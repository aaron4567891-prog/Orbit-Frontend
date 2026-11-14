package au.orbit.frontend

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.ProgressBar

/** Active scraper jobs and their progress, updated on the main thread. */
internal object ScrapeProgress {
    class Job internal constructor() {
        var completed = 0
            private set
        var total = 0
            private set

        fun update(completed: Int, total: Int) {
            if (this.completed == completed && this.total == total) return
            this.completed = completed
            this.total = total
            if (this in jobs) notifyListeners()
        }

        fun finish() {
            if (jobs.remove(this)) notifyListeners()
        }
    }

    private val jobs = mutableSetOf<Job>()
    private val listeners = mutableSetOf<() -> Unit>()

    fun start() = Job().also {
        jobs.add(it)
        notifyListeners()
    }

    fun current() = jobs.firstOrNull()
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun notifyListeners() = listeners.toList().forEach { it() }
}

internal class GameScrapeProgressBar(context: Context) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        progressTintList = ColorStateList.valueOf(Color.rgb(82, 169, 255))
        indeterminateTintList = progressTintList
        progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(65, 80, 100))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val update = { refresh() }
    var inGameView = false
        set(value) {
            field = value
            refresh()
        }

    init {
        visibility = GONE
        background = GradientDrawable().apply {
            setColor(Color.rgb(17, 25, 37))
            cornerRadius = dp(6).toFloat()
        }
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(bar, LayoutParams(dp(84), dp(8)))
    }

    private fun refresh() {
        val job = ScrapeProgress.current()
        visibility = if (inGameView && job != null) VISIBLE else GONE
        if (visibility != VISIBLE || job == null) return
        bar.isIndeterminate = job.total <= 0
        if (job.total > 0) {
            bar.max = job.total
            bar.setProgress(job.completed, true)
        }
        contentDescription = if (job.total > 0) "Scraping, ${job.completed} of ${job.total} complete" else "Scraping in progress"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ScrapeProgress.addListener(update)
        refresh()
    }

    override fun onDetachedFromWindow() {
        ScrapeProgress.removeListener(update)
        super.onDetachedFromWindow()
    }
}
