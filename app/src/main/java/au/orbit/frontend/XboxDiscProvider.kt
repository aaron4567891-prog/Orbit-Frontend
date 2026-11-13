package au.orbit.frontend

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** A stable disc URI avoids the Xbox emulators' cross-process dvdUri preference race. */
class XboxDiscProvider:ContentProvider() {
    override fun onCreate()=true
    private fun slot(uri:Uri)=uri.pathSegments.takeIf { it.size==2 && it[1]=="disc.iso" && it[0] in setOf("hakux","x1box") }
        ?.first() ?: throw FileNotFoundException("Unknown Xbox disc")
    private fun source(uri:Uri):Uri {
        val value=context!!.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(slot(uri),null)
            ?: throw FileNotFoundException("No Xbox disc selected")
        return Uri.parse(value)
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor? {
        if(mode!="r")throw FileNotFoundException("Xbox discs are read-only")
        val selected=source(uri)
        android.util.Log.i("OrbitXboxDisc","Opening ${context!!.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(slot(uri)+".name","")}")
        val identity=android.os.Binder.clearCallingIdentity()
        return try { context!!.contentResolver.openFileDescriptor(selected,"r")
            ?: throw FileNotFoundException("Selected Xbox disc is unavailable")
        } finally { android.os.Binder.restoreCallingIdentity(identity) }
    }
    override fun getType(uri:Uri):String { slot(uri);return "application/octet-stream" }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val columns=projection ?: arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        val prefs=context!!.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val name=prefs.getString(slot(uri)+".name","disc.iso")
        val identity=android.os.Binder.clearCallingIdentity()
        val size=try { runCatching { context!!.contentResolver.openAssetFileDescriptor(source(uri),"r")?.use { it.length.takeIf { n -> n>=0 } } }.getOrNull()
        } finally { android.os.Binder.restoreCallingIdentity(identity) }
        return MatrixCursor(columns).apply { addRow(columns.map { when(it) { OpenableColumns.DISPLAY_NAME -> name;OpenableColumns.SIZE -> size;else -> null } }) }
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    companion object {
        private const val PREFS="xbox_disc_launch"
        fun prepare(context:Context,game:Game,packageName:String):Game {
            val slot=when(packageName) { "com.rfandango.haku_x" -> "hakux";"com.izzy2lost.x1box" -> "x1box";else -> return game }
            val authority=context.packageName+".xbox-disc"
            require(game.uri.authority!=authority) { "Invalid source disc" }
            check(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString(slot,game.uri.toString()).putString("$slot.name",game.fileName).commit()) { "Could not save selected Xbox disc" }
            return game.copy(uri=Uri.Builder().scheme("content").authority(authority).appendPath(slot).appendPath("disc.iso").build())
        }
    }
}
