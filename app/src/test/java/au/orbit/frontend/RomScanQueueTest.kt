package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RomScanQueueTest {
    @Test fun overlapsTwoScansAndDeliversEveryResult() {
        val firstPair=CountDownLatch(2)
        val active=AtomicInteger();val maximum=AtomicInteger()
        val results=mutableMapOf<Int,Int>()
        RomScanQueue.run((1..6).toList(),{ false },{ item ->
            val running=active.incrementAndGet();maximum.updateAndGet { maxOf(it,running) }
            try { if(item<=2) { firstPair.countDown();check(firstPair.await(2,TimeUnit.SECONDS)) };item*10 }
            finally { active.decrementAndGet() }
        },{ item,result -> results[item]=result })
        assertEquals(2,maximum.get())
        assertEquals((1..6).associateWith { it*10 },results)
    }
    @Test fun cancellationStopsPendingSystems() {
        var cancelled=false;var delivered=0
        try {
            RomScanQueue.run((1..20).toList(),{ cancelled },{ it },{ _,_ -> delivered++;cancelled=true })
            fail("Expected cancellation")
        } catch(_:java.util.concurrent.CancellationException) { assertEquals(1,delivered) }
    }
}
