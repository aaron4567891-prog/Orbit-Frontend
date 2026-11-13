package au.orbit.frontend

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock

/** Labels and icons are prepared off the UI thread and reused across tab visits. */
object InstalledApps {
    data class Entry(val info: ResolveInfo, val label: String, val icon: Drawable)
    @Volatile var cached: List<Entry>? = null
        private set
    private var loadedAt = 0L
    @Synchronized fun load(context: Context): List<Entry> {
        cached?.takeIf { SystemClock.elapsedRealtime() - loadedAt < 15_000 }?.let { return it }
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.exported && it.activityInfo.enabled }
            .distinctBy { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .map {
                val source = it.loadIcon(pm)
                // Adaptive icons can compose large layers on every UI-thread draw.
                // Flatten them once at the actual grid size on this loader thread.
                val size = (64 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                source.setBounds(0, 0, size, size)
                source.draw(Canvas(bitmap))
                bitmap.prepareToDraw()
                Entry(it, it.loadLabel(pm).toString(), BitmapDrawable(context.resources, bitmap))
            }
            .sortedBy { it.label.lowercase() }.also { cached = it; loadedAt = SystemClock.elapsedRealtime() }
    }
}
