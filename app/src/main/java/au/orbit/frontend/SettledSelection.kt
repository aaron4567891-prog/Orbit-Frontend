package au.orbit.frontend

/** Shares one preview selection while navigation continues through intermediate entries. */
internal class SettledSelection<T : Any>(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val moving: () -> Boolean,
    private val changed: (T) -> Unit
) {
    var current: T? = null
        private set
    private var pending: T? = null
    private val settle = object : Runnable {
        override fun run() {
            val value = pending ?: return
            if (moving()) { post(this, 16L); return }
            pending = null
            publish(value)
        }
    }

    fun select(value: T, wait: Boolean) {
        remove(settle)
        pending = null
        if (!wait || current == null) publish(value)
        else if (value != current) {
            pending = value
            post(settle, 180L)
        }
    }

    private fun publish(value: T) {
        if (current == value) return
        current = value
        changed(value)
    }

    fun clear() {
        remove(settle)
        pending = null
        current = null
    }
}
