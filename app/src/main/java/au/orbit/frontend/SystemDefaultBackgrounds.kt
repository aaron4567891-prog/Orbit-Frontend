package au.orbit.frontend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Local defaults never require a scrape or a network connection at launch. */
internal object SystemDefaultBackgrounds {
    internal fun assetId(systemId: String) = if (systemId == "3ds") "n3ds" else systemId
    fun key(systemId: String) = "bundled-system-hero-v1/${assetId(systemId)}"
    fun supports(systemId: String) = systems.any { it.id == systemId }
    fun bytes(context: Context, systemId: String): ByteArray? {
        val id = assetId(systemId)
        if (!id.matches(Regex("[a-z0-9_-]+"))) return null
        return runCatching { context.assets.open("system-backgrounds/$id.webp").use { it.readBytes() } }.getOrNull()
    }
    @Volatile private var focusSpans: Map<String, FloatArray>? = null
    /**
     * From focus.json: [logoStart, logoEnd] or [logoStart, logoEnd, artStart, artEnd], as fractions of a
     * bundled hero's width. The logo stays in view; the crop leans towards the wider art span.
     */
    fun focus(context: Context, assetId: String): FloatArray? {
        val spans = focusSpans ?: runCatching {
            val json = org.json.JSONObject(context.assets.open("system-backgrounds/focus.json").bufferedReader().use { it.readText() })
            json.keys().asSequence().associateWith { key ->
                json.getJSONArray(key).let { array -> FloatArray(array.length()) { array.getDouble(it).toFloat() } }
            }.filterValues(::validFocus)
        }.getOrDefault(emptyMap()).also { focusSpans = it }
        return spans[assetId]
    }
    internal fun validFocus(span: FloatArray) = (span.size == 2 || span.size == 4) &&
        span.all { it in 0f..1f } && span[1] >= span[0] && (span.size == 2 || span[3] >= span[2] && span[2] <= span[0] && span[3] >= span[1])
    fun load(context: Context, systemId: String): Bitmap? {
        val bytes = bytes(context,systemId) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1200) sample *= 2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
