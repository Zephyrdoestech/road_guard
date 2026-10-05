package com.wecare.roadguard.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.firestore.ListenerRegistration
import com.wecare.roadguard.RoadGuardApp
import com.wecare.roadguard.data.*
import com.wecare.roadguard.domain.*
import com.wecare.roadguard.monitoring.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class AppState(val signedIn: Boolean = false, val verified: Boolean = false, val busy: Boolean = false,
    val cloudReady: Boolean = false,
    val email: String = "", val uid: String = "", val error: String? = null, val notice: String? = null,
    val contacts: List<CloudRecord> = emptyList(), val trips: List<CloudRecord> = emptyList(),
    val alerts: List<CloudRecord> = emptyList(), val messages: List<CloudRecord> = emptyList(),
    val outbox: List<PendingOperation> = emptyList(), val localTrips: List<LocalTrip> = emptyList(),
    val inviteCode: String? = null, val online: Boolean = false)

class RoadGuardViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RoadGuardApp
    val repo = app.repository
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    val monitoring = TripRuntime.state
    private val listeners = mutableListOf<ListenerRegistration>()
    private val sharedListeners = mutableListOf<ListenerRegistration>()
    private val recordBuckets = mutableMapOf<String, List<CloudRecord>>()
    private val jobs = mutableListOf<Job>()
    private var attached = false
    private val operationMutex = Mutex()
    init {
        refreshUser()
        viewModelScope.launch { while (isActive) { mutable.update { it.copy(online = repo.online()) }; delay(2_000) } }
    }
    fun clearError() { mutable.update { it.copy(error = null, notice = null) } }
    fun report(message: String) { mutable.update { it.copy(error = message) } }
    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
          operationMutex.withLock {
            mutable.update { it.copy(busy = true, error = null) }
            try { block() } catch (e: Exception) { if (e is CancellationException) throw e; report(e.localizedMessage ?: "This action could not be completed.") }
            finally { mutable.update { it.copy(busy = false) } }
          }
        }
    }
    fun authenticate(email: String, password: String, name: String, register: Boolean) = work {
        val auth = repo.auth ?: error("Firebase is not configured. See README.md to connect your project.")
        if (register) {
            auth.createUserWithEmailAndPassword(email.trim(), password).await()
            auth.currentUser?.updateProfile(com.google.firebase.auth.UserProfileChangeRequest.Builder().setDisplayName(name).build())?.await()
            auth.currentUser?.sendEmailVerification()?.await()
        } else auth.signInWithEmailAndPassword(email.trim(), password).await()
        loadUser()
    }
    fun refreshUser() = work { loadUser() }
    private suspend fun loadUser() {
        if (repo.online()) repo.auth?.currentUser?.reload()?.await()
        val user = repo.auth?.currentUser
        mutable.update { it.copy(signedIn = user != null, verified = user?.isEmailVerified == true, email = user?.email.orEmpty(), uid = user?.uid.orEmpty()) }
        if (user?.isEmailVerified == true && !attached) {
            if (repo.online()) repo.bootstrap(user.displayName.orEmpty())
            attach(); repo.scheduleSync()
        }
    }
    fun resendVerification() = work { repo.auth?.currentUser?.sendEmailVerification()?.await(); mutable.update { it.copy(notice = "Verification email sent.") } }
    fun resetPassword(email: String) = work { repo.auth?.sendPasswordResetEmail(email.trim())?.await(); mutable.update { it.copy(notice = "Check your email for password reset instructions.") } }
    private fun attach() {
        detach(); attached = true
        mutable.update { it.copy(cloudReady = true) }
        val uid = repo.uid ?: return
        jobs += viewModelScope.launch { repo.dao.observeOutbox(uid).collect { list -> mutable.update { it.copy(outbox = list) } } }
        jobs += viewModelScope.launch { repo.dao.trips(uid).collect { list -> mutable.update { it.copy(localTrips = list) } } }
        for (collection in listOf("trips", "alerts")) listeners += repo.observeRecords(collection, uid, { bucket("$collection:own", it) }, ::report)
        listeners += repo.observeContacts({ contacts ->
            mutable.update { it.copy(contacts = contacts) }
            sharedListeners.forEach { it.remove() }; sharedListeners.clear()
            recordBuckets.keys.filter { !it.endsWith(":own") }.toList().forEach { recordBuckets.remove(it) }
            publishBuckets()
            for (contact in contacts) {
                for (collection in listOf("trips", "alerts")) sharedListeners += repo.observeRecords(collection, contact.id,
                    { bucket("$collection:${contact.id}", it) }, ::report)
                for (out in listOf(true, false)) sharedListeners += repo.observeMessages(contact.id, out,
                    { bucket("messages:${contact.id}:$out", it) }, ::report)
            }
        }, ::report)
    }
    private fun bucket(key: String, records: List<CloudRecord>) { recordBuckets[key] = records; publishBuckets() }
    private fun publishBuckets() {
        fun records(prefix: String) = recordBuckets.filterKeys { it.startsWith(prefix) }.values.flatten().distinctBy { it.id }
        mutable.update { it.copy(trips = records("trips").sortedByDescending { r -> r.number("createdAt") },
            alerts = records("alerts").sortedByDescending { r -> r.number("occurredAt") },
            messages = records("messages").sortedBy { r -> r.number("occurredAt") }) }
    }
    private fun detach() { listeners.forEach { it.remove() }; sharedListeners.forEach { it.remove() }; listeners.clear(); sharedListeners.clear(); jobs.forEach { it.cancel() }; jobs.clear(); recordBuckets.clear(); attached = false }
    fun createInvite() = work {
        val result = repo.command("createInvite")
        mutable.update { it.copy(inviteCode = result["code"] as String) }
    }
    fun acceptInvite(code: String) = work { repo.command("acceptInvite", mapOf("code" to code)); mutable.update { it.copy(notice = "Contact added to your trusted circle.") } }
    fun revokeContact(uid: String) = work { repo.command("revokeContact", mapOf("uid" to uid)) }
    fun startTrip(role: TravelRole, recipients: Set<String>, impact: Boolean) = work {
        check(repo.dao.activeTrip() == null) { "End or resume your current trip first." }
        val id = UUID.randomUUID().toString()
        repo.command("startTrip", mapOf("tripId" to id, "role" to role.name, "recipientUids" to recipients.toList()), id)
        repo.dao.saveTrip(LocalTrip(id, repo.uid!!, role.name, impactOptIn = impact))
        ContextCompat.startForegroundService(app, Intent(app, TripService::class.java))
    }
    fun resumeTrip() = work {
        val trip = repo.dao.activeTrip() ?: error("No interrupted trip found.")
        repo.dao.saveTrip(trip.copy(interrupted = true))
        ContextCompat.startForegroundService(app, Intent(app, TripService::class.java))
    }
    fun recoverTrip(trip: CloudRecord) = work {
        check(trip.text("ownerUid") == repo.uid && trip.flag("active")) { "Only your active trip can be recovered." }
        repo.dao.saveTrip(LocalTrip(trip.id, repo.uid!!, trip.text("role"), phase = trip.text("phase"), interrupted = true, startedAt = trip.number("createdAt")))
    }
    fun endTrip() = work {
        if (monitoring.value.running) service("STOP")
        else {
            val trip = repo.dao.activeTrip() ?: return@work
            repo.dao.saveTrip(trip.copy(active = false, phase = "Ended"))
            repo.enqueue("endTrip", mapOf("tripId" to trip.id, "occurredAt" to System.currentTimeMillis()))
            TripRuntime.mutable.value = MonitoringState(phase = TripPhase.Ended)
        }
    }
    fun service(action: String) {
        if (monitoring.value.running) app.startService(Intent(app, TripService::class.java).setAction(action))
        else report("Resume your trip to use this control.")
    }
    fun shareTrip(tripId: String, recipients: Set<String>) = work { repo.command("shareTrip", mapOf("tripId" to tripId, "recipientUids" to recipients.toList())) }
    fun sendText(receiver: String, text: String, voiceConfirmed: Boolean = false) = work {
        val m = monitoring.value
        check(voiceConfirmed || !typingBlocked(m.role, m.phase, SystemClock.elapsedRealtime(), m.overrideUntil)) { "Use a confirmed voice message while driving." }
        check(text.isNotBlank()) { "Add a message first." }
        repo.enqueue("sendMessage", mapOf("messageId" to UUID.randomUUID().toString(), "receiverUid" to receiver,
            "kind" to "text", "text" to text, "occurredAt" to System.currentTimeMillis()))
    }
    fun sendVoice(receiver: String, file: java.io.File, duration: Long) = work {
        val id = UUID.randomUUID().toString()
        repo.enqueue("sendMessage", mapOf("messageId" to id, "receiverUid" to receiver, "kind" to "voice", "audioPath" to "voice/${repo.uid}/$receiver/$id.m4a",
            "durationMs" to duration.coerceIn(1, 60_000), "occurredAt" to System.currentTimeMillis()), file.absolutePath)
    }
    fun markRead(message: CloudRecord) {
        if (message.text("receiverUid") == repo.uid && message.number("readAt") == 0L)
            viewModelScope.launch { runCatching { repo.command("readMessage", mapOf("messageId" to message.id)) } }
    }
    fun acknowledge(id: String) = work { repo.command("acknowledgeAlert", mapOf("alertId" to id)) }
    fun resolve(id: String, cancelled: Boolean) = work { repo.enqueue("resolveAlert", mapOf("alertId" to id, "status" to if (cancelled) "cancelled" else "resolved")) }
    fun retry(id: String) = work { repo.dao.status(id, "queued"); repo.scheduleSync() }
    fun discard(operation: PendingOperation) = work { operation.localAudio?.let { java.io.File(it).delete() }; repo.dao.remove(operation.operationId) }
    fun signOut() = work {
        check(repo.dao.activeTrip() == null) { "End your trip before signing out." }
        check(mutable.value.outbox.isEmpty()) { "Send or discard pending operations before signing out." }
        if (repo.auth?.currentUser?.isEmailVerified == true) repo.signOut() else repo.auth?.signOut()
        detach(); mutable.value = AppState()
    }
    fun deleteAccount(password: String) = work {
        check(repo.dao.activeTrip() == null) { "End your trip before deleting your account." }
        val user = repo.auth?.currentUser ?: return@work
        user.reauthenticate(EmailAuthProvider.getCredential(user.email!!, password)).await()
        repo.deleteAccount(); detach(); mutable.value = AppState()
    }
    override fun onCleared() { detach(); super.onCleared() }
}
