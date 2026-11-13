package au.orbit.frontend

import java.util.concurrent.CancellationException

/** Refreshes collections with two bounded storage workers. Failed folders remain retryable. */
object StartupScanner {
    fun scan(repository: LibraryRepository, cancelled: () -> Boolean, completed: (SystemSpec, RomScanResult) -> Unit = { _, _ -> }, progress: (String, Int, Int) -> Unit): List<String> {
        val errors = mutableListOf<String>()
        fun check() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException() }
        repository.collectionRoots().forEach { uri ->
            check()
            for(attempt in 0..1) {
                try {
                    val (found, _) = repository.discover(uri, cancelled) { path -> progress("Finding system folders: $path", 0, 0) }
                    found.forEach { (system, folders) -> check(); repository.addFolders(system, folders) }
                    break
                } catch(e:CancellationException) { throw e }
                catch(e:Exception) {
                    if(attempt==0)Thread.sleep(250)
                    else { android.util.Log.w("OrbitStartup","ROM folder unavailable: ${e.message}",e);errors.add("ROM folder unavailable: ${e.message}") }
                }
            }
        }
        val targets = systems.filter { repository.folders(it).isNotEmpty() }
        val totalGames=java.util.concurrent.atomic.AtomicInteger()
        val done=java.util.concurrent.atomic.AtomicInteger()
        RomScanQueue.run(targets,cancelled,{ system ->
            check()
            progress("${system.title} · ${totalGames.get()} games found", done.get(), targets.size)
            runCatching {
                var result:RomScanResult?=null
                var failure:Exception?=null
                for(attempt in 0..1) {
                    check()
                    try {
                        result = repository.scanReport(system, cancelled) { count ->
                            progress("${system.title} · ${totalGames.get() + count} games found", done.get(), targets.size)
                        }
                        failure=null
                        break
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) {
                        failure=e
                        if(attempt==0)Thread.sleep(250)
                    }
                }
                failure?.let { throw it }
                result ?: error("No scan result")
            }.onFailure { if(it is CancellationException)throw it }
        },{ system,outcome ->
            outcome.onSuccess { report -> totalGames.addAndGet(report.games.size);completed(system,report);errors.addAll(report.errors) }
                .onFailure { error -> android.util.Log.w("OrbitStartup","${system.title}: ${error.message}",error);errors.add("${system.title}: ${error.message}") }
            val count=done.incrementAndGet()
            progress("$count/${targets.size} systems · ${totalGames.get()} games",count,targets.size)
        })
        return errors
    }
}
