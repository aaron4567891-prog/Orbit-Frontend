package au.orbit.frontend

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** Independent reader for modern ES-DE theme XML. No ES-DE engine code is used. */
object EsDeXml {
    data class Choice(val id:String,val label:String)
    data class Capabilities(val name:String,val author:String,val variants:List<Choice>,val schemes:List<Choice>,val fonts:List<Choice>,val ratios:List<Choice>)
    data class Item(val type:String,val name:String,val props:Map<String,String>)
    data class Model(val views:Map<String,List<Item>>,val warnings:List<String>)
    private val variable=Regex("\\$\\{([^}]+)\\}")
    fun document(xml:String):Element {
        require(xml.length<=2*1024*1024) { "Theme XML is too large" }
        require(!xml.contains("<!DOCTYPE",true) && !xml.contains("<!ENTITY",true)) { "Theme XML entities are not supported" }
        val builder=DocumentBuilderFactory.newInstance().newDocumentBuilder()
        builder.setEntityResolver { _,_ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        return builder.parse(xml.byteInputStream()).documentElement
    }
    private fun children(e:Element)=(0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }
    private fun tokens(value:String)=value.split(Regex("[,\\s]+" )).filter(String::isNotBlank)
    fun resolve(from:String,path:String):String? {
        if(path.isBlank() || ':' in path || path.startsWith('/') || path.startsWith('~') || '\\' in path)return null
        val parts=mutableListOf<String>()
        val prefix=from.substringBeforeLast('/',"")
        for(part in (if(prefix.isBlank())path else "$prefix/$path").split('/'))when(part) {
            "", "." -> Unit
            ".." -> if(parts.isEmpty())return null else parts.removeAt(parts.lastIndex)
            else -> parts.add(part)
        }
        return parts.joinToString("/")
    }
    fun capabilities(xml:String?,fallback:String):Capabilities {
        val root=xml?.let(::document)
        fun value(key:String)=root?.let { children(it).firstOrNull { e -> e.tagName==key }?.textContent?.trim() }.orEmpty()
        fun choices(tag:String)=root?.let { children(it).filter { e -> e.tagName==tag && children(e).none { c -> c.tagName=="selectable" && c.textContent.trim()=="false" } }.map { e ->
            val id=e.getAttribute("name").ifBlank { e.textContent.trim() }
            val labels=children(e).filter { c -> c.tagName=="label" }
            val label=labels.firstOrNull { it.getAttribute("language") in listOf("en_US","en_GB","") } ?: labels.firstOrNull()
            Choice(id,label?.textContent?.trim().orEmpty().ifBlank { id })
        }.filter { it.id.isNotBlank() } }.orEmpty()
        return Capabilities(value("themeName").ifBlank { fallback },value("author"),choices("variant"),choices("colorScheme"),choices("fontSize"),choices("aspectRatio"))
    }
    fun parse(system:String,options:Map<String,String>,read:(String)->String?):Model {
        val vars=mutableMapOf("system.theme" to if(system=="3ds") "n3ds" else system,"system.name" to system)
        val views=linkedMapOf<String,LinkedHashMap<String,Item>>()
        val warnings=linkedSetOf<String>();val visiting=mutableSetOf<String>()
        fun expand(value:String):String { var out=value;repeat(12) { out=variable.replace(out) { vars[it.groupValues[1]] ?: it.value } };return out }
        val supported=setOf("image","text","textlist","carousel","grid","video","datetime","gamelistinfo","rating")
        val ignored=setOf("helpsystem","gameselector","systemstatus","clock","badges","animation","sound")
        val paths=setOf("path","default","fontPath","staticImage","defaultImage","backgroundImage","selectorImage","unfilledPath","filledPath")
        fun load(path:String,depth:Int) {
            if(depth>24 || !visiting.add(path)) { warnings.add("Recursive include: $path");return }
            val xml=read(path)
            if(xml==null) { visiting.remove(path);return }
            val root=document(xml)
            require(root.tagName=="theme") { "$path is not an ES-DE theme XML file" }
            fun walk(parent:Element) {
                for(e in children(parent)) {
                    if(e.tagName in setOf("variant","colorScheme","fontSize","aspectRatio","language")) {
                        val selected=options[e.tagName] ?: ""
                        if(selected !in tokens(e.getAttribute("name")) && "all" !in tokens(e.getAttribute("name")))continue
                        walk(e);continue
                    }
                    when(e.tagName) {
                        "include" -> resolve(path,expand(e.textContent.trim()))?.let { load(it,depth+1) } ?: warnings.add("Include outside theme folder was ignored")
                        "variables" -> children(e).forEach { vars[it.tagName]=expand(it.textContent.trim()) }
                        "view" -> for(scope in tokens(e.getAttribute("name"))) {
                            val target=views.getOrPut(scope){ linkedMapOf() }
                            for(item in children(e)) {
                                if(item.tagName !in supported) {
                                    warnings.add("Unsupported element: ${item.tagName}")
                                    continue
                                }
                                for(name in tokens(item.getAttribute("name"))) {
                                    val props=target[name]?.props.orEmpty().toMutableMap()
                                    for(prop in children(item)) {
                                        var value=expand(prop.textContent.trim())
                                        if(prop.tagName in paths && value.isNotBlank())value=resolve(path,value) ?: ""
                                        props[prop.tagName]=value
                                    }
                                    if(item.tagName in setOf("carousel","grid"))warnings.add("${item.tagName.replaceFirstChar(Char::uppercase)} uses Orbit’s native selection layout")
                                    if(props.keys.any { it in setOf("shader","fragmentShader","blurPasses","animationType") })warnings.add("Advanced visual effects are not rendered")
                                    target[name]=Item(item.tagName,name,props)
                                }
                            }
                        }
                        "formatVersion" -> warnings.add("Legacy theme: modern system/gamelist elements are preferred")
                        else -> if(e.tagName !in ignored)walk(e)
                    }
                }
            }
            walk(root);visiting.remove(path)
        }
        val systemFile="${if(system=="3ds") "n3ds" else system}/theme.xml"
        if(read(systemFile)!=null)load(systemFile,0) else load("theme.xml",0)
        // Variables can be declared after a shared include; resolve the final values too.
        return Model(views.mapValues { (_,items) -> items.values.map { item -> item.copy(props=item.props.mapValues { expand(it.value) }) } },warnings.toList())
    }
}
