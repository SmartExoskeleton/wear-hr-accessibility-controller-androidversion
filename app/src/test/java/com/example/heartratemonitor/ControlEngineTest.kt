// Created by ravishan_n on 2026-03-10
package com.example.heartratemonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ControlEngineTest {

    private class FakeClock(var now: Long = 0L) {
        fun tick(ms: Long) {
            now += ms
        }
    }

    @Test
    fun noSwitchBeforeDwell() {
        val clock = FakeClock()
        val engine = ControlEngine(
            initialMode = DeviceMode.FITNESS,
            config = ControlConfig(highThreshold = 100, lowThreshold = 80, dwellHighMs = 5000),
            nowMs = { clock.now }
        )

        val d1 = engine.onSample(105, autoEnabled = true)
        assertNull(d1.switchTarget)
        clock.tick(3000)
        val d2 = engine.onSample(106, autoEnabled = true)
        assertNull(d2.switchTarget)
    }

    @Test
    fun switchesAfterDwell() {
        val clock = FakeClock()
        val engine = ControlEngine(
            initialMode = DeviceMode.FITNESS,
            config = ControlConfig(highThreshold = 100, lowThreshold = 80, dwellHighMs = 5000),
            nowMs = { clock.now }
        )

        engine.onSample(105, autoEnabled = true)
        clock.tick(5000)
        val decision = engine.onSample(105, autoEnabled = true)
        assertEquals(DeviceMode.ECO, decision.switchTarget)
    }

    @Test
    fun cooldownBlocksImmediateReswitch() {
        val clock = FakeClock()
        val engine = ControlEngine(
            initialMode = DeviceMode.FITNESS,
            config = ControlConfig(
                highThreshold = 100,
                lowThreshold = 80,
                dwellHighMs = 1000,
                dwellLowMs = 1000,
                cooldownMs = 6000,
            ),
            nowMs = { clock.now }
        )

        engine.onSample(110, autoEnabled = true)
        clock.tick(1000)
        val toEco = engine.onSample(110, autoEnabled = true)
        assertEquals(DeviceMode.ECO, toEco.switchTarget)
        engine.onSwitchSuccess(DeviceMode.ECO)

        engine.onSample(70, autoEnabled = true)
        clock.tick(1000)
        val blocked = engine.onSample(70, autoEnabled = true)
        assertNull(blocked.switchTarget)
        assertEquals("cooldown", blocked.reason)
    }

    @Test
    fun manualOverrideCreatesHoldWindow() {
        val clock = FakeClock()
        val engine = ControlEngine(
            config = ControlConfig(highThreshold = 100, lowThreshold = 80, manualOverrideHoldMs = 10000),
            nowMs = { clock.now }
        )

        val manual = engine.onManualOverride(DeviceMode.FITNESS)
        assertEquals("manual_override", manual.reason)

        val held = engine.onSample(110, autoEnabled = true)
        assertEquals("manual_hold", held.reason)
        assertNull(held.switchTarget)

        clock.tick(10001)
        engine.onSample(110, autoEnabled = true)
        clock.tick(5000)
        val request = engine.onSample(110, autoEnabled = true)
        assertNotNull(request.switchTarget)
    }
}
