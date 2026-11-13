package au.orbit.frontend

import android.content.Context

/** Visibility preferences apply to the same emulator/core across every system. */
object InstalledEmulators {
    private fun key(profile:EmulatorProfile):String {
        val core=profile.stringExtras["LIBRETRO"]?.substringAfterLast('/').orEmpty()
        return ScraperClient.gameKey(profile.component.flattenToString()+"/"+core)
    }
    fun hidden(context:Context,profile:EmulatorProfile)=UserDataStore.string(context,"hiddenEmulators",key(profile))=="1"
    fun hide(context:Context,profile:EmulatorProfile,hidden:Boolean) {
        UserDataStore.put(context,"hiddenEmulators",key(profile),if(hidden) "1" else null)
    }
    fun available(context:Context,profiles:List<EmulatorProfile>,showHidden:Boolean=false)=
        profiles.filter { showHidden || !hidden(context,it) }
}
