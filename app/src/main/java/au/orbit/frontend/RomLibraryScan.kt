package au.orbit.frontend

import android.app.Activity
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class RomLibraryScan(
    private val activity: Activity,
    private val repository: LibraryRepository,
    private val worker: Executor,
    private val scanned: (SystemSpec, RomScanResult) -> Unit
) {
    private var cancellation: AtomicBoolean? = null
    val busy get() = cancellation != null
    var status = "Add one or more global ROM folders, then scan all games."
        private set
    var details = emptyList<String>()
        private set
    var onUpdate: (() -> Unit)? = null
    val cancelling get() = cancellation?.get() == true

    fun cancel() {
        if (cancellation?.compareAndSet(false, true) != true) return
        status = "Cancelling…"
        onUpdate?.invoke()
    }

    fun start() {
        if (busy) return
        val cancelled = AtomicBoolean(false)
        cancellation = cancelled
        details = emptyList()
        status = "Finding system folders…"
        onUpdate?.invoke()
        worker.execute {
            var count = 0
            val summaries = mutableListOf<String>()
            val result = runCatching {
                StartupScanner.scan(repository, { cancelled.get() }, { spec, report ->
                    count += report.games.size
                    summaries.add("${spec.title}: ${report.games.size} games")
                    activity.runOnUiThread { if (!activity.isDestroyed) scanned(spec, report) }
                }) { message, _, _ ->
                    activity.runOnUiThread {
                        if (!activity.isDestroyed && cancellation === cancelled && !cancelled.get()) {
                            status = message
                            onUpdate?.invoke()
                        }
                    }
                }
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                cancellation = null
                val failure = result.exceptionOrNull()
                val outcome = when {
                    cancelled.get() || failure is CancellationException -> "Scan cancelled"
                    failure != null -> "Scan stopped"
                    else -> "Scan complete"
                }
                status = "$outcome · ${summaries.size} systems · $count games"
                details = summaries + result.getOrDefault(emptyList()) +
                    listOfNotNull(failure?.takeUnless { it is CancellationException }?.message)
                onUpdate?.invoke()
            }
        }
    }
}
