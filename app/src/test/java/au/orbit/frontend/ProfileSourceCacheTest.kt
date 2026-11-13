package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ProfileSourceCacheTest {
    @Test fun unchangedFilesReuseCacheAcrossInstancesAndChangesReload() {
        val folder=Files.createTempDirectory("orbit-profiles").toFile()
        try {
            var reads=0
            assertEquals("original",ProfileSourceCache(folder).read("root/psx","v1") { reads++;"original" })
            assertEquals("original",ProfileSourceCache(folder).read("root/psx","v1") { error("Must not reopen unchanged file") })
            assertEquals("edited",ProfileSourceCache(folder).read("root/psx","v2") { reads++;"edited" })
            assertEquals("other",ProfileSourceCache(folder).read("other/psx","v2") { reads++;"other" })
            assertEquals(3,reads)
        } finally { folder.deleteRecursively() }
    }
    @Test fun explicitReloadAndUnknownRevisionsAlwaysReadSource() {
        val folder=Files.createTempDirectory("orbit-profiles").toFile()
        try {
            val cache=ProfileSourceCache(folder)
            cache.read("psx","same") { "old" }
            assertEquals("new",cache.read("psx","same",true) { "new" })
            assertEquals("new",cache.read("psx","same") { error("Reload should update cache") })
            assertEquals("unknown",cache.read("psx",null) { "unknown" })
        } finally { folder.deleteRecursively() }
    }
}
