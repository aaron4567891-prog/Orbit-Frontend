package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EsDeXmlTest {
    @Test fun capabilitiesDiscoverLabelsAndExcludeHiddenVariants() {
        val caps=EsDeXml.capabilities("""<themeCapabilities><themeName>Example</themeName><variant name="grid"><label language="en_US">Cover grid</label></variant><variant name="hidden"><selectable>false</selectable></variant><colorScheme name="dark"><label>Dark</label></colorScheme><fontSize>large</fontSize><aspectRatio>4:3</aspectRatio></themeCapabilities>""","Fallback")
        assertEquals("Example",caps.name)
        assertEquals(listOf(EsDeXml.Choice("grid","Cover grid")),caps.variants)
        assertEquals("dark",caps.schemes.single().id)
        assertEquals("4:3",caps.ratios.single().id)
    }
    @Test fun includesResolveRelativeToTheirOwnFilesAndMergeByName() {
        val files=mapOf(
            "theme.xml" to """<theme><variables><accent>1177ff</accent></variables><include>parts/layout.xml</include><variant name="selected"><view name="gamelist"><text name="title"><color>ffffff</color></text></view></variant></theme>""",
            "parts/layout.xml" to """<theme><include>../common.xml</include><view name="gamelist"><text name="title"><pos>0.1 0.2</pos><color>${'$'}{accent}</color></text><image name="console"><path>../images/${'$'}{system.theme}.png</path></image></view></theme>""",
            "common.xml" to """<theme><view name="system"><text name="name"><systemdata>fullname</systemdata></text></view></theme>"""
        )
        val model=EsDeXml.parse("psx",mapOf("variant" to "selected"),files::get)
        val title=model.views.getValue("gamelist").first { it.name=="title" }
        assertEquals("ffffff",title.props["color"])
        assertEquals("0.1 0.2",title.props["pos"])
        assertEquals("images/psx.png",model.views.getValue("gamelist").first { it.type=="image" }.props["path"])
        assertEquals("fullname",model.views.getValue("system").single().props["systemdata"])
    }
    @Test fun themesCannotReadOutsideRootOrExpandEntities() {
        assertNull(EsDeXml.resolve("theme.xml","../secret.xml"))
        assertNull(EsDeXml.resolve("theme.xml","https://example.com/file"))
        assertNull(EsDeXml.resolve("theme.xml","/etc/passwd"))
        assertEquals("common.xml",EsDeXml.resolve("parts/theme.xml","../common.xml"))
        try { EsDeXml.document("<!DOCTYPE theme [<!ENTITY x SYSTEM 'file:///secret'>]><theme/>");fail("Entity declarations must be rejected") } catch(_:IllegalArgumentException) { }
        val paths=mutableListOf<String>()
        val parsed=EsDeXml.parse("psx",emptyMap()) { path -> paths.add(path);if(path=="theme.xml")"<theme><include>../secret.xml</include></theme>" else null }
        assertFalse(paths.contains("../secret.xml"))
        assertTrue(parsed.warnings.isNotEmpty())
    }
    @Test fun systemSpecificThemeAndUnsupportedElementsAreHandled() {
        val files=mapOf("psx/theme.xml" to """<theme><view name="system"><animation name="intro"><path>intro.json</path></animation><text name="name"><text>PlayStation</text></text></view></theme>""","theme.xml" to "<theme/>")
        val parsed=EsDeXml.parse("psx",emptyMap(),files::get)
        assertEquals("PlayStation",parsed.views.getValue("system").single().props["text"])
        assertTrue(parsed.warnings.any { "animation" in it })
    }
    @Test fun originalColourfulCanBeReadWithoutHardcodedElementNames() {
        val root=File("../tools/legacy-theme-assets/colorful")
        val model=EsDeXml.parse("psx",mapOf("variant" to "gamelist-list-cover","colorScheme" to "colorful","fontSize" to "large","aspectRatio" to "16:9","language" to "en_US")) { File(root,it).takeIf(File::isFile)?.readText() }
        assertTrue(model.views.getValue("system").any { it.type=="carousel" })
        assertTrue(model.views.getValue("gamelist").any { it.type=="textlist" })
        assertTrue(model.views.getValue("gamelist").any { it.type=="video" })
        assertFalse(model.views.values.flatten().flatMap { it.props.values }.any { "${'$'}{" in it })
    }
}
