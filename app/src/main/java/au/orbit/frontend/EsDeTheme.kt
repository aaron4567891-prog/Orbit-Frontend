package au.orbit.frontend

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import android.util.TypedValue
import android.widget.TextView
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** ES-DE adapter for Colorful's selectable layouts, colour schemes and fonts at 16:9.
 * Theme adaptation CC-BY-NC-SA 2.0; original theme by Anthony Caccese, viking and faeran.
 * This deliberately does not claim support for arbitrary ES-DE themes or all element types.
 */
object EsDeTheme {
    private val variablePattern=Regex("\\$\\{([^}]+)\\}")
    const val base = "themes/colorful/"
    fun enabled(context: Context) = false
    val variants=listOf(
        "gamelist-list-cover" to "Box art", "gamelist-list-screenshot" to "Screenshot",
        "gamelist-list-screenshot-marquee" to "Screenshot + Marquee", "gamelist-list-titlescreen" to "Title screen",
        "gamelist-list-physicalmedia" to "Physical media", "gamelist-list-miximage" to "Miximage",
        "gamelist-list-metadata-screenshot-cover" to "Metadata + Screenshot + Box art",
        "gamelist-list-metadata-screenshot-marquee" to "Metadata + Screenshot + Marquee",
        "gamelist-list-metadata-screenshot-physicalmedia" to "Metadata + Screenshot + Physical media"
    )
    val schemes=listOf("colorful" to "Colorful","colorful-dark" to "Colorful Dark","oled" to "OLED","dark" to "Dark","light" to "Light","retro-bright" to "Retro Bright","dmg" to "DMG")
    fun variantIndex(context:Context)=UserDataStore.int(context,"colorfulVariant",0).coerceIn(variants.indices)
    fun schemeIndex(context:Context)=UserDataStore.int(context,"colorfulScheme",0).coerceIn(schemes.indices)
    data class Model(val variables: Map<String,String>, val views: Map<String,Map<String,Map<String,String>>>, val variant:String="gamelist-list-cover") {
        fun value(input: String): String {
            var result=input
            repeat(12) { result=variablePattern.replace(result) { variables[it.groupValues[1]] ?: "" } }
            return result
        }
        fun property(view:String,name:String,key:String,fallback:String="") = value(views[view]?.get(name)?.get(key) ?: fallback)
        fun color(view:String,name:String,key:String,fallback:String="ffffff") = parseColor(property(view,name,key,fallback))
        fun variable(key:String,fallback:String="")=value(variables[key] ?: fallback).ifBlank { fallback }
    }
    private val cache=mutableMapOf<String,Model>()
    @Synchronized fun model(context: Context, id:String):Model {
        val variant=variants[variantIndex(context)].first;val scheme=schemes[schemeIndex(context)].first
        val font=if(UserDataStore.int(context,"colorfulFontSize",1)==0) "medium" else "large"
        return cache.getOrPut("$id/$variant/$scheme/$font") {
            parse(id,variant,scheme,font) { path -> runCatching { context.assets.open(base+path).bufferedReader().use { it.readText() } }.getOrNull() }
        }
    }
    fun parse(id:String, variant:String="gamelist-list-cover", scheme:String="colorful", font:String="large", read:(String)->String?):Model {
        val variables=mutableMapOf("system.theme" to if (id == "3ds") "n3ds" else id)
        val views=mutableMapOf<String,MutableMap<String,MutableMap<String,String>>>()
        val conditions=mapOf("variant" to variant,"colorScheme" to scheme,"fontSize" to font,"aspectRatio" to "16:9","language" to "en_US")
        // Android rejects some desktop parser flags when constructing the builder.
        // Reject declarations before parsing, and prevent all external resolution explicitly.
        val factory=DocumentBuilderFactory.newInstance()
        fun elements(element:Element):List<Element> = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
        fun expand(text:String)=Model(variables,emptyMap()).value(text)
        fun names(element:Element)=element.getAttribute("name").split(',').map(String::trim).filter(String::isNotBlank)
        fun load(path:String,depth:Int) {
            if(depth>16)return
            val clean=path.removePrefix("./")
            if(clean.split('/').any { it==".." } || clean.startsWith('/'))return
            val xml=read(clean) ?: return
            if(xml.contains("<!DOCTYPE",true) || xml.contains("<!ENTITY",true))return
            val root=runCatching {
                val builder=factory.newDocumentBuilder()
                builder.setEntityResolver { _,_ -> org.xml.sax.InputSource(java.io.StringReader("")) }
                builder.parse(xml.byteInputStream()).documentElement
            }.getOrNull() ?: return
            fun walk(element:Element) {
                for(child in elements(element)) {
                    val choice=conditions[child.tagName]
                    if(choice!=null && choice !in names(child))continue
                    when(child.tagName) {
                        "include" -> load(expand(child.textContent.trim()),depth+1)
                        "variables" -> elements(child).forEach { variables[it.tagName]=it.textContent.trim() }
                        "view" -> names(child).forEach { view ->
                            val target=views.getOrPut(view){ mutableMapOf() }
                            elements(child).forEach { item -> names(item).forEach { name ->
                                val properties=target.getOrPut(name){ mutableMapOf() }
                                elements(item).forEach { properties[it.tagName]=it.textContent.trim() }
                            } }
                        }
                        else -> walk(child)
                    }
                }
            }
            walk(root)
        }
        load("theme.xml",0)
        return Model(variables,views,variant)
    }
    fun parseColor(hex:String):Int {
        val clean=hex.removePrefix("#")
        return runCatching {
            if(clean.length==8) Color.argb(clean.substring(6,8).toInt(16),clean.substring(0,2).toInt(16),clean.substring(2,4).toInt(16),clean.substring(4,6).toInt(16))
            else Color.parseColor("#$clean")
        }.getOrDefault(Color.DKGRAY)
    }
    private val fonts=mutableMapOf<String,Typeface>()
    fun font(context:Context,path:String)=fonts.getOrPut(path) {
        runCatching { Typeface.createFromAsset(context.assets,base+path.removePrefix("./")) }.getOrDefault(Typeface.DEFAULT)
    }
}

/** ES-DE uses normalized positions/sizes; keep those coordinates as the Android view resizes. */
class ThemeFrame(context:Context):FrameLayout(context) {
    private data class Placement(val view:View,val x:Float,val y:Float,val w:Float,val h:Float,val font:Float?)
    private val placements=mutableListOf<Placement>()
    fun place(view:View,model:EsDeTheme.Model,scope:String,name:String,fallback:String="0 0 1 1",font:Float?=null) {
        val defaults=fallback.split(' ').map(String::toFloat)
        fun pair(key:String,a:Float,b:Float)=model.property(scope,name,key).trim().split(Regex("\\s+")).mapNotNull(String::toFloatOrNull).takeIf { it.size==2 } ?: listOf(a,b)
        val pos=pair("pos",defaults[0],defaults[1]);val size=pair("size",defaults[2],defaults[3])
        val origin=pair("origin",0f,0f)
        placements.add(Placement(view,pos[0]-size[0]*origin[0],pos[1]-size[1]*origin[1],size[0],size[1],font))
        addView(view)
    }
    fun clear() { placements.clear();removeAllViews() }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val w=MeasureSpec.getSize(widthMeasureSpec);val h=MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w,h)
        placements.forEach {
            if(it.view is TextView && it.font!=null)it.view.setTextSize(TypedValue.COMPLEX_UNIT_PX,h*it.font)
            it.view.measure(MeasureSpec.makeMeasureSpec((w*it.w).toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((h*it.h).toInt(),MeasureSpec.EXACTLY))
        }
    }
    override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int) {
        placements.forEach { p -> val x=(width*p.x).toInt();val y=(height*p.y).toInt();p.view.layout(x,y,x+p.view.measuredWidth,y+p.view.measuredHeight) }
    }
}
