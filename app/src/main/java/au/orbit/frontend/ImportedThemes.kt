package au.orbit.frontend

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Imports ordinary theme data into portable user data and a local rendering cache. */
object ImportedThemes {
    data class Entry(val id:String,val root:File,val capabilities:EsDeXml.Capabilities,val previews:List<String>,val notes:List<String>) {
        fun file(path:String):File? {
            if(EsDeXml.resolve("theme.xml",path)!=path)return null
            val result=File(root,path)
            return result.takeIf { it.canonicalPath.startsWith(root.canonicalPath+File.separator) && it.isFile }
        }
    }
    @Volatile private var installed=emptyList<Entry>()
    fun list()=installed
    // Legacy imports remain on disk, but native Orbit themes own the live interface.
    fun active(context:Context):Entry?=null
    private val models=java.util.concurrent.ConcurrentHashMap<String,EsDeXml.Model>()
    fun options(context:Context,entry:Entry):Map<String,String> {
        fun choice(key:String,choices:List<EsDeXml.Choice>,fallback:String)=UserDataStore.string(context,"themeOptions","${entry.id}.$key")?.takeIf { saved -> choices.any { it.id==saved } }
            ?: choices.firstOrNull { it.id==fallback }?.id ?: choices.firstOrNull()?.id ?: fallback
        return mapOf("variant" to choice("variant",entry.capabilities.variants,""),"colorScheme" to choice("colorScheme",entry.capabilities.schemes,""),
            "fontSize" to choice("fontSize",entry.capabilities.fonts,"medium"),"aspectRatio" to choice("aspectRatio",entry.capabilities.ratios,"16:9"),"language" to "en_US")
    }
    fun model(context:Context,entry:Entry,system:String):EsDeXml.Model {
        val options=options(context,entry)
        return models.getOrPut("${entry.id}/$system/$options") { EsDeXml.parse(system,options) { entry.file(it)?.readText() } }
    }
    fun refresh(context:Context) {
        installed=UserDataStore.themeIndexes(context).mapNotNull { (id,json) -> runCatching {
            val index=JSONObject(json)
            val root=File(context.filesDir,"theme-cache/$id").apply { mkdirs() }
            val paths=(0 until index.getJSONArray("files").length()).map { index.getJSONArray("files").getString(it) }
            require(paths.size<=12000)
            UserDataStore.restoreTheme(context,id,paths,root)
            entry(id,root,index)
        }.getOrNull() }
        models.clear()
    }
    private fun entry(id:String,root:File,index:JSONObject):Entry {
        val caps=EsDeXml.capabilities(File(root,"capabilities.xml").takeIf { it.isFile }?.readText(),index.optString("name","Imported theme"))
        fun strings(key:String)=index.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
        return Entry(id,root,caps,strings("previews"),strings("notes"))
    }
    fun import(context:Context,uri:Uri,progress:(String)->Unit={}):Entry {
        val id="theme-${UUID.randomUUID()}"
        val stage=File(context.filesDir,"theme-cache/$id").apply { check(mkdirs()) }
        var count=0;var total=0L
        fun copy(parentId:String,target:File,depth:Int) {
            require(depth<=24) { "Theme folder nesting is too deep" }
            val children=Docs.buildChildDocumentsUriUsingTree(uri,parentId)
            context.contentResolver.query(children,arrayOf(Docs.Document.COLUMN_DOCUMENT_ID,Docs.Document.COLUMN_DISPLAY_NAME,Docs.Document.COLUMN_MIME_TYPE),null,null,null)?.use { cursor ->
                while(cursor.moveToNext()) {
                    if(Thread.currentThread().isInterrupted)throw InterruptedException()
                    val doc=cursor.getString(0);val name=cursor.getString(1)
                    if(name in listOf(".git",".svn",".DS_Store"))continue
                    require(name.isNotBlank() && name !in listOf(".","..") && name.none { it in "/\\:\u0000" }) { "Invalid theme file name" }
                    val file=File(target,name)
                    if(cursor.getString(2)==Docs.Document.MIME_TYPE_DIR) { check(file.mkdir());copy(doc,file,depth+1) }
                    else {
                        require(++count<=12000) { "Theme contains too many files" }
                        if(count==1 || count%20==0)progress("Copying theme: $count files · ${total/1024/1024} MB")
                        val source=Docs.buildDocumentUriUsingTree(uri,doc)
                        context.contentResolver.openInputStream(source)?.use { input -> file.outputStream().use { output ->
                            val buffer=ByteArray(32768);var size=0L
                            while(true) { val length=input.read(buffer);if(length<0)break
                                size+=length;total+=length
                                require(size<=64L*1024*1024 && total<=512L*1024*1024) { "Theme exceeds the import size limit" }
                                output.write(buffer,0,length)
                            }
                        } } ?: error("Cannot read $name")
                    }
                }
            } ?: error("Cannot read selected theme folder")
        }
        try {
            copy(Docs.getTreeDocumentId(uri),stage,0)
            return finishImport(context,id,stage,progress)
        } catch(error:Exception) {
            if(stage.canonicalPath.startsWith(File(context.filesDir,"theme-cache").canonicalPath+File.separator))stage.deleteRecursively()
            throw error
        }
    }
    fun importFolder(context:Context,source:File,progress:(String)->Unit):Entry {
        val id="theme-${UUID.randomUUID()}"
        val stage=File(context.filesDir,"theme-cache/$id")
        try {
            stage.parentFile!!.mkdirs()
            check(source.renameTo(stage)) { "Cannot move downloaded theme into local storage" }
            return finishImport(context,id,stage,progress)
        } catch(error:Exception) { stage.deleteRecursively();throw error }
    }
    private fun finishImport(context:Context,id:String,stage:File,progress:(String)->Unit):Entry {
        val count=stage.walkTopDown().count { it.isFile }
        try {
            progress("Checking theme XML…")
            require(File(stage,"theme.xml").isFile || File(stage,"capabilities.xml").isFile) { "Choose the theme’s own folder containing theme.xml and capabilities.xml" }
            val paths=stage.walkTopDown().filter { it.isFile }.map { it.relativeTo(stage).invariantSeparatorsPath }.toList()
            val caps=EsDeXml.capabilities(File(stage,"capabilities.xml").takeIf { it.isFile }?.readText(),"Imported theme")
            val choices=mapOf("variant" to caps.variants.firstOrNull()?.id.orEmpty(),"colorScheme" to caps.schemes.firstOrNull()?.id.orEmpty(),"fontSize" to (caps.fonts.firstOrNull()?.id ?: "medium"),"aspectRatio" to (caps.ratios.firstOrNull()?.id ?: "16:9"),"language" to "en_US")
            val sampleSystem=if(File(stage,"theme.xml").isFile) "psx" else paths.firstOrNull { it.endsWith("/theme.xml") }?.substringBefore('/') ?: "psx"
            val parsed=EsDeXml.parse(sampleSystem,choices) { path -> if(EsDeXml.resolve("theme.xml",path)==path)File(stage,path).takeIf { it.isFile }?.readText() else null }
            require(parsed.views.keys.any { it in setOf("system","gamelist","basic","detailed","video") }) { "No supported system or gamelist layout found" }
            val previews=paths.filter { it.substringAfterLast('.').lowercase() in setOf("png","jpg","jpeg","webp") && it.lowercase().let { n -> "screenshot" in n || "preview" in n } }.take(5)
            val index=JSONObject().put("name",caps.name).put("files",JSONArray(paths)).put("previews",JSONArray(previews)).put("notes",JSONArray(parsed.warnings))
            File(stage,"orbit-theme.json").writeText(index.toString(2))
            progress("Saving $count theme files to user data…")
            UserDataStore.saveTheme(context,id,stage) { done,totalFiles ->
                if(done==1 || done%20==0 || done==totalFiles)progress("Saving to user data: $done / $totalFiles files")
            }
            val result=entry(id,stage,index)
            installed=installed+result
            return result
        } catch(error:Exception) {
            // Only this freshly created UUID staging directory is removed after a failed import.
            if(stage.canonicalPath.startsWith(File(context.filesDir,"theme-cache").canonicalPath+File.separator))stage.deleteRecursively()
            throw error
        }
    }
}
