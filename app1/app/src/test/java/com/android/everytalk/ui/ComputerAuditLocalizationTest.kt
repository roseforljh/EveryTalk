package com.android.everytalk.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 锁定服务器审计的用户可见本地化，避免英文界面混入中文安全摘要。 */
class ComputerAuditLocalizationTest {
    @Test
    fun `Preview停止结果状态和安全摘要都经过本地化`() {
        val source = sourceFile("src/main/java/com/android/everytalk/ui/screens/computer/ComputerDetailScreen.kt")
        val english = sourceFile("src/main/res/values/strings.xml")
        val chinese = sourceFile("src/main/res/values-zh/strings.xml")

        assertTrue(source.contains("\"PREVIEW_STOPPED\" -> R.string.computer_audit_preview_stopped"))
        assertTrue(source.contains("auditOutcomeLabel(event.outcome)"))
        assertTrue(source.contains("auditSafeSummary(event)"))
        assertTrue(english.contains("name=\"computer_audit_preview_stopped\""))
        assertTrue(chinese.contains("name=\"computer_audit_preview_stopped\""))
    }

    private fun sourceFile(relativePath: String): String {
        val candidates = listOf(File(relativePath), File("app/$relativePath"), File("app1/app/$relativePath"))
        return requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到 $relativePath" }
            .readText(Charsets.UTF_8)
    }
}
