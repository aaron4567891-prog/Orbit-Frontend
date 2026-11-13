package au.orbit.frontend

import java.text.Normalizer
import java.util.Locale

/** Matches exported media to scanned ROMs without guessing across systems or duplicate names. */
object MediaImportPlan {
    private val personalSystems=mapOf("2600" to "atari2600","5200" to "atari5200","cv" to "colecovision",
        "dc" to "dreamcast","gg" to "gamegear","jag" to "atarijaguar","lynx" to "atarilynx",
        "mini" to "pokemini","sat" to "saturn","scd" to "segacd","sms" to "mastersystem",
        "vb" to "virtualboy","vita" to "psvita","segamodel3" to "model3","gbahacks" to "gba")
    data class Kind(val folder:String,val type:String,val extensions:Set<String>)
    private val pictures=setOf("png","jpg","jpeg","webp","gif","bmp")
    private fun image(folder:String,type:String)=Kind(folder,type,pictures)
    private val aliases=mapOf(
        "covers" to image("covers","box-2D"),"box2d" to image("covers","box-2D"),"boxart" to image("covers","box-2D"),
        "marquees" to image("marquees","marquee"),"wheels" to image("marquees","marquee"),"logos" to image("marquees","marquee"),
        "fanart" to image("fanart","fanart"),"fanarts" to image("fanart","fanart"),
        "screenshots" to image("screenshots","screenshot"),
        "physicalmedia" to image("physicalmedia","physicalmedia"),"discs" to image("physicalmedia","physicalmedia"),
        "videos" to Kind("videos","video",setOf("mp4","webm","mkv","avi")),
        "manuals" to Kind("manuals","manual",setOf("pdf")),
        "titlescreens" to image("titlescreens","titlescreen"),
        "miximages" to image("miximages","miximage"),
        "3dboxes" to image("3dboxes","box-3D"),"box3d" to image("3dboxes","box-3D"),
        "backcovers" to image("backcovers","backcover")
    )
    data class Source(val uri:String,val path:List<String>,val size:Long=-1,val canDelete:Boolean=false)
    data class Match(val source:Source,val system:SystemSpec,val game:RomRecord,val kind:Kind)
    data class Plan(val matches:List<Match>,val unmatched:Int,val ambiguous:Int,val unsupported:Int)
    fun normalized(value:String):String=Normalizer.normalize(value,Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    fun documentPathsOverlap(first:String,second:String):Boolean {
        val left=first.trimEnd('/');val right=second.trimEnd('/')
        fun inside(child:String,parent:String)=child==parent || child.startsWith(if(parent.endsWith(':'))parent else "$parent/")
        return inside(left,right) || inside(right,left)
    }
    private fun stem(value:String,extensions:Set<String>):String {
        val ext=value.substringAfterLast('.',"").lowercase(Locale.ROOT)
        return if(ext in extensions)value.substringBeforeLast('.') else value
    }
    fun create(sources:List<Source>,catalogue:List<SystemSpec>,games:Map<String,List<RomRecord>>,onlySystem:SystemSpec?=null):Plan {
        val matches=mutableListOf<Match>();var unmatched=0;var ambiguous=0;var unsupported=0
        val seen=mutableSetOf<String>()
        // Build each index once, rather than comparing every source file with every ROM.
        val indexes=catalogue.associate { spec ->
            val entries=linkedMapOf<String,MutableList<RomRecord>>()
            games[spec.id].orEmpty().distinctBy { it.uri }.forEach { game ->
                setOf(normalized(stem(game.fileName,spec.extensions+"m3u")),normalized(game.title)).filter { it.isNotEmpty() }.forEach {
                    entries.getOrPut(it) { mutableListOf() }.add(game)
                }
            }
            spec.id to entries
        }
        sources.forEach { source ->
            if(!seen.add(source.uri))return@forEach
            val parts=source.path
            val categoryIndex=parts.indexOfLast { it.lowercase(Locale.ROOT) in aliases }
            val kind=parts.getOrNull(categoryIndex)?.lowercase(Locale.ROOT)?.let(aliases::get)
            val ext=parts.lastOrNull()?.substringAfterLast('.',"")?.lowercase(Locale.ROOT)
            if(kind==null || ext !in kind.extensions || categoryIndex>=parts.lastIndex) { unsupported++;return@forEach }
            val system=parts.take(categoryIndex).asReversed().firstNotNullOfOrNull { name ->
                RomScanner.matchFolder(name,catalogue) ?: personalSystems[normalized(name)]?.let { id -> catalogue.firstOrNull { it.id==id } }
            }
            if(onlySystem!=null && system!=null && system.id!=onlySystem.id)return@forEach
            val selectedSystem=system ?: onlySystem
            if(selectedSystem==null) { unmatched++;return@forEach }
            val name=parts.last().substringBeforeLast('.')
            val key=normalized(stem(name,selectedSystem.extensions+"m3u"))
            val candidates=indexes[selectedSystem.id]?.get(key).orEmpty().distinctBy { it.uri }
            when(candidates.size) {
                0 -> unmatched++
                1 -> matches.add(Match(source,selectedSystem,candidates.single(),kind))
                else -> ambiguous++
            }
        }
        return Plan(matches,unmatched,ambiguous,unsupported)
    }
}
