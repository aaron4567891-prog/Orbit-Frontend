package au.orbit.frontend

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Android owns the confirmation and reports apps that cannot be removed. */
internal object AppActions {
    fun uninstall(activity: Activity, component: ComponentName): Boolean = runCatching {
        activity.startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", component.packageName, null)))
    }.onFailure {
        Toast.makeText(activity, "Unable to open uninstall: ${it.message}", Toast.LENGTH_LONG).show()
    }.isSuccess
}
