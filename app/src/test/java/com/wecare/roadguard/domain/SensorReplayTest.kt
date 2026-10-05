package com.wecare.roadguard.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SensorReplayTest {
    @Test fun replayLabeledTrace() {
        val path = System.getProperty("roadguard.replay").orEmpty()
        val trace = if (path.isBlank()) javaClass.getResourceAsStream("/synthetic-trip.csv")!!.bufferedReader().readLines() else File(path).readLines()
        val engine = SafetyEngine()
        val impact = ImpactDetector()
        var samples = 0
        trace.drop(1).filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val c = line.split(','); val time = c[0].toLong()
            val phase = engine.sample(c[1].toDoubleOrNull(), c[2].toFloat(), time, c[3].toBooleanStrict())
            assertEquals("phase at $time ms", c[4], phase.name)
            val candidate = impact.sample(c[5].toDouble(), c[6].toDouble(), c[7].toDouble(), time)
            assertEquals("impact candidate at $time ms", c[8].toBooleanStrict(), candidate)
            samples++
        }
        println("Replayed $samples labeled samples; synthetic validation does not establish field accuracy.")
    }
}
