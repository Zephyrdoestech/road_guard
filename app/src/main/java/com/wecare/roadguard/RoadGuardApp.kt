package com.wecare.roadguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import androidx.work.*
import com.google.firebase.FirebaseApp
import com.wecare.roadguard.data.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class RoadGuardApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var database: LocalStore; private set
    lateinit var repository: RoadGuardRepository; private set
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("monitoring", "Trip monitoring", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("safety", "Safety and circle updates", NotificationManager.IMPORTANCE_HIGH))
        database = Room.databaseBuilder(this, LocalStore::class.java, "roadguard.db").build()
        if (BuildConfig.USE_FIREBASE_EMULATORS && FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this, com.google.firebase.FirebaseOptions.Builder()
                .setApplicationId("1:1234567890:android:abcdef0123456789").setApiKey("demo-api-key")
                .setProjectId("demo-roadguard").setStorageBucket("demo-roadguard.appspot.com").build())
        } else FirebaseApp.initializeApp(this)
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            if (BuildConfig.USE_FIREBASE_EMULATORS) {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled = false
                check(FirebaseApp.getInstance().options.projectId == "demo-roadguard") { "Emulator builds must use the demo-roadguard project. Remove google-services.json for emulator testing." }
                com.google.firebase.auth.FirebaseAuth.getInstance().useEmulator("10.0.2.2", 9099)
                com.google.firebase.firestore.FirebaseFirestore.getInstance().useEmulator("10.0.2.2", 8080)
                com.google.firebase.functions.FirebaseFunctions.getInstance(BuildConfig.FUNCTIONS_REGION).useEmulator("10.0.2.2", 5001)
                com.google.firebase.storage.FirebaseStorage.getInstance().useEmulator("10.0.2.2", 9199)
            }
            com.google.firebase.firestore.FirebaseFirestore.getInstance().firestoreSettings =
                com.google.firebase.firestore.FirebaseFirestoreSettings.Builder().setLocalCacheSettings(
                    com.google.firebase.firestore.MemoryCacheSettings.newBuilder().build()).build()
        }
        repository = RoadGuardRepository(this, database.dao())
        scope.launch {
            database.dao().markInterrupted()
            database.dao().activeTrip()?.let { trip ->
                if (repository.uid == trip.ownerUid) repository.enqueue("tripEvent", mapOf("tripId" to trip.id,
                    "kind" to "interrupted", "occurredAt" to System.currentTimeMillis()))
            }
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            database.dao().pruneTrips(cutoff)
            database.dao().expiredOperations(cutoff).forEach {
                it.localAudio?.let { path -> java.io.File(path).delete() }
                database.dao().remove(it.operationId)
            }
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("outbox-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}
