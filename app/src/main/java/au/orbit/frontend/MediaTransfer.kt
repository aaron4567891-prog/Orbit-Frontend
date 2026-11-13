package au.orbit.frontend

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** The source is read-only until verified files and their immutable Orbit record exist. */
object MediaTransfer {
    @Volatile var revision=0L
        private set
    fun sha256(input:InputStream,cancelled:()->Boolean={false}):String {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(128*1024)
        while(true) {
            if(cancelled() || Thread.currentThread().isInterrupted)throw CancellationException()
            val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private data class Node(val uri:Uri,val name:String,val directory:Boolean,val size:Long,val canDelete:Boolean)
    private fun children(context:Context,tree:Uri,parent:Uri):List<Node> {
        val uri=Docs.buildChildDocumentsUriUsingTree(tree,Docs.getDocumentId(parent))
        val columns=arrayOf(Docs.Document.COLUMN_DOCUMENT_ID,Docs.Document.COLUMN_DISPLAY_NAME,Docs.Document.COLUMN_MIME_TYPE,Docs.Document.COLUMN_SIZE,Docs.Document.COLUMN_FLAGS)
        return (context.contentResolver.query(uri,columns,null,null,null) ?: error("Cannot read media folder")).use { cursor ->
            buildList { while(cursor.moveToNext())add(Node(Docs.buildDocumentUriUsingTree(tree,cursor.getString(0)),cursor.getString(1),
                cursor.getString(2)==Docs.Document.MIME_TYPE_DIR,if(cursor.isNull(3))-1 else cursor.getLong(3),cursor.getInt(4) and Docs.Document.FLAG_SUPPORTS_DELETE!=0)) }
        }
    }
    fun scan(context:Context,tree:Uri,source:String,cancelled:()->Boolean,progress:(String)->Unit,onlySystem:SystemSpec?=null):MediaImportPlan.Plan {
        require(!UserDataStore.overlapsUserData(context,tree)) { "Choose the source frontend's media folder, outside Orbit's user data folder." }
        var root=Docs.buildDocumentUriUsingTree(tree,Docs.getTreeDocumentId(tree))
        val desired=if(source=="es-de")"downloaded_media" else "media"
        val first=children(context,tree,root)
        first.singleOrNull { it.directory && it.name.equals(desired,true) }?.let { root=it.uri }
        val rootName=Docs.getDocumentId(root).substringAfterLast('/').substringAfterLast(':')
        val pending=java.util.ArrayDeque<Pair<Uri,List<String>>>().apply { add(root to listOf(rootName)) }
        val files=mutableListOf<MediaImportPlan.Source>();val seen=mutableSetOf<String>()
        while(pending.isNotEmpty()) {
            if(cancelled())throw CancellationException()
            val (folder,path)=pending.removeFirst()
            if(!seen.add(Docs.getDocumentId(folder)))continue
            require(path.size<=32 && seen.size<=20000) { "Media folder is too deeply nested or too large" }
            for(node in children(context,tree,folder)) {
                if(node.name.startsWith('.'))continue
                if(node.directory)pending.add(node.uri to (path+node.name))
                else {
                    require(files.size<100000) { "Select a smaller media folder (maximum 100,000 files per transfer)." }
                    files.add(MediaImportPlan.Source(node.uri.toString(),path+node.name,node.size,node.canDelete))
                }
            }
            progress("Checking media: ${files.size} files")
        }
        val library=LibraryRepository(context)
        val games=systems.associate { it.id to library.cached(it)?.games.orEmpty() }
        return MediaImportPlan.create(files,systems,games,onlySystem)
    }
    data class Report(var imported:Int=0,var moved:Int=0,var cancelled:Boolean=false,val errors:MutableList<String> = mutableListOf()) {
        fun message(move:Boolean):String = "$imported media files imported.${if(move) "\n$moved source files removed after verification." else "\nSource files were kept."}${if(cancelled) "\nTransfer cancelled; completed imports were kept." else ""}${if(errors.isEmpty()) "" else "\n${errors.size} issues:\n${errors.take(8).joinToString("\n")}"}"
    }
    private data class Saved(val match:MediaImportPlan.Match,val hash:String,val path:String)
    fun transfer(context:Context,plan:MediaImportPlan.Plan,source:String,move:Boolean,cancelled:()->Boolean,progress:(String)->Unit):Report {
        val report=Report();val destination=UserDataStore.location(context)
        fun checkActive() {
            if(cancelled() || Thread.currentThread().isInterrupted)throw CancellationException()
            check(UserDataStore.location(context)==destination) { "User data folder changed during transfer" }
        }
        fun temp()=File.createTempFile("orbit-media-import-",".tmp",context.cacheDir)
        var completed=0
        try {
            for((_,matches) in plan.matches.groupBy { it.system.id+"/"+it.game.uri }) {
                checkActive()
                val first=matches.first();val group=first.system.id+"/"+ScraperClient.gameKey(first.game.uri)
                val saved=mutableListOf<Saved>()
                for(match in matches) {
                    checkActive();progress("${++completed}/${plan.matches.size} · ${match.game.title}")
                    val stage=temp()
                    try {
                        context.contentResolver.openInputStream(Uri.parse(match.source.uri))?.use { input -> stage.outputStream().use { output ->
                            val buffer=ByteArray(128*1024)
                            while(true) { checkActive();val count=input.read(buffer);if(count<0)break;output.write(buffer,0,count) }
                        } } ?: error("Cannot read source media")
                        val hash=stage.inputStream().use { sha256(it,cancelled) }
                        val ext=match.source.path.last().substringAfterLast('.').lowercase(java.util.Locale.ROOT)
                        val name=MediaLayout.fileName(match.game.fileName,ext)
                        val folder=UserDataStore.mediaTypeFolder(context,match.system.id,match.kind.folder,match.source.path)
                        UserDataStore.importMediaFile(context,"${match.system.id}/$folder",name,stage,hash)
                        saved.add(Saved(match,hash,"@/${match.system.id}/$folder/$name"))
                    } catch(error:CancellationException) { throw error }
                    catch(error:Exception) { report.errors.add("${match.source.path.last()}: ${error.message}") }
                    finally { stage.delete() }
                }
                if(saved.isEmpty())continue
                checkActive()
                val record=JSONObject().put("provider",source).put("title",first.game.title).put("romUri",first.game.uri)
                    .put("metadata",JSONObject()).put("files",JSONArray(saved.map { JSONObject().put("type",it.match.kind.type).put("file",it.path) }))
                    .put("importedAt",System.currentTimeMillis())
                val stage=temp()
                try {
                    stage.writeText(record.toString(2))
                    val hash=stage.inputStream().use { sha256(it,cancelled) }
                    UserDataStore.importMediaFile(context,group,"import-${System.currentTimeMillis()}-$source-${java.util.UUID.randomUUID()}.json",stage,hash)
                } catch(error:CancellationException) { throw error }
                catch(error:Exception) { report.errors.add("${first.game.title}: media record not saved; source files kept. ${error.message}");continue }
                finally { stage.delete() }
                report.imported+=saved.size;revision++
                if(move)for(file in saved) {
                    checkActive()
                    try {
                        check(file.match.source.canDelete) { "Source does not allow deletion; copy kept" }
                        val uri=Uri.parse(file.match.source.uri)
                        val sourceHash=context.contentResolver.openInputStream(uri)?.use { sha256(it,cancelled) }
                        check(sourceHash==file.hash) { "Source changed; original kept" }
                        check(Docs.deleteDocument(context.contentResolver,uri)) { "Source could not be removed; copy kept" }
                        report.moved++
                    } catch(error:CancellationException) { throw error }
                    catch(error:Exception) { report.errors.add("${file.match.source.path.last()}: ${error.message}") }
                }
            }
        } catch(error:CancellationException) { report.cancelled=true }
        return report
    }
}
