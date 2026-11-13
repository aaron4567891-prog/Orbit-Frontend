package au.orbit.frontend

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrapePolicyTest {
    @Test fun manualAmountStaysWithinAccountAllowanceAndMaximumIsDefault() {
        assertEquals(6,ScrapePolicy.parallelism(6,0))
        assertEquals(12,ScrapePolicy.parallelism(12,0))
        assertEquals(3,ScrapePolicy.parallelism(6,3))
        assertEquals(2,ScrapePolicy.parallelism(2,6))
    }
    @Test fun noSystemTicksMeansAllAndAnySubsetIsAllowed() {
        val catalogue=(1..20).map { SystemSpec("system$it","System $it","S$it","",setOf("rom")) }
        assertEquals(20,ScrapePolicy.systemsForSelection(catalogue,emptySet()).size)
        assertEquals(20,ScrapePolicy.systemsForSelection(catalogue,catalogue.map { it.id }.toSet()).size)
        assertEquals(15,ScrapePolicy.systemsForSelection(catalogue,catalogue.take(15).map { it.id }.toSet()).size)
        assertEquals("system4",ScrapePolicy.systemsForSelection(catalogue,setOf("system4")).single().id)
    }

    @Test fun concurrencyMatchesAccountWithoutThreeGameCap() {
        assertEquals(1,ScrapePolicy.parallelism(1))
        assertEquals(2,ScrapePolicy.parallelism(2))
        assertEquals(6,ScrapePolicy.parallelism(6))
        assertEquals(12,ScrapePolicy.parallelism(12))
        assertEquals(1,ScrapePolicy.parallelism(0))
    }
}
