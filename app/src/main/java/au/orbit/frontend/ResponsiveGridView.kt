package au.orbit.frontend

import android.content.Context
import android.os.Build
import android.widget.GridView

// Keep the tuned layout on the handheld that shipped with Orbit's original design.
internal val usesThorLayout = Build.MODEL.replace('_', ' ').equals("AYN Thor", ignoreCase = true)

/** Keeps tiles readable in a narrow pane and exposes the measured columns to D-pad navigation. */
internal class ResponsiveGridView(context: Context, private val minimumTileWidthDp: Int) : GridView(context) {
    init { numColumns = if (usesThorLayout) 3 else 1 }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!usesThorLayout) {
            val tileWidth = (minimumTileWidthDp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            numColumns = ((available + horizontalSpacing) / (tileWidth + horizontalSpacing)).coerceIn(1, 3)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
