package com.android.everytalk

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SensitiveBackupRulesTest {
    private val excludedDomains = setOf(
        "root",
        "file",
        "database",
        "sharedpref",
        "external",
        "device_root",
        "device_file",
        "device_database",
        "device_sharedpref",
    )

    @Test
    fun `系统备份和设备迁移均排除敏感应用数据`() {
        val manifest = parseXml(mainFile("AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals(
            "false",
            application.getAttributeNS("http://schemas.android.com/apk/res/android", "allowBackup"),
        )

        val legacyRules = parseXml(mainFile("res/xml/backup_rules.xml"))
        assertEquals(excludedDomains, excludeDomains(legacyRules.documentElement))

        val extractionRules = parseXml(mainFile("res/xml/data_extraction_rules.xml"))
        listOf("cloud-backup", "device-transfer").forEach { sectionName ->
            val section = extractionRules.getElementsByTagName(sectionName).item(0) as Element
            assertEquals(excludedDomains, excludeDomains(section))
        }
    }

    private fun excludeDomains(parent: Element): Set<String> = buildSet {
        val nodes = parent.getElementsByTagName("exclude")
        for (index in 0 until nodes.length) {
            add((nodes.item(index) as Element).getAttribute("domain"))
        }
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
}
