package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EsDeThemeTest {
    @Test fun everyBuiltInVariantAndColourSchemeResolvesFromOriginalXml() {
        val base=File("../tools/legacy-theme-assets/colorful")
        fun read(path:String)=File(base,path).takeIf(File::isFile)?.readText()
        for((variant,_) in EsDeTheme.variants) {
            val model=EsDeTheme.parse("psx",variant=variant,read=::read)
            assertEquals(variant,model.variant)
            assertTrue(variant,model.property("gamelist","gamelist-textlist","size").isNotBlank())
            assertTrue(variant,model.property("gamelist","game-video","imageType").isNotBlank())
            if(variant.contains("metadata"))assertEquals("true",model.property("gamelist","game-description","visible"))
        }
        val backgrounds=EsDeTheme.schemes.map { (scheme,_) ->
            val model=EsDeTheme.parse("psx",scheme=scheme,read=::read)
            assertTrue(scheme,model.variable("gamelistTextlistPrimaryColor").isNotBlank())
            model.variable("backgroundColor")
        }
        assertTrue(backgrounds.distinct().size>=4)
        val medium=EsDeTheme.parse("psx",font="medium",read=::read)
        assertEquals("0.033333333333333",medium.property("gamelist","gamelist-textlist","fontSize"))
    }
    @Test fun originalColorfulXmlResolvesSystemVariablesAndSelectedLayout() {
        val base=File("../tools/legacy-theme-assets/colorful")
        val model=EsDeTheme.parse("psx") { File(base,it).takeIf(File::isFile)?.readText() }
        assertEquals("PlayStation",model.variable("systemName"))
        assertEquals("878b8f",model.variable("backgroundColor"))
        assertEquals("./_inc/systems/images/psx.webp",model.property("system","system-carousel","staticImage"))
        assertEquals("0.046875 0.212962962962963",model.property("gamelist","gamelist-textlist","pos"))
        assertEquals("0.04074074",model.property("gamelist","gamelist-textlist","fontSize"))
        assertEquals("cover",model.property("gamelist","game-video","imageType"))
    }
    @Test fun orbit3dsIdUsesColorfulNintendo3dsAssets() {
        val base=File("../tools/legacy-theme-assets/colorful")
        val model=EsDeTheme.parse("3ds") { File(base,it).takeIf(File::isFile)?.readText() }
        assertEquals("Nintendo 3DS",model.variable("systemName"))
        val image=model.property("system","system-carousel","staticImage").removePrefix("./")
        assertEquals("_inc/systems/images/n3ds.webp",image)
        assertTrue(File(base,image).isFile)
    }
    @Test fun themeIncludesCannotEscapeThemeRootOrLoadEntities() {
        val paths=mutableListOf<String>()
        EsDeTheme.parse("psx") { path -> paths.add(path);if(path=="theme.xml") "<theme><include>../outside.xml</include></theme>" else null }
        assertEquals(listOf("theme.xml"),paths)
    }
}
