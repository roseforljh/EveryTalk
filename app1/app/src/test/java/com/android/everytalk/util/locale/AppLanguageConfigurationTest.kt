package com.android.everytalk.util.locale

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AppLanguageConfigurationTest {
    @Test
    fun `应用语言标签解析支持系统中文和英文`() {
        assertEquals(AppLanguage.SYSTEM, resolveAppLanguage(""))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, resolveAppLanguage("zh-Hans-CN"))
        assertEquals(AppLanguage.ENGLISH, resolveAppLanguage("en-US"))
        assertEquals(AppLanguage.SYSTEM, resolveAppLanguage("fr-FR"))
    }

    @Test
    fun `Manifest声明语言配置和低版本持久化`() {
        val manifest = parseXml(mainFile("AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals(
            "@xml/locales_config",
            application.getAttributeNS(ANDROID_NAMESPACE, "localeConfig"),
        )

        val services = manifest.getElementsByTagName("service")
        val localeService = (0 until services.length)
            .map { services.item(it) as Element }
            .first { it.getAttributeNS(ANDROID_NAMESPACE, "name") == APP_LOCALES_SERVICE }
        val metadata = localeService.getElementsByTagName("meta-data").item(0) as Element
        assertEquals("autoStoreLocales", metadata.getAttributeNS(ANDROID_NAMESPACE, "name"))
        assertEquals("true", metadata.getAttributeNS(ANDROID_NAMESPACE, "value"))
    }

    @Test
    fun `中英文资源名称保持一致`() {
        val localeConfig = parseXml(mainFile("res/xml/locales_config.xml"))
        val locales = localeConfig.getElementsByTagName("locale")
        val languageTags = (0 until locales.length)
            .map { (locales.item(it) as Element).getAttributeNS(ANDROID_NAMESPACE, "name") }
            .toSet()
        assertEquals(setOf("en", "zh-CN"), languageTags)

        val defaultResources = localizedResourceNames(mainFile("res/values/strings.xml"))
        val chineseResources = localizedResourceNames(mainFile("res/values-zh/strings.xml"))
        assertEquals(defaultResources, chineseResources)
        assertTrue(defaultResources.contains("string:app_language_system"))
        assertFalse(
            containsHan(parseXml(mainFile("res/values/strings.xml")).documentElement.textContent),
        )
    }

    @Test
    fun `语音功能资源完整且英文包不混入中文`() {
        val defaultVoiceStrings = stringValues(mainFile("res/values/strings.xml"))
            .filterKeys { it.startsWith("voice_") }
        val chineseVoiceStrings = stringValues(mainFile("res/values-zh/strings.xml"))
            .filterKeys { it.startsWith("voice_") }

        assertEquals(defaultVoiceStrings.keys, chineseVoiceStrings.keys)
        assertTrue(defaultVoiceStrings.size >= 160)
        assertTrue(defaultVoiceStrings.values.all(String::isNotBlank))
        assertTrue(chineseVoiceStrings.values.all(String::isNotBlank))
        assertFalse(defaultVoiceStrings.values.any(::containsHan))
        assertTrue(defaultVoiceStrings.getValue("voice_mode_prompt").contains("standard English"))
        assertTrue(chineseVoiceStrings.getValue("voice_mode_prompt").contains("标准中文"))
    }

    @Test
    fun `中英文格式参数一致且资源不重复或为空`() {
        val english = resourceValues(mainFile("res/values/strings.xml"))
        val chinese = resourceValues(mainFile("res/values-zh/strings.xml"))
        assertEquals(english.keys, chinese.keys)
        english.forEach { (name, values) ->
            val expected = formatArguments(values.first())
            (values + chinese.getValue(name)).forEach { value ->
                assertTrue("$name 不能为空", value.isNotBlank())
                assertEquals("$name 的格式参数不一致", expected, formatArguments(value))
            }
        }
    }

    @Test
    fun `全部界面的固定显示文案不得直接写中文`() {
        val uiRoot = requireNotNull(mainFile("java/com/android/everytalk/ui/screens/skill/SkillScreen.kt")
            .parentFile?.parentFile?.parentFile)
        // 只检查显示入口，代码中的兼容标签、日志和动画调试名称不属于显示文案。
        val displayLiteral = Regex("""(?:Text\s*\(\s*|(?:text|contentDescription|placeholder)\s*=\s*)"[^"\r\n]*[\u4e00-\u9fff][^"\r\n]*"""")
        val violations = uiRoot.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            val source = file.readText()
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""(?m)^\s*//.*$"""), "")
            displayLiteral.findAll(source).map { "${file.name}: ${it.value}" }
        }.toList()
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    private fun resourceValues(file: File): Map<String, List<String>> {
        val root = parseXml(file).documentElement
        val values = linkedMapOf<String, List<String>>()
        for (index in 0 until root.childNodes.length) {
            val element = root.childNodes.item(index) as? Element ?: continue
            if (element.tagName !in setOf("string", "plurals")) continue
            val key = "${element.tagName}:${element.getAttribute("name")}"
            val translations = if (element.tagName == "string") listOf(element.textContent) else {
                val items = element.getElementsByTagName("item")
                val quantities = (0 until items.length).map { items.item(it) as Element }
                assertTrue("$key 必须提供 other", quantities.any { it.getAttribute("quantity") == "other" })
                assertEquals("$key 的复数项重复", quantities.size, quantities.map { it.getAttribute("quantity") }.toSet().size)
                quantities.map { it.textContent }
            }
            assertFalse("资源名称重复：$key", values.containsKey(key))
            values[key] = translations
        }
        return values
    }

    private fun formatArguments(value: String): List<String> =
        Regex("""%(?:\d+\$)?[-+0, (#]*\d*(?:\.\d+)?[a-zA-Z]""")
            .findAll(value).map { it.value }.sorted().toList()

    private fun localizedResourceNames(file: File): Set<String> {
        val document = parseXml(file)
        return listOf("string", "plurals").flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { "$tag:${(nodes.item(it) as Element).getAttribute("name")}" }
        }.toSet()
    }

    private fun stringValues(file: File): Map<String, String> {
        val strings = parseXml(file).getElementsByTagName("string")
        return (0 until strings.length)
            .map { strings.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private fun containsHan(value: String): Boolean = value.codePoints().anyMatch {
        Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN
    }

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file)

    private fun mainFile(relativePath: String): File {
        val candidates = listOf(
            File("src/main/$relativePath"),
            File("app/src/main/$relativePath"),
            File("app1/app/src/main/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到 $relativePath" }
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val APP_LOCALES_SERVICE = "androidx.appcompat.app.AppLocalesMetadataHolderService"
    }
}
