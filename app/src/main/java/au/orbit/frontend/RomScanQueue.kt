package au.orbit.frontend

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A small bounded queue overlaps storage queries without flooding the document provider. */
internal object RomScanQueue {
    fun <T,R> run(items:List<T>,cancelled:()->Boolean,scan:(T)->R,completed:(T,R)->Unit) {
        if(items.isEmpty())return
        val executor=Executors.newFixedThreadPool(minOf(2,items.size))
        val queue=ExecutorCompletionService<Pair<T,R>>(executor)
        var next=0;var finished=0
        fun check() { if(cancelled() || Thread.currentThread().isInterrupted)throw CancellationException("Scan cancelled") }
        fun submit() { check();val item=items[next++];queue.submit(java.util.concurrent.Callable { check();item to scan(item) }) }
        try {
            repeat(minOf(2,items.size)) { submit() }
            while(finished<items.size) {
                check()
                val future=queue.poll(100,TimeUnit.MILLISECONDS) ?: continue
                val (item,result)=try { future.get() } catch(error:java.util.concurrent.ExecutionException) { throw error.cause ?: error }
                check();completed(item,result);finished++
                if(next<items.size)submit()
            }
        } finally { executor.shutdownNow() }
    }
}
