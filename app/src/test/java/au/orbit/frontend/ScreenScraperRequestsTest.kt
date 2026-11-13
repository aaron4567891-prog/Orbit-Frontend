package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScreenScraperRequestsTest {
    @Test fun allSixAccountSlotsCanRunSimultaneously() {
        ScreenScraperRequests.configure(6)
        val entered=CountDownLatch(6);val release=CountDownLatch(1)
        val executor=java.util.concurrent.Executors.newFixedThreadPool(6)
        val tasks=(0 until 6).map { executor.submit {
            ScreenScraperRequests.acquire()
            try { entered.countDown();release.await(3,TimeUnit.SECONDS) }
            finally { ScreenScraperRequests.release() }
        } }
        try { assertTrue(entered.await(2,TimeUnit.SECONDS)) }
        finally { release.countDown();tasks.forEach { it.get(3,TimeUnit.SECONDS) };executor.shutdownNow() }
    }

    @Test fun singleSlotBlocksThenReleasesWaitingRequest() {
        ScreenScraperRequests.configure(1)
        ScreenScraperRequests.acquire()
        val started=CountDownLatch(1);val entered=CountDownLatch(1)
        val thread=Thread {
            started.countDown();ScreenScraperRequests.acquire()
            try { entered.countDown() } finally { ScreenScraperRequests.release() }
        }
        thread.start()
        try {
            assertTrue(started.await(2,TimeUnit.SECONDS))
            assertFalse(entered.await(100,TimeUnit.MILLISECONDS))
        } finally { ScreenScraperRequests.release() }
        assertTrue(entered.await(2,TimeUnit.SECONDS));thread.join(2000)
    }
    @Test fun waitingRequestCanBeCancelledWithoutTakingSlot() {
        ScreenScraperRequests.configure(1);ScreenScraperRequests.acquire()
        val cancelled=CountDownLatch(1)
        val thread=Thread {
            try { ScreenScraperRequests.acquire();ScreenScraperRequests.release() }
            catch(e:InterruptedException) { cancelled.countDown() }
        }
        thread.start();thread.interrupt()
        try { assertTrue(cancelled.await(2,TimeUnit.SECONDS)) }
        finally { ScreenScraperRequests.release();thread.join(2000) }
    }
}
