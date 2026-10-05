package com.wecare.roadguard.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wecare.roadguard.RoadGuardApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wecare.roadguard.MainActivity
import com.wecare.roadguard.R
import kotlinx.coroutines.launch

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if ((applicationContext as RoadGuardApp).repository.sync()) Result.success() else Result.retry()
}
class RoadGuardMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val app = application as RoadGuardApp
        app.scope.launch { runCatching { app.repository.registerDevice() } }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        if ((application as RoadGuardApp).repository.uid == null) return
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "safety").setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(message.notification?.title ?: "RoadGuard update")
            .setContentText("Open RoadGuard to view the update.").setContentIntent(pending).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(this).notify((message.messageId ?: "update").hashCode(), notification) } catch (_: SecurityException) { }
    }
}
