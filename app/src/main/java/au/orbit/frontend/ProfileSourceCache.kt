package au.orbit.frontend

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Local copies are reused only when the storage provider reports the same file revision. */
internal class ProfileSourceCache(private val directory:File) {
    fun read(key:String,revision:String?,force:Boolean=false,source:()->String):String {
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val file=File(directory,"$hash.json")
        if(revision!=null && !force)runCatching {
            val cached=JSONObject(file.readText())
            if(cached.getString("revision")==revision)return cached.getString("source")
        }
        val text=source()
        if(revision!=null)runCatching {
            directory.mkdirs()
            val temporary=File.createTempFile("profile-",".tmp",directory)
            try {
                temporary.writeText(JSONObject().put("revision",revision).put("source",text).toString())
                Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
        return text
    }
}
