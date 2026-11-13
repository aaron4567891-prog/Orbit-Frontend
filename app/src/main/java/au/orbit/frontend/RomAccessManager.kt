package au.orbit.frontend

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** Shares the selected collection tree as well as its ROM, so sibling saves/patches can be opened. */
object RomAccessManager {
    fun persistFolderAccess(context: Context, result: Intent, uri: Uri) {
        val modes = result.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(modes and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "Folder did not grant read access" }
        if (modes and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } else context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun prepare(context: Context, game: Game, intent: Intent) {
        val clip = ClipData.newRawUri("ROM", game.uri)
        var modes = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if(game.uri.authority==context.packageName+".xbox-disc")modes=modes or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        if (game.uri.scheme == "content" && DocumentsContract.isTreeUri(game.uri)) {
            val authority = game.uri.authority ?: error("ROM URI has no provider")
            val tree = DocumentsContract.buildTreeDocumentUri(authority, DocumentsContract.getTreeDocumentId(game.uri))
            // Older Orbit versions persisted only READ even when the picker had already offered WRITE.
            // This succeeds only for rights the user previously granted; it cannot manufacture access.
            runCatching {
                context.contentResolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == tree }
            if (grant?.isReadPermission == true) {
                clip.addItem(ClipData.Item(tree))
                modes = modes or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                // Launchers such as hakuX persist the disc URI before opening their emulator process.
                modes = modes or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                if (grant.isWritePermission) modes = modes or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
        }
        intent.clipData = clip
        intent.addFlags(modes)
    }
}
