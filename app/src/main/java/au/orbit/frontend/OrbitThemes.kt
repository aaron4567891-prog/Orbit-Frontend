package au.orbit.frontend

import android.content.Context
import org.json.JSONObject

/** Orbit's own theme format. Rendering and navigation stay native on both displays. */
object OrbitThemes {
    internal const val SPLIT_THEME="orbit-split"
    private val retiredThemes=setOf("orbit","mono")
    data class Artwork(val role:String,val screen:String,val file:String)
    /** Theme-wide images; the page backgrounds override "background" behind one bottom-grid page. */
    val artworkRoles=setOf("background","hero","systemsBackground","gamesBackground")
    private fun validSystemId(id:String)=id.matches(Regex("[a-z0-9][a-z0-9_-]{0,79}"))
    fun validArtworkPath(path:String)=path.startsWith("artwork/") && path.removePrefix("artwork/").let {
        it.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,159}\\.(?:png|jpg|jpeg|webp)")) && ".." !in it
    }
    data class Theme(val id:String,val name:String,val description:String,val background:Int,val surface:Int,
        val accent:Int,val text:Int,val muted:Int,val systemLayout:String,val listFraction:Float,val artwork:List<Artwork> = emptyList(),
        val systemArtworkScreens:Set<String> = setOf("top","bottom"),
        val systemHeroes:Map<String,Map<String,String>> = emptyMap(),
        val systemArtworkOverrides:Map<String,Set<String>> = emptyMap(),
        val systemImages:Map<String,Map<String,String>> = emptyMap(),
        val libraryScreen:String = "top",
        val systemLogos:Map<String,String> = emptyMap(),
        /** Per system: "hero/top", "hero/bottom" or "logo" entries the user removed, including bundled defaults. */
        val removedSystemArtwork:Map<String,Set<String>> = emptyMap()) {
        fun json()=JSONObject().put("format","orbit-native").put("version",1).put("id",id).put("name",name)
            .put("description",description).put("systemLayout",systemLayout).put("gameListFraction",listFraction).put("libraryScreen",libraryScreen)
            .put("colors",JSONObject().put("background",hex(background)).put("surface",hex(surface))
                .put("accent",hex(accent)).put("text",hex(text)).put("muted",hex(muted)))
            .put("artwork",org.json.JSONArray(artwork.map { JSONObject().put("role",it.role).put("screen",it.screen).put("file",it.file) }))
            .put("systemArtworkScreens",org.json.JSONArray(systemArtworkScreens.sorted()))
            .put("systemHeroes",JSONObject().apply { systemHeroes.toSortedMap().forEach { (system,images) -> put(system,JSONObject(images)) } })
            .put("systemImages",JSONObject().apply { systemImages.toSortedMap().forEach { (system,images) -> put(system,JSONObject(images)) } })
            .put("systemLogos",JSONObject(systemLogos.toSortedMap()))
            .put("removedSystemArtwork",JSONObject().apply { removedSystemArtwork.toSortedMap().forEach { (system,entries) -> put(system,org.json.JSONArray(entries.sorted())) } })
            .put("systemArtworkOverrides",JSONObject().apply { systemArtworkOverrides.toSortedMap().forEach { (system,screens) -> put(system,org.json.JSONArray(screens.sorted())) } }).toString(2)
        fun image(role:String,screen:String)=artwork.firstOrNull { it.role==role && it.screen==screen }?.file
        fun heroImage(screen:String,systemId:String?=null)=if(systemId!=null && heroRemoved(systemId,screen))null
            else systemId?.let { systemHeroes[it]?.get(screen) } ?: image("hero",screen)
        fun heroRemoved(systemId:String,screen:String)="hero/$screen" in removedSystemArtwork[systemId].orEmpty()
        fun logoRemoved(systemId:String)="logo" in removedSystemArtwork[systemId].orEmpty()
        fun withSystemArtworkRemoved(systemId:String,entry:String,removed:Boolean):Theme {
            require(validSystemId(systemId) && entry in removableArtwork) { "Invalid artwork removal" }
            val entries=removedSystemArtwork[systemId].orEmpty().let { if(removed)it+entry else it-entry }
            return copy(removedSystemArtwork=if(entries.isEmpty())removedSystemArtwork-systemId else removedSystemArtwork+(systemId to entries))
        }
        fun showSystemArtwork(systemId:String,screen:String)=screen in (systemArtworkOverrides[systemId] ?: systemArtworkScreens)
        private fun updatedSystemImages(assignments:Map<String,Map<String,String>>,systemId:String,screens:Set<String>,file:String?):Map<String,Map<String,String>> {
            require(validSystemId(systemId)) { "Invalid system ID" }
            require(screens.isNotEmpty() && screens.all { it in setOf("top","bottom") }) { "Invalid artwork screen" }
            require(file==null || validArtworkPath(file)) { "Invalid artwork path" }
            val images=assignments[systemId].orEmpty().toMutableMap()
            screens.forEach { if(file==null)images.remove(it) else images[it]=file }
            val next=assignments.toMutableMap()
            if(images.isEmpty())next.remove(systemId) else next[systemId]=images.toMap()
            return next.toMap()
        }
        fun withSystemHero(systemId:String,screens:Set<String>,file:String?)=
            copy(systemHeroes=updatedSystemImages(systemHeroes,systemId,screens,file)).let { updated ->
                // Choosing a new image brings that slot back.
                if(file==null)updated else screens.fold(updated) { next,screen -> next.withSystemArtworkRemoved(systemId,"hero/$screen",false) }
            }
        fun withSystemImage(systemId:String,screens:Set<String>,file:String?)=
            copy(systemImages=updatedSystemImages(systemImages,systemId,screens,file))
        fun withSystemLogo(systemId:String,file:String?):Theme {
            require(validSystemId(systemId)) { "Invalid system ID" }
            require(file==null || validArtworkPath(file)) { "Invalid artwork path" }
            val updated=copy(systemLogos=if(file==null)systemLogos-systemId else systemLogos+(systemId to file))
            return if(file==null)updated else updated.withSystemArtworkRemoved(systemId,"logo",false)
        }
        fun withSystemArtworkVisible(systemId:String,screen:String,visible:Boolean):Theme {
            require(screen in setOf("top","bottom")) { "Invalid artwork screen" }
            val screens=(systemArtworkOverrides[systemId] ?: systemArtworkScreens).toMutableSet()
            if(visible)screens.add(screen) else screens.remove(screen)
            return withSystemArtworkScreens(systemId,screens)
        }
        fun withSystemArtworkScreens(systemId:String,screens:Set<String>?):Theme {
            require(validSystemId(systemId)) { "Invalid system ID" }
            require(screens==null || screens.all { it in setOf("top","bottom") }) { "Invalid artwork screen" }
            val next=systemArtworkOverrides.toMutableMap()
            if(screens==null)next.remove(systemId) else next[systemId]=screens.toSet()
            return copy(systemArtworkOverrides=next.toMap())
        }
        fun withArtwork(role:String,screens:Set<String>,file:String?):Theme {
            require(role in artworkRoles && screens.isNotEmpty() && screens.all { it in setOf("top","bottom") })
            require(file==null || validArtworkPath(file)) { "Invalid artwork path" }
            return copy(artwork=artwork.filterNot { it.role==role && it.screen in screens } + if(file==null)emptyList() else screens.sorted().map { Artwork(role,it,file) })
        }
    }
    internal val removableArtwork=setOf("hero/top","hero/bottom","logo")
    private fun hex(color:Int)="#"+Integer.toHexString(color).takeLast(6).padStart(6,'0')
    private val fallback=Theme("midnight","Orbit Midnight","Deep blue, original console illustrations and a spacious game media view. Designed for Orbit's two screens.",
        0xff081a32.toInt(),0xff102c4b.toInt(),0xff1679da.toInt(),0xfff4f8ff.toInt(),0xff9bb6d3.toInt(),"artwork",0.44f)
    @Volatile private var themes=listOf(fallback)
    fun list()=themes
    fun current()=themes.firstOrNull { it.id==UserDataStore.cachedString("settings","nativeTheme") }
        ?: themes.firstOrNull { it.id=="midnight" } ?: fallback
    fun parse(source:String):Theme {
        val json=JSONObject(source)
        require(json.getString("format")=="orbit-native" && json.getInt("version")==1) { "Unsupported Orbit theme format" }
        val id=json.getString("id");require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,79}"))) { "Invalid theme ID" }
        val name=json.getString("name").trim();require(name.isNotEmpty() && name.length<=60) { "Theme name must contain 1–60 characters" }
        val colors=json.getJSONObject("colors")
        fun color(key:String):Int {
            val value=colors.getString(key).removePrefix("#")
            require(value.matches(Regex("[a-fA-F0-9]{6}"))) { "Invalid $key color" }
            return (0xff000000L or value.toLong(16)).toInt()
        }
        val layout=json.getString("systemLayout");require(layout in setOf("cards","artwork")) { "Unknown system layout" }
        val libraryScreen=if(json.has("libraryScreen"))json.getString("libraryScreen") else "top"
        require(libraryScreen in setOf("top","bottom")) { "Invalid library screen" }
        val fraction=json.getDouble("gameListFraction");require(fraction in 0.3..0.7) { "Game list width must be between 0.3 and 0.7" }
        val art=json.optJSONArray("artwork")
        require(art==null || art.length()<=artworkRoles.size*2) { "Too many artwork assignments" }
        val artwork=(0 until (art?.length() ?: 0)).map { index ->
            val item=art!!.getJSONObject(index)
            val role=item.getString("role");val screen=item.getString("screen");val file=item.getString("file")
            require(role in artworkRoles && screen in setOf("top","bottom") && validArtworkPath(file)) { "Invalid artwork assignment" }
            Artwork(role,screen,file)
        }
        require(artwork.distinctBy { it.role to it.screen }.size==artwork.size) { "Duplicate artwork assignment" }
        val visible=json.optJSONArray("systemArtworkScreens")?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() } ?: setOf("top","bottom")
        require(visible.all { it in setOf("top","bottom") }) { "Invalid system artwork screen" }
        fun systemImages(key:String):Map<String,Map<String,String>> {
            val entries=if(json.has(key))json.getJSONObject(key) else JSONObject()
            require(entries.length()<=512) { "Too many system image assignments" }
            return entries.keys().asSequence().associate { system ->
                require(validSystemId(system)) { "Invalid system ID" }
                val images=entries.getJSONObject(system)
                require(images.length() in 1..2) { "Invalid system image assignments" }
                system to images.keys().asSequence().associate { screen ->
                    val path=images.getString(screen)
                    require(screen in setOf("top","bottom") && validArtworkPath(path)) { "Invalid system image assignment" }
                    screen to path
                }
            }
        }
        val heroes=systemImages("systemHeroes")
        val images=systemImages("systemImages")
        val logosObject=if(json.has("systemLogos"))json.getJSONObject("systemLogos") else JSONObject()
        require(logosObject.length()<=512) { "Too many system logo assignments" }
        val logos=logosObject.keys().asSequence().associate { system ->
            val path=logosObject.getString(system)
            require(validSystemId(system) && validArtworkPath(path)) { "Invalid system logo assignment" }
            system to path
        }
        val overridesObject=if(json.has("systemArtworkOverrides"))json.getJSONObject("systemArtworkOverrides") else JSONObject()
        require(overridesObject.length()<=512) { "Too many system artwork assignments" }
        val overrides=overridesObject.keys().asSequence().associate { system ->
            require(validSystemId(system)) { "Invalid system ID" }
            val screens=overridesObject.getJSONArray(system)
            require(screens.length()<=2) { "Invalid system artwork screens" }
            val values=(0 until screens.length()).map { screens.getString(it) }
            require(values.all { it in setOf("top","bottom") } && values.distinct().size==values.size) { "Invalid system artwork screen" }
            system to values.toSet()
        }
        val removedObject=if(json.has("removedSystemArtwork"))json.getJSONObject("removedSystemArtwork") else JSONObject()
        require(removedObject.length()<=512) { "Too many removed system artwork entries" }
        val removed=removedObject.keys().asSequence().associate { system ->
            val entries=removedObject.getJSONArray(system)
            val values=(0 until entries.length()).map { entries.getString(it) }.toSet()
            require(validSystemId(system) && values.isNotEmpty() && values.all { it in removableArtwork }) { "Invalid removed system artwork" }
            system to values
        }
        return Theme(id,name,json.optString("description"),color("background"),color("surface"),color("accent"),color("text"),color("muted"),layout,fraction.toFloat(),artwork,visible,heroes,overrides,images,libraryScreen,logos,removed)
    }
    private const val ICONIC_RELEASE_KEY="systemBackgrounds.iconicReleased"
    /** Iconic pack heroes saved before SteamGridDB defaults were bundled; user-chosen art carries no pack label. */
    internal fun iconicPackSystems(themeId:String,sources:Map<String,String>):Set<String> =
        sources.filter { (key,label) -> key.startsWith("$themeId/") && label.startsWith("Iconic · ") }
            .keys.map { it.removePrefix("$themeId/") }.toSet()
    /** One-time: drop pack-applied Iconic heroes so the bundled SteamGridDB backgrounds show. */
    private fun releaseIconicPackHeroes(context:Context,loaded:MutableMap<String,Theme>) {
        if(UserDataStore.string(context,"settings",ICONIC_RELEASE_KEY)!=null)return
        val sources=UserDataStore.section(context,"systemArtworkSources")
        for((id,theme) in loaded.toMap()) {
            val systems=iconicPackSystems(id,sources).filter { it in theme.systemHeroes }
            if(systems.isEmpty())continue
            val released=systems.fold(theme) { next,system -> next.withSystemHero(system,setOf("top","bottom"),null) }
            UserDataStore.saveNativeTheme(context,id,released.json())
            loaded[id]=released
            systems.forEach { UserDataStore.put(context,"systemArtworkSources","$id/$it",null) }
        }
        UserDataStore.put(context,"settings",ICONIC_RELEASE_KEY,"1")
    }
    fun refresh(context:Context) {
        val loaded=linkedMapOf(fallback.id to fallback)
        context.assets.list("themes/native").orEmpty().filter { it.endsWith(".json") }.sorted().forEach { name ->
            context.assets.open("themes/native/$name").bufferedReader().use { parse(it.readText()) }.let { loaded[it.id]=it }
        }
        val saved=UserDataStore.nativeThemeSources(context).mapNotNull { source -> runCatching { parse(source) }.getOrNull() }
        saved.filterNot { it.id in retiredThemes }.forEach { theme ->
            val preset=loaded[theme.id]
            loaded[theme.id]=if(theme.id==SPLIT_THEME && preset!=null)theme.copy(name=preset.name) else theme
        }
        runCatching { releaseIconicPackHeroes(context,loaded) }
        val migration=if(DualScreenCoordinator.hasLegacyBottomLayout(context) && saved.none { it.id==SPLIT_THEME })runCatching {
            val activeId=UserDataStore.cachedString("settings","nativeTheme")
            val active=saved.firstOrNull { it.id==activeId } ?: loaded[activeId] ?: loaded.getValue("midnight")
            val split=loaded.getValue(SPLIT_THEME)
            val migrated=split.copy(artwork=active.artwork,systemArtworkScreens=active.systemArtworkScreens,
                systemHeroes=active.systemHeroes,systemArtworkOverrides=active.systemArtworkOverrides,systemImages=active.systemImages,
                systemLogos=active.systemLogos)
            UserDataStore.saveNativeTheme(context,migrated.id,migrated.json())
            loaded[migrated.id]=migrated
        } else null
        loaded.values.filter { preset -> saved.none { it.id==preset.id } && (preset.id!=SPLIT_THEME || migration==null) }.forEach { preset ->
            runCatching { UserDataStore.saveNativeTheme(context,preset.id,preset.json()) }
        }
        themes=loaded.values.toList()
        if(migration?.isFailure!=true)DualScreenCoordinator.cacheThemeLayout(context,current().libraryScreen)
        OrbitThemeArtwork.prepare(context,current())
    }
    fun apply(context:Context,theme:Theme) {
        OrbitThemeArtwork.prepare(context,theme)
        UserDataStore.put(context,"settings","nativeTheme",theme.id)
        DualScreenCoordinator.cacheThemeLayout(context,theme.libraryScreen)
        OrbitThemeArtwork.prepare(context,theme)
    }
    internal fun isBuiltIn(id:String)=id in retiredThemes || id=="midnight" || id==SPLIT_THEME
    fun canDelete(theme:Theme)=!isBuiltIn(theme.id)
    fun delete(context:Context,theme:Theme) {
        require(canDelete(theme)) { "Built-in Orbit themes cannot be deleted" }
        UserDataStore.deleteNativeTheme(context,theme.id)
        refresh(context)
    }
    fun create(context:Context,name:String,base:Theme):Theme {
        val theme=base.copy(id="custom-${java.util.UUID.randomUUID()}",name=name.trim(),description="Created in Orbit · Based on ${base.name}")
        parse(theme.json())
        UserDataStore.saveNativeTheme(context,theme.id,theme.json())
        refresh(context)
        return theme
    }
}
