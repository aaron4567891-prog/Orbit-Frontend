package au.orbit.frontend

/** The seven supported media categories, shared by every scraper. */
object ScraperMediaTypes {
    data class Category(val folder: String, val title: String, val types: List<String>)
    val categories = listOf(
        Category("physicalmedia", "Physical media", listOf("support-2D", "physicalmedia")),
        Category("marquees", "Marquees", listOf("wheel-hd", "wheel", "logo", "logos", "marquee")),
        Category("videos", "Videos", listOf("video-normalized", "video", "videos")),
        Category("fanart", "Fan art", listOf("fanart", "heroes")),
        Category("screenshots", "Screenshots", listOf("ss-hd", "ss", "screenshot", "screenshots")),
        Category("covers", "Box art", listOf("box-2D", "boxart", "cover", "grids")),
        Category("manuals", "Manuals", listOf("manuel", "manual", "manuals"))
    )
    private fun languageRank(media:ScraperClient.Media,preferredRegion:String,preferredLanguage:String):Int {
        val language=media.language.lowercase(java.util.Locale.ROOT)
        val region=media.region.lowercase(java.util.Locale.ROOT)
        val languagePenalty=when { language==preferredLanguage -> 0;language.isBlank() -> 1;language=="en" -> 20;else -> 40 }
        val regionPenalty=when { region==preferredRegion -> 0;region in listOf("wor","world") -> 1;region=="us" -> 2;region=="eu" -> 3;region.isBlank() || region=="ss" -> 4;else -> 10 }
        return languagePenalty*100+regionPenalty
    }
    fun select(media: List<ScraperClient.Media>, enabled: Set<String>, region:String="us", language:String="en"): List<Pair<Category,ScraperClient.Media>> =
        categories.filter { it.folder in enabled }.mapNotNull { category ->
            media.filter { it.type in category.types }.minWithOrNull(
                compareBy<ScraperClient.Media> { languageRank(it,region,language) }.thenBy { category.types.indexOf(it.type) }
            )?.let { category to it }
        }
}
