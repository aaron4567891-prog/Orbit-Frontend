package au.orbit.frontend

/** Human-readable filenames; retain ROM region/disc suffixes to avoid merging games. */
object MediaLayout {
    fun fileName(romName:String, extension:String):String {
        val stem=romName.substringBeforeLast('.',romName)
            .map { if(it.code<32 || it in "\\/:*?\"<>|") '_' else it }.joinToString("").trim().trim('.')
            .take(180).ifBlank { "game" }
        return "$stem.${extension.lowercase(java.util.Locale.ROOT)}"
    }
    fun isRecordGroup(group:String)=group.split('/').let { it.size==2 && it[1].matches(Regex("[a-f0-9]{64}")) }
    fun validPart(part:String)=part.isNotBlank() && part!="." && part!=".." && part.none { it=='/' || it=='\\' || it.code<32 }
}
