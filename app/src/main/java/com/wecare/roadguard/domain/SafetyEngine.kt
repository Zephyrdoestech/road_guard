package com.wecare.roadguard.domain

enum class TripPhase { Idle, Monitoring, Moving, Stopped, Ended }
enum class TravelRole { Driver, Passenger }
data class DetectionConfig(val movingKmh: Double = 10.0, val stoppedKmh: Double = 3.0,
    val enterMillis: Long = 10_000, val stopMillis: Long = 120_000, val maxAccuracyMeters: Float = 40f)

/** Monotonic time only. Missing/low quality fixes never remove protection. */
class SafetyEngine(private val config: DetectionConfig = DetectionConfig(), initial: TripPhase = TripPhase.Monitoring) {
    var phase = initial; private set
    var degraded = false; private set
    private var fastSince: Long? = null
    private var slowSince: Long? = null
    private var previousAt: Long? = null
    fun sample(speedKmh: Double?, accuracyMeters: Float, now: Long, inVehicle: Boolean = false): TripPhase {
        if (phase == TripPhase.Ended || phase == TripPhase.Idle) return phase
        val previous = previousAt
        if (previous != null && now <= previous) return phase
        if (previous != null && now - previous > 15_000) { fastSince = null; slowSince = null }
        previousAt = now
        if (speedKmh == null || !speedKmh.isFinite() || speedKmh < 0 || accuracyMeters > config.maxAccuracyMeters || accuracyMeters < 0) {
            missingFix(); return phase
        }
        degraded = false
        // Activity recognition adds context; speed remains required, never infer the occupant's role.
        val enter = if (inVehicle) config.enterMillis else config.enterMillis + 5_000
        if (speedKmh > config.movingKmh) {
            slowSince = null
            if (fastSince == null) fastSince = now
            if (now - fastSince!! >= enter) phase = TripPhase.Moving
        } else if (speedKmh < config.stoppedKmh) {
            fastSince = null
            if (slowSince == null) slowSince = now
            if (now - slowSince!! >= config.stopMillis) phase = TripPhase.Stopped
        } else { fastSince = null; slowSince = null }
        return phase
    }
    fun missingFix() { degraded = true; fastSince = null; slowSince = null }
    fun end() { phase = TripPhase.Ended }
}

fun typingBlocked(role: TravelRole, phase: TripPhase, now: Long, overrideUntil: Long): Boolean =
    role == TravelRole.Driver && phase == TripPhase.Moving && now >= overrideUntil

data class ImpactConfig(val version: String = "experimental-1", val accelerationG: Double = 4.5,
    val rotationRad: Double = 2.5, val speedDropKmh: Double = 15.0)

/** Candidate detector only; requires corroboration within a short window, then a human countdown. */
class ImpactDetector(private val config: ImpactConfig = ImpactConfig()) {
    private var lastSpike = Long.MIN_VALUE / 2
    private var lastRotation = Long.MIN_VALUE / 2
    private var lastDrop = Long.MIN_VALUE / 2
    private var lastTrigger = Long.MIN_VALUE / 2
    fun sample(accelerationG: Double, rotation: Double, speedDrop: Double, now: Long): Boolean {
        if (accelerationG >= config.accelerationG) lastSpike = now
        if (rotation >= config.rotationRad) lastRotation = now
        if (speedDrop >= config.speedDropKmh) lastDrop = now
        val candidate = now - lastSpike <= 2_000 && (now - lastRotation <= 2_000 || now - lastDrop <= 2_000)
        if (candidate && now - lastTrigger > 60_000) { lastTrigger = now; return true }
        return false
    }
}
