package au.orbit.frontend

/** One account budget shared by searches, game metadata and artwork downloads. */
object ScreenScraperRequests {
    private val monitor=java.lang.Object()
    private var active=0
    private var limit=1
    fun configure(allowed:Int)=synchronized(monitor) { limit=allowed.coerceAtLeast(1);monitor.notifyAll() }
    fun acquire()=synchronized(monitor) {
        while(active>=limit)monitor.wait()
        active++
    }
    fun release()=synchronized(monitor) { active--;monitor.notifyAll() }
}
