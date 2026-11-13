package au.orbit.frontend

import android.content.Context

object ScraperCredentials {
    val hasDeveloperAccount: Boolean get() = BuildConfig.SCREENSCRAPER_DEV_ID.isNotBlank() && BuildConfig.SCREENSCRAPER_DEV_PASSWORD.isNotBlank()
    fun value(context: Context, provider: String, key: String): String {
        if (provider == "ScreenScraper" && hasDeveloperAccount) {
            when (key) {
                "developerId" -> return BuildConfig.SCREENSCRAPER_DEV_ID
                "developerPassword" -> return BuildConfig.SCREENSCRAPER_DEV_PASSWORD
            }
        }
        return context.getSharedPreferences("scraper_credentials", 0).getString("$provider.$key", "").orEmpty()
    }
}
