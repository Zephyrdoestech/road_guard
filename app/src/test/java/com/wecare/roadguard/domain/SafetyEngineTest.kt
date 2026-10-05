package com.wecare.roadguard.domain

import org.junit.Assert.*
import org.junit.Test

class SafetyEngineTest {
    @Test fun sustainedMovementRequiresTime() {
        val e = SafetyEngine()
        assertEquals(TripPhase.Monitoring, e.sample(25.0, 5f, 0, true))
        assertEquals(TripPhase.Monitoring, e.sample(25.0, 5f, 9_999, true))
        assertEquals(TripPhase.Moving, e.sample(25.0, 5f, 10_000, true))
    }
    @Test fun missingGpsAndShortStopsNeverUnlock() {
        val e = SafetyEngine(initial = TripPhase.Moving)
        e.sample(0.0, 5f, 0); e.sample(null, 100f, 10_000)
        assertTrue(e.degraded); assertEquals(TripPhase.Moving, e.phase)
        for (t in 20_000L..130_000L step 10_000) e.sample(0.0, 5f, t)
        assertEquals(TripPhase.Moving, e.phase)
        assertEquals(TripPhase.Stopped, e.sample(0.0, 5f, 140_000))
    }
    @Test fun longGapDoesNotCountAsStationaryEvidence() {
        val e = SafetyEngine(initial = TripPhase.Moving)
        e.sample(0.0, 5f, 0); e.sample(0.0, 5f, 180_000)
        assertEquals(TripPhase.Moving, e.phase)
    }
    @Test fun overrideAndRoleAreIndependentOfGps() {
        assertFalse(typingBlocked(TravelRole.Passenger, TripPhase.Moving, 60_000, 0))
        assertFalse(typingBlocked(TravelRole.Driver, TripPhase.Moving, 59_999, 60_000))
        assertTrue(typingBlocked(TravelRole.Driver, TripPhase.Moving, 60_000, 60_000))
    }
    @Test fun spikeAloneIsNotAnImpact() {
        val d = ImpactDetector()
        assertFalse(d.sample(6.0, 0.0, 0.0, 0))
        assertFalse(d.sample(1.0, 4.0, 0.0, 3_000))
        assertTrue(d.sample(6.0, 4.0, 0.0, 4_000))
        assertFalse(d.sample(6.0, 4.0, 0.0, 5_000))
    }
}
