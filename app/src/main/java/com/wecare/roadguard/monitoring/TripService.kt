package com.wecare.roadguard.monitoring

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.*
import android.location.Location
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.wecare.roadguard.*
import com.wecare.roadguard.R
import com.wecare.roadguard.data.LocalTrip
import com.wecare.roadguard.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID
import kotlin.math.sqrt

data class MonitoringState(val tripId: String? = null, val role: TravelRole = TravelRole.Driver,
    val phase: TripPhase = TripPhase.Idle, val running: Boolean = false, val degraded: Boolean = false,
    val speedKmh: Double? = null, val location: Map<String, Any>? = null,
    val overrideUntil: Long = 0, val impactDeadline: Long = 0, val impactEnabled: Boolean = false,
    val problem: String? = null)

object TripRuntime {
    internal val mutable = MutableStateFlow(MonitoringState())
    val state = mutable.asStateFlow()
    var inVehicle = false
}

class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!TripRuntime.state.value.running) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        TripRuntime.inVehicle = result.getActivityConfidence(DetectedActivity.IN_VEHICLE) >= 50
    }
}

class TripService : Service(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as RoadGuardApp
    private val repo get() = app.repository
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val detector = ImpactDetector()
    private var engine = SafetyEngine()
    private var local: LocalTrip? = null
    private var lastFixAt = 0L
    private var lastUploadAt = 0L
    private var lastSpeed = 0.0
    private var rotation = 0.0
    private var speedDrop = 0.0
    private var speedDropAt = 0L
    private var tone: ToneGenerator? = null
    private var tick: Job? = null
    private var starting = false
    private val activityIntent by lazy { PendingIntent.getBroadcast(this, 20, Intent(this, ActivityReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE) }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.lastLocation?.let { acceptLocation(it) } }
        override fun onLocationAvailability(value: LocationAvailability) {
            if (!value.isLocationAvailable) { engine.missingFix(); update { copy(degraded = true) } }
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> scope.launch { endTrip() }
            "OVERRIDE" -> {
                if (local != null) { update { copy(overrideUntil = SystemClock.elapsedRealtime() + 60_000) }; log("override") }
            }
            "CANCEL_IMPACT" -> { update { copy(impactDeadline = 0) }; tone?.stopTone(); log("impact-cancelled") }
            "SOS", "IMPACT_SOS" -> scope.launch { sendAlert(if (intent.action == "IMPACT_SOS") "suspected-impact" else "manual") }
            else -> if (!TripRuntime.state.value.running && !starting) {
                starting = true
                startForeground(101, notification("Starting trip monitoring"))
                scope.launch { startMonitoring() }
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun startMonitoring() {
        val trip = app.database.dao().activeTrip()
        if (trip == null || trip.ownerUid != repo.uid) { stopSelf(); return }
        local = trip
        val previous = runCatching { TripPhase.valueOf(trip.phase) }.getOrDefault(TripPhase.Monitoring)
        engine = SafetyEngine(initial = previous)
        val impact = trip.impactOptIn && (BuildConfig.INTERNAL_BUILD || BuildConfig.IMPACT_VALIDATED)
        TripRuntime.mutable.value = MonitoringState(trip.id, TravelRole.valueOf(trip.role), previous, running = true, impactEnabled = impact)
        lastFixAt = SystemClock.elapsedRealtime()
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) throw SecurityException()
            fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000).setMinUpdateIntervalMillis(2_000).build(), callback, Looper.getMainLooper()).awaitResult()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
                ActivityRecognition.getClient(this).requestActivityUpdates(10_000, activityIntent)
            if (impact) {
                val acceleration = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                if (acceleration != null && gyro != null) {
                    sensors.registerListener(this, acceleration, SensorManager.SENSOR_DELAY_GAME)
                    sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
                } else update { copy(impactEnabled = false, problem = "Impact monitoring unavailable: required sensors are missing.") }
            }
            app.database.dao().saveTrip(trip.copy(interrupted = false))
            if (trip.interrupted) log("resumed")
            tick = scope.launch {
                while (isActive) {
                    delay(1_000)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFixAt > 30_000) { engine.missingFix(); update { copy(degraded = true) } }
                    val deadline = TripRuntime.state.value.impactDeadline
                    if (deadline > 0 && now >= deadline) sendAlert("suspected-impact")
                    getSystemService(NotificationManager::class.java).notify(101, notification(
                        if (deadline > 0) "Suspected impact: open RoadGuard to respond" else if (TripRuntime.state.value.degraded) "Monitoring degraded • check location" else "${engine.phase} • ${trip.role} mode"))
                }
            }
        } catch (_: Exception) {
            update { copy(running = false, degraded = true, problem = "Monitoring could not start. Check location permissions and resume.") }
            app.database.dao().saveTrip(trip.copy(interrupted = true)); stopSelf()
        } finally { starting = false }
    }
    private fun acceptLocation(location: Location) {
        val trip = local ?: return
        val now = SystemClock.elapsedRealtime()
        // Never let cached or out-of-order fixes count as sustained movement.
        if (now - location.elapsedRealtimeNanos / 1_000_000 > 15_000) return
        lastFixAt = now
        val speed = if (location.hasSpeed() && (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= 5)) location.speed * 3.6 else null
        engine.sample(speed, location.accuracy, now, TripRuntime.inVehicle)
        speedDrop = if (speed != null) (lastSpeed - speed).coerceAtLeast(0.0) else 0.0
        speedDropAt = now; if (speed != null) lastSpeed = speed
        val point = mapOf("lat" to location.latitude, "lng" to location.longitude, "accuracy" to location.accuracy.toDouble(), "capturedAt" to location.time)
        update { copy(phase = engine.phase, degraded = engine.degraded, speedKmh = speed, location = point) }
        val saved = trip.copy(phase = engine.phase.name, interrupted = false)
        local = saved
        scope.launch {
            app.database.dao().saveTrip(saved)
            val interval = if (engine.phase == TripPhase.Stopped) 30_000 else 10_000
            if (now - lastUploadAt >= interval) {
                lastUploadAt = now
                repo.enqueue("location", mapOf("tripId" to trip.id, "location" to point, "phase" to engine.phase.name, "degraded" to engine.degraded))
            }
        }
    }
    private suspend fun sendAlert(kind: String) {
        val trip = local ?: return
        update { copy(impactDeadline = 0) }; tone?.stopTone()
        repo.enqueue("createAlert", mapOf("tripId" to trip.id, "alertId" to UUID.randomUUID().toString(), "kind" to kind,
            "occurredAt" to System.currentTimeMillis(), "location" to TripRuntime.state.value.location))
        app.scope.launch { repo.sync() }
    }
    private suspend fun endTrip() {
        val trip = local ?: app.database.dao().activeTrip()
        if (trip != null) {
            app.database.dao().saveTrip(trip.copy(active = false, phase = "Ended", interrupted = false))
            repo.enqueue("endTrip", mapOf("tripId" to trip.id, "occurredAt" to System.currentTimeMillis()))
        }
        engine.end(); update { copy(running = false, phase = TripPhase.Ended, overrideUntil = 0, impactDeadline = 0) }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun log(kind: String) {
        val trip = local ?: return
        scope.launch { repo.enqueue("tripEvent", mapOf("tripId" to trip.id, "kind" to kind, "occurredAt" to System.currentTimeMillis())) }
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (!TripRuntime.state.value.impactEnabled) return
        val magnitude = sqrt(event.values.take(3).sumOf { (it * it).toDouble() })
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) { rotation = magnitude; return }
        val now = SystemClock.elapsedRealtime()
        if (detector.sample(magnitude / SensorManager.GRAVITY_EARTH, rotation, if (now - speedDropAt < 2_000) speedDrop else 0.0, now)
            && TripRuntime.state.value.impactDeadline == 0L) {
            update { copy(impactDeadline = now + 30_000) }
            tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }.getOrNull()
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 30_000)
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500), -1))
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    private fun update(change: MonitoringState.() -> MonitoringState) { TripRuntime.mutable.value = TripRuntime.state.value.change() }
    private fun notification(label: String): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "monitoring").setSmallIcon(R.drawable.ic_shield).setContentTitle("RoadGuard is watching your trip")
            .setContentText(label).setOngoing(true).setContentIntent(pending).build()
    }
    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
                ActivityRecognition.getClient(this).removeActivityUpdates(activityIntent)
        } catch (_: SecurityException) { /* Permission may be revoked during a trip. */ }
        sensors.unregisterListener(this); tick?.cancel(); tone?.release()
        val trip = local
        if (trip != null && TripRuntime.state.value.phase != TripPhase.Ended) app.scope.launch {
            app.database.dao().saveTrip(trip.copy(interrupted = true))
        }
        update { copy(running = false, overrideUntil = 0, impactDeadline = 0) }
        TripRuntime.inVehicle = false
        scope.cancel(); super.onDestroy()
    }
}
private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T = await()
