package au.orbit.frontend

/** File identities passed to emulator shortcut launchers must survive URI encoding and subfolders. */
object RomLaunchUris {
    fun fileUri(path:String):String {
        require(path.startsWith('/')) { "ROM path must be absolute" }
        return java.net.URI("file","",path,null,null).toASCIIString()
    }
    fun relativeDocumentPath(document:String,folders:List<String>,fallback:String):String {
        val folder=folders.filter { document.startsWith(it.trimEnd('/')+"/") }.maxByOrNull { it.length } ?: return fallback
        return document.removePrefix(folder.trimEnd('/')+"/").takeIf { it.isNotBlank() } ?: fallback
    }
}
