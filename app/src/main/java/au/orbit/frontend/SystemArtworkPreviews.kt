package au.orbit.frontend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Small, resident system previews survive full-size cache eviction; disk copies speed cold starts. */
internal object SystemArtworkPreviews {
    internal const val MEMORY_BYTES = 24 * 1024 * 1024
    private const val LOGO_MEMORY_BYTES = 8 * 1024 * 1024
    private const val DISK_BYTES = 64L * 1024 * 1024
    data class Source(val key: String, val trim: Boolean = false, val trimMargin: Float = 0.015f,
        val wordmark: Boolean = false, val horizontal: ((Int) -> Bitmap?)? = null, val read: () -> ByteArray?)
    @Volatile private var images = emptyMap<String, Bitmap>()
    @Volatile private var startupLogos = emptyMap<String, Bitmap>()
    fun cached(key: String): Bitmap? = images[key] ?: startupLogos[key]
    fun consoleKey(system: SystemSpec) = "console/${system.id}"
    /** Bundled art ships inside the APK, so its disk previews are tied to the installed build. */
    @Volatile private var bundleStamp: Long? = null
    private fun bundleStamp(context: Context): Long = bundleStamp ?: runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L).also { bundleStamp = it }
    internal fun isBundled(key: String) = key.startsWith("bundled-") || key.startsWith("console/")
    internal fun diskKey(key: String, stamp: Long) = if (isBundled(key)) "$key@$stamp" else key

    internal fun pixelLimit(count: Int) = minOf(640 * 400, MEMORY_BYTES / 4 / count.coerceAtLeast(1)).coerceAtLeast(1)
    internal fun sampleSize(width: Int, height: Int, pixels: Int): Int {
        var sample = 1
        fun side(size: Int) = (size.toLong() + sample - 1) / sample
        while (sample < (1 shl 30) &&
            (side(width) * side(height) > pixels.coerceAtLeast(1) || side(width) > 640 || side(height) > 640)) sample *= 2
        return sample
    }
    internal fun artworkPaths(theme: OrbitThemes.Theme, systemIds: Set<String>): List<String> = buildList {
        for (id in systemIds) {
            addAll(theme.systemHeroes[id].orEmpty().values)
            addAll(theme.systemImages[id].orEmpty().values)
            theme.systemLogos[id]?.let(::add)
        }
    }.distinct()

    /** Small local logo files only; no original artwork reads, cache writes or bulk warming. */
    fun restoreLogos(context: Context, sources: List<Source>) {
        val unique = sources.distinctBy { it.key }
        val limit = minOf(pixelLimit(unique.size), LOGO_MEMORY_BYTES / 4 / unique.size.coerceAtLeast(1)).coerceAtLeast(1)
        val folder = File(context.cacheDir, "system-artwork-previews-v1")
        val stamp = bundleStamp(context)
        val restored = linkedMapOf<String, Bitmap>()
        var used = 0L
        val began = android.os.SystemClock.elapsedRealtime()
        for (source in unique) {
            val bitmap = cached(source.key) ?: runCatching { load(folder,source,limit,stamp,writeCache=false) }.getOrNull()
            if (bitmap != null && used + bitmap.allocationByteCount <= LOGO_MEMORY_BYTES) {
                restored[source.key] = bitmap
                used += bitmap.allocationByteCount
            }
        }
        startupLogos = restored
        android.util.Log.i("OrbitArtwork", "Restored ${restored.size}/${unique.size} cached logos in ${android.os.SystemClock.elapsedRealtime() - began} ms")
    }

    @Synchronized fun prepare(context: Context, sources: List<Source>, cancelled: () -> Boolean = { false }) {
        val unique = sources.distinctBy { it.key }
        val previous = images
        val limit = pixelLimit(unique.size)
        val folder = File(context.cacheDir, "system-artwork-previews-v1").apply { mkdirs() }
        val stamp = bundleStamp(context)
        val prepared = linkedMapOf<String, Bitmap>()
        var used = 0L
        for (source in unique) {
            if (cancelled()) return
            val bitmap = runCatching {
                previous[source.key]?.takeIf { it.allocationByteCount <= limit * 4 }
                    ?: load(folder, source, limit, stamp)
            }.getOrNull()
            if (bitmap != null && used + bitmap.allocationByteCount <= MEMORY_BYTES) {
                prepared[source.key] = bitmap
                used += bitmap.allocationByteCount
                // Make each preview usable immediately, without waiting for the whole library.
                images = prepared.toMap()
            }
        }
        if (cancelled()) return
        images = prepared
        // Cache files are disposable derivatives, never the user's original artwork.
        val files = folder.listFiles().orEmpty().filter { it.extension == "png" }.sortedBy { it.lastModified() }
        var diskBytes = files.sumOf { it.length() }
        for (file in files) {
            if (diskBytes <= DISK_BYTES) break
            val size = file.length()
            if (file.delete()) diskBytes -= size
        }
    }

    private fun load(folder: File, source: Source, pixels: Int, stamp: Long, writeCache: Boolean = true): Bitmap? {
        fun prepared(bitmap: Bitmap) = when {
            source.wordmark -> SystemLogoWordmark.prepare(bitmap) { source.horizontal?.invoke(pixels) }
            source.trim -> SystemTileArtwork.trim(bitmap, source.trimMargin)
            else -> bitmap
        }
        val digits = "0123456789abcdef"
        val hash = buildString(64) {
            MessageDigest.getInstance("SHA-256").digest(diskKey(source.key, stamp).toByteArray()).forEach { byte ->
                val value = byte.toInt() and 255
                append(digits[value ushr 4]); append(digits[value and 15])
            }
        }
        val file = File(folder, "$hash.png")
        if (file.isFile) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, pixels)
                })?.let {
                    file.setLastModified(System.currentTimeMillis())
                    return prepared(it)
                }
            }
        }
        val bytes = source.read() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, pixels)
        }) ?: return null
        val bitmap = prepared(decoded)
        // User artwork paths are immutable UUIDs; bundled previews are keyed to the installed build.
        if (writeCache) runCatching {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                atomic.finishWrite(stream)
            } catch (error: Throwable) { atomic.failWrite(stream); throw error }
        }
        return bitmap
    }
}
