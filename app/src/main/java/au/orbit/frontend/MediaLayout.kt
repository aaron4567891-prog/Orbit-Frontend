package au.orbit.frontend

/** Human-readable filenames; retain ROM region/disc suffixes to avoid merging games. */
object MediaLayout {
    fun fileName(romName:String, extension:String):String {
        val stem=romName.substringBeforeLast('.',romName)
            .map { if(it.code<32 || it in "\\/:*?\"<>|") '_' else it }.joinToString("").trim().trim('.')
            .take(180).ifBlank { "game" }
        return "$stem.${extension.lowercase(java.util.Locale.ROOT)}"
    }

    internal fun gameMediaPath(group: String, path: String): List<String>? {
        if (path.isBlank() || !isRecordGroup(group)) return null
        val parts = (if (path.startsWith("@/")) path.removePrefix("@/") else "$group/$path").split('/')
        val owner = group.split('/')
        if (parts.size < 3 || parts[0] != owner[0] || parts.any { !validPart(it) }) return null
        if (path.startsWith("@/") && parts.size != 3) return null
        return listOf("media") + parts
    }

    fun isRecordGroup(group:String)=group.split('/').let { it.size==2 && it[1].matches(Regex("[a-f0-9]{64}")) }
    fun validPart(part:String)=part.isNotBlank() && part!="." && part!=".." && part.none { it=='/' || it=='\\' || it.code<32 }
}
