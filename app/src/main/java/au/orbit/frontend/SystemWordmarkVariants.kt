package au.orbit.frontend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Bundled system-name logos, also used when custom artwork needs a horizontal alternative. */
internal object SystemWordmarkVariants {
    private val missing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun key(systemId: String) = "bundled-system-logo/$systemId"
    fun bytes(context: Context, systemId: String): ByteArray? {
        if (systemId in missing || !systemId.matches(Regex("[a-z0-9_-]+"))) return null
        val assetId = SystemDefaultBackgrounds.assetId(systemId)
        return runCatching { context.assets.open("system-wordmarks/$assetId.png").use { it.readBytes() } }.getOrNull()
            ?: run { missing.add(systemId); return null }
    }
    fun load(context: Context, systemId: String, pixels: Int = 640 * 400): Bitmap? {
        val bytes = bytes(context, systemId) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = SystemArtworkPreviews.sampleSize(bounds.outWidth, bounds.outHeight, pixels)
        }) ?: return null
        return SystemLogoWordmark.prepare(bitmap)
    }
}
