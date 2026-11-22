package au.orbit.frontend

import org.junit.Assert.assertEquals
import org.junit.Test

class EmulatorGroupsTest {
    @Test fun retroArchBuildsUseShortNames() {
        assertEquals("RetroArch 64", EmulatorGroups.shortBuildLabel("RetroArch (64 bits)"))
        assertEquals("RetroArch 32", EmulatorGroups.shortBuildLabel("RetroArch (32 bits)"))
        assertEquals("RetroArch 64", EmulatorGroups.shortBuildLabel("RetroArch 64"))
        assertEquals("RetroArch", EmulatorGroups.shortBuildLabel("RetroArch"))
    }
}
