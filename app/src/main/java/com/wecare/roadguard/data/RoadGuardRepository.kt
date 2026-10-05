package com.wecare.roadguard.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.wecare.roadguard.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CloudRecord(val id: String, val fields: Map<String, Any?>) {
    fun text(key: String) = fields[key] as? String ?: ""
    fun number(key: String) = (fields[key] as? Number)?.toLong() ?: 0L
    fun flag(key: String) = fields[key] == true
}

class RoadGuardRepository(private val context: Context, val dao: LocalDao) {
    val configured get() = FirebaseApp.getApps(context).isNotEmpty()
    val auth: FirebaseAuth? get() = if (configured) FirebaseAuth.getInstance() else null
    val uid get() = auth?.currentUser?.uid
    private val functions get() = FirebaseFunctions.getInstance(BuildConfig.FUNCTIONS_REGION)
    private val db get() = FirebaseFirestore.getInstance()
    private val syncMutex = Mutex()
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val deviceId: String get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit { putString("deviceId", it) } }
    fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
    suspend fun command(action: String, payload: Map<String, Any?> = emptyMap(), operationId: String = UUID.randomUUID().toString()): Map<String, Any?> {
        check(configured) { "Firebase is not configured. Follow the setup guide." }
        val user = auth?.currentUser ?: error("Sign in first.")
        check(user.isEmailVerified) { "Verify your email first." }
        @Suppress("UNCHECKED_CAST")
        return functions.getHttpsCallable("command").call(mapOf("action" to action, "operationId" to operationId, "payload" to payload)).await().data as Map<String, Any?>
    }
    suspend fun bootstrap(name: String) {
        auth?.currentUser?.getIdToken(true)?.await()
        command("bootstrap", mapOf("displayName" to name.ifBlank { auth?.currentUser?.email?.substringBefore('@') ?: "RoadGuard member" }))
        runCatching { registerDevice() }
    }
    suspend fun registerDevice() {
        if (BuildConfig.USE_FIREBASE_EMULATORS) return // FCM has no local emulator.
        if (auth?.currentUser?.isEmailVerified != true) return
        val token = FirebaseMessaging.getInstance().token.await()
        command("registerDevice", mapOf("deviceId" to deviceId, "token" to token))
    }
    suspend fun enqueue(action: String, payload: Map<String, Any?>, localAudio: String? = null, op: String = UUID.randomUUID().toString()) {
        val owner = uid ?: error("Sign in first.")
        dao.enqueue(PendingOperation(operationId = op, ownerUid = owner, action = action, payload = JSONObject(payload).toString(), localAudio = localAudio))
        scheduleSync()
    }
    fun scheduleSync() {
        WorkManager.getInstance(context).enqueueUniqueWork("outbox", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    suspend fun sync(): Boolean = syncMutex.withLock {
        val owner = uid ?: return@withLock true
        while (uid == owner) {
            val item = dao.next(owner) ?: return@withLock true
            try {
                val payload = JSONObject(item.payload).toMap()
                if (item.localAudio != null) {
                    val file = File(item.localAudio)
                    check(file.exists()) { "The voice recording is no longer available." }
                    val ref = FirebaseStorage.getInstance().reference.child(payload["audioPath"] as String)
                    // A completed upload may already exist if the function call was interrupted.
                    val exists = runCatching { ref.metadata.await() }.isSuccess
                    if (!exists) ref.putFile(android.net.Uri.fromFile(file), StorageMetadata.Builder().setContentType("audio/mp4").build()).await()
                }
                command(item.action, payload, item.operationId)
                item.localAudio?.let { File(it).delete() }
                dao.remove(item.operationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val code = (e as? FirebaseFunctionsException)?.code
                if (code in listOf(FirebaseFunctionsException.Code.INVALID_ARGUMENT, FirebaseFunctionsException.Code.PERMISSION_DENIED,
                        FirebaseFunctionsException.Code.NOT_FOUND, FirebaseFunctionsException.Code.FAILED_PRECONDITION, FirebaseFunctionsException.Code.ALREADY_EXISTS)
                    || e is IllegalStateException) {
                    dao.status(item.operationId, "failed", e.message ?: "Operation could not be sent.")
                } else return@withLock false
            }
        }
        true
    }
    fun observeContacts(onData: (List<CloudRecord>) -> Unit, onError: (String) -> Unit): ListenerRegistration =
        db.collection("users").document(uid!!).collection("contacts").addSnapshotListener { data, error ->
            if (error != null) { onData(emptyList()); onError("Trusted circle is unavailable. Reconnect to refresh access.") }
            else onData(data?.documents.orEmpty().map { CloudRecord(it.id, it.data.orEmpty()) })
        }
    fun observeRecords(collection: String, owner: String, onData: (List<CloudRecord>) -> Unit, onError: (String) -> Unit): ListenerRegistration {
        var query: Query = db.collection(collection).whereEqualTo("ownerUid", owner)
        if (owner != uid) query = query.whereArrayContains("participantUids", uid!!)
        // Rules enforce relationship access; TTL is also applied locally while scheduled deletion runs.
        return query.addSnapshotListener { data, error ->
            if (error != null) { onData(emptyList()); onError("A shared $collection feed is unavailable or access was revoked.") }
            else onData(data?.documents.orEmpty().filter { (it.getTimestamp("expiresAt")?.toDate()?.time ?: 0) > System.currentTimeMillis() }
                .map { CloudRecord(it.id, it.data.orEmpty()) })
        }
    }
    fun observeMessages(other: String, outgoing: Boolean, onData: (List<CloudRecord>) -> Unit, onError: (String) -> Unit): ListenerRegistration =
        db.collection("messages").whereEqualTo("senderUid", if (outgoing) uid else other)
            .whereEqualTo("receiverUid", if (outgoing) other else uid)
            .addSnapshotListener { data, error ->
                if (error != null) { onData(emptyList()); onError("Messages are unavailable or contact access was revoked.") }
                else onData(data?.documents.orEmpty().filter { (it.getTimestamp("expiresAt")?.toDate()?.time ?: 0) > System.currentTimeMillis() }
                    .map { CloudRecord(it.id, it.data.orEmpty()) })
            }
    suspend fun audioFile(path: String): File {
        val file = File.createTempFile("playback", ".m4a", context.cacheDir)
        FirebaseStorage.getInstance().reference.child(path).getFile(file).await()
        return file
    }
    suspend fun signOut() {
        val owner = uid ?: return
        // Do not leave an old account's push token attached after switching accounts.
        command("removeDevice", mapOf("deviceId" to deviceId))
        dao.clearOutbox(owner); dao.clearTrips(owner)
        context.filesDir.resolve("voice").deleteRecursively()
        auth?.signOut()
    }
    suspend fun deleteAccount() {
        val owner = uid ?: return
        functions.getHttpsCallable("deleteAccount").call().await()
        dao.clearOutbox(owner); dao.clearTrips(owner)
        context.filesDir.resolve("voice").deleteRecursively()
        auth?.signOut()
    }
}

fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key -> convert(get(key)) }
private fun convert(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> value.toMap()
    is JSONArray -> (0 until value.length()).map { convert(value.get(it)) }
    else -> value
}
