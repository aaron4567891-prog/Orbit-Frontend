package au.orbit.frontend

import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UserDataStoreSnapshotsTest {
    @Test fun settingsReadsDoNotWaitForStorageMonitor() {
        val state=UserDataStore::class.java.getDeclaredField("state").apply { isAccessible=true }
        val previous=state.get(UserDataStore)
        val pool=Executors.newFixedThreadPool(2)
        val locked=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            state.set(UserDataStore,JSONObject("""{"settings":{"hideNavigationBar":1,"theme":"canvas"},"library":{"folders":["one","two"]}}"""))
            pool.submit { synchronized(UserDataStore) { locked.countDown();release.await() } }
            check(locked.await(1,TimeUnit.SECONDS))
            val read=pool.submit<List<Any?>> {
                val context=ContextWrapper(null)
                listOf(UserDataStore.cachedInt("hideNavigationBar",0),UserDataStore.int(context,"hideNavigationBar",0),
                    UserDataStore.string(context,"settings","theme"),UserDataStore.strings(context,"library","folders"))
            }
            assertEquals(listOf(1,1,"canvas",listOf("one","two")),read.get(1,TimeUnit.SECONDS))
        } finally { release.countDown();pool.shutdownNow();pool.awaitTermination(2,TimeUnit.SECONDS);state.set(UserDataStore,previous) }
    }
    @Test fun focusReadsUseDefaultBeforeStorageIsReady() {
        val state=UserDataStore::class.java.getDeclaredField("state").apply { isAccessible=true }
        val previous=state.get(UserDataStore)
        try {
            state.set(UserDataStore,null)
            assertEquals(0,UserDataStore.cachedInt("hideNavigationBar",0))
            assertEquals(5,AppDock.slots(ContextWrapper(null)))
            assertEquals(true,AppDock.enabled(ContextWrapper(null)))
            assertEquals(null,UserDataStore.cachedString("settings","appDockSlot0"))
        }
        finally { state.set(UserDataStore,previous) }
    }
}
