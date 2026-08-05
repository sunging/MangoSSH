package website.sung.mangossh.presentation

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class StringResourcesContractTest {
    @Test
    fun englishAndSimplifiedChineseExposeTheSameFormattingContract() {
        val appDir = locateAppDirectory()
        val english = readResources(appDir.resolve("src/main/res/values/strings.xml"))
        val chinese = readResources(appDir.resolve("src/main/res/values-zh-rCN/strings.xml"))

        val expectedNames = english
            .filterValues { it.translatable }
            .keys
        assertEquals(expectedNames, chinese.keys)
        expectedNames.forEach { name ->
            assertEquals(
                "Formatting placeholders differ for $name",
                english.getValue(name).placeholders,
                chinese.getValue(name).placeholders,
            )
        }
    }

    @Test
    fun kotlinSourcesContainNoHardcodedChineseUiLiterals() {
        val sourceRoot = locateAppDirectory().resolve("src/main/java")
        Files.walk(sourceRoot).use { paths ->
            val offenders = paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .flatMap { path ->
                    Files.readAllLines(path).mapIndexedNotNull { index, line ->
                        if (CJK.containsMatchIn(line)) "$path:${index + 1}" else null
                    }.stream()
                }
                .toList()
            assertFalse("Hardcoded Chinese text remains: $offenders", offenders.isNotEmpty())
        }
    }

    private fun locateAppDirectory(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(5) {
            if (Files.isRegularFile(current.resolve("src/main/res/values/strings.xml"))) return current
            if (Files.isRegularFile(current.resolve("app/src/main/res/values/strings.xml"))) {
                return current.resolve("app")
            }
            current = current.parent ?: return@repeat
        }
        error("Unable to locate the app module from ${System.getProperty("user.dir")}")
    }

    private fun readResources(path: Path): Map<String, ResourceContract> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile())
        val result = linkedMapOf<String, ResourceContract>()
        val children = document.documentElement.childNodes
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            if (element.tagName != "string" && element.tagName != "plurals") continue
            val name = element.getAttribute("name")
            val translatable = element.getAttribute("translatable") != "false"
            result[name] = ResourceContract(
                translatable = translatable,
                placeholders = PLACEHOLDER.findAll(element.textContent)
                    .map { it.value }
                    .distinct()
                    .sorted()
                    .toList(),
            )
        }
        return result
    }

    private data class ResourceContract(
        val translatable: Boolean,
        val placeholders: List<String>,
    )

    private companion object {
        val CJK = Regex("[\\u3400-\\u9fff]")
        val PLACEHOLDER = Regex("%(?:\\d+\\$)?[dfgs]")
    }
}
