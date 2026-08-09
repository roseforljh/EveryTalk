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
