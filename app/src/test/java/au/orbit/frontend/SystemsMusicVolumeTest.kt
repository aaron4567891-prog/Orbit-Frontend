package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test

class SystemsMusicVolumeTest {
    @Test fun thorBoostTapersToTheExistingMaximumWithoutExceedingUnity() {
        val gains = (0..15).map { SystemsMusicVolume.gain(it, 15, true, false) }
        assertEquals(0f, gains.first(), 0f)
        assertEquals(0.614f, gains.last(), 0.00001f)
        gains.drop(1).forEach { assertTrue(it in 0.614f..1f) }
        gains.drop(1).zipWithNext().forEach { (before, after) -> assertTrue(after < before) }
    }

    @Test fun muteAndZeroRemainSilentEvenWithASeparateVolumeRoute() {
        for (softwareVolume in listOf(false, true)) {
            assertEquals(0f, SystemsMusicVolume.gain(8, 15, softwareVolume, true), 0f)
            assertEquals(0f, SystemsMusicVolume.gain(0, 15, softwareVolume, false), 0f)
        }
    }

    @Test fun lowStepsDoNotApplyASecondFullVolumeAttenuation() {
        val full = SystemsMusicVolume.gain(15, 15, true, false)
        for (level in 1..4) {
            // At least 3 dB above the previous low-step gain, not just a tiny adjustment.
            assertTrue(SystemsMusicVolume.gain(level, 15, true, false) >= full * 1.414f)
        }
    }

    @Test fun ordinaryDevicesKeepAndroidVolumeControlWithoutDoubleAttenuation() {
        val gains = (1..15).map { SystemsMusicVolume.gain(it, 15, false, false) }
        gains.forEach { assertEquals(0.614f, it, 0.00001f) }
    }

    @Test fun invalidRangesCannotProduceInvalidGain() {
        assertEquals(0f, SystemsMusicVolume.gain(-1, 15, true, false), 0f)
        assertEquals(0.614f, SystemsMusicVolume.gain(30, 15, true, false), 0.00001f)
        assertEquals(0.614f, SystemsMusicVolume.gain(1, 0, true, false), 0.00001f)
    }
}
