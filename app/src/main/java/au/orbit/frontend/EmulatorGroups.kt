package au.orbit.frontend

/**
 * Emulator lists show each RetroArch build (RetroArch, RetroArch 64 bits, …) once, with its cores
 * in a drop-down underneath; every other emulator is a single entry.
 */
internal object EmulatorGroups {
    sealed class Entry {
        data class Single(val profile: EmulatorProfile) : Entry()
        data class Build(val key: String, val label: String, val cores: List<EmulatorProfile>) : Entry()
    }

    fun isRetroArch(profile: EmulatorProfile) = "LIBRETRO" in profile.stringExtras
    /** "RetroArch (64 bits) - picodrive" → "RetroArch (64 bits)". */
    fun buildLabel(profile: EmulatorProfile) = profile.name.substringBefore(" - ").trim()
    /** "RetroArch (64 bits) - picodrive" → "picodrive". */
    fun coreLabel(profile: EmulatorProfile) = profile.name.substringAfter(" - ", profile.name).trim()

    /**
     * Groups RetroArch cores under their build (the installed app), keeping the list's order of first
     * appearance. Profiles name the same build differently ("RetroArch 64", "RetroArch (64 bits)"), so the
     * section takes the name most of its cores use.
     */
    fun group(profiles: List<EmulatorProfile>): List<Entry> {
        val builds = linkedMapOf<String, MutableList<EmulatorProfile>>()
        val order = mutableListOf<Any>()
        for (profile in profiles) {
            if (!isRetroArch(profile)) { order += profile; continue }
            val key = profile.component.packageName ?: buildLabel(profile)
            builds.getOrPut(key) { order += key; mutableListOf() }.add(profile)
        }
        return order.map { item ->
            if (item is EmulatorProfile) Entry.Single(item)
            else builds.getValue(item as String).let { cores -> Entry.Build(item, commonLabel(cores), cores) }
        }
    }

    /** "RetroArch (64 bits)" and "RetroArch 64" both read "RetroArch 64". */
    internal fun shortBuildLabel(label: String) = label.replace(Regex("""\s*\((\d+)\s*bits?\)""", RegexOption.IGNORE_CASE), " $1").trim()

    internal fun commonLabel(cores: List<EmulatorProfile>) = cores.groupingBy { shortBuildLabel(buildLabel(it)) }.eachCount()
        .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
        .first().key

    /** One row of an expanded list: a profile to choose, or a build to open or close. */
    sealed class Row {
        data class Choose(val profile: EmulatorProfile, val core: Boolean) : Row()
        data class Toggle(val build: Entry.Build) : Row()
    }

    fun rows(entries: List<Entry>, expanded: Set<String>): List<Row> = entries.flatMap { entry ->
        when (entry) {
            is Entry.Single -> listOf(Row.Choose(entry.profile, core = false))
            is Entry.Build -> listOf(Row.Toggle(entry)) +
                if (entry.key in expanded) entry.cores.map { Row.Choose(it, core = true) } else emptyList()
        }
    }
}
