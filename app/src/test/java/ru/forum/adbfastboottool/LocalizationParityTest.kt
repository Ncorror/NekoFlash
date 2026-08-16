package ru.forum.adbfastboottool

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LocalizationParityTest {

    @Test
    fun englishAndRussianResourcesExposeTheSameNamedEntries() {
        val english = resourceFile("values/strings.xml")
        val russian = resourceFile("values-ru/strings.xml")

        val englishKeys = namedResourceKeys(english)
        val russianKeys = namedResourceKeys(russian)

        assertTrue("English resource set is unexpectedly empty", englishKeys.isNotEmpty())
        assertEquals(
            "RU/EN localization keys differ. Missing in RU=${englishKeys - russianKeys}; missing in EN=${russianKeys - englishKeys}",
            englishKeys,
            russianKeys
        )
    }

    private fun resourceFile(relative: String): File {
        val roots = listOf(
            File(System.getProperty("user.dir")),
            File(System.getProperty("user.dir"), "app")
        )
        return roots
            .map { root -> File(root, "src/main/res/$relative") }
            .firstOrNull { it.isFile }
            ?: error("Unable to locate src/main/res/$relative from ${System.getProperty("user.dir")}")
    }

    private fun namedResourceKeys(file: File): Set<String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val root = factory.newDocumentBuilder().parse(file).documentElement
        val keys = linkedSetOf<String>()
        for (index in 0 until root.childNodes.length) {
            val node = root.childNodes.item(index)
            if (node is Element && node.hasAttribute("name")) {
                keys += "${node.tagName}:${node.getAttribute("name")}"
            }
        }
        return keys
    }
}
