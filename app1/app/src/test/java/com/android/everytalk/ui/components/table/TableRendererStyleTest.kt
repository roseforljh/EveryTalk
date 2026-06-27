package com.android.everytalk.ui.components.table

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableRendererStyleTest {

    @Test
    fun `table renderer follows chatgpt table spacing evidence`() {
        val styleSource = sourceFile(
            "src/main/java/com/android/everytalk/ui/components/ChatMarkdownTextStyle.kt",
            "app/src/main/java/com/android/everytalk/ui/components/ChatMarkdownTextStyle.kt",
            "app1/app/src/main/java/com/android/everytalk/ui/components/ChatMarkdownTextStyle.kt",
        )
        val tableSource = sourceFile(
            "src/main/java/com/android/everytalk/ui/components/table/TableRenderer.kt",
            "app/src/main/java/com/android/everytalk/ui/components/table/TableRenderer.kt",
            "app1/app/src/main/java/com/android/everytalk/ui/components/table/TableRenderer.kt",
        )

        assertTrue(styleSource.contains("const val TABLE_CELL_PADDING_DP = 8f"))
        assertTrue(styleSource.contains("const val TABLE_BORDER_STROKE_WIDTH_DP = 1f"))
        assertTrue(tableSource.contains("ChatMarkdownTextStyle.TABLE_CELL_PADDING_DP.dp"))
        assertTrue(tableSource.contains("ChatMarkdownTextStyle.TABLE_BORDER_STROKE_WIDTH_DP.dp.toPx()"))
        assertTrue(tableSource.contains("ChatMarkdownTextStyle.TABLE_CELL_CONTENT_MAX_WIDTH_INSET_DP.dp"))
        assertFalse(tableSource.contains("0.5.dp.toPx()"))
        assertFalse(tableSource.contains(".padding(horizontal = 12.dp)"))
        assertFalse(tableSource.contains("width - 24.dp"))
    }

    @Test
    fun `table columns use chatgpt equal width formula`() {
        val width = TableUtils.calculateChatGptEqualColumnWidthPx(
            maxTableWidthPx = 360f,
            columnCount = 3,
            borderStrokeWidthPx = 1f,
        )

        assertEquals(119, width)
    }

    @Test
    fun `table renderer follows chatgpt default non scroll column arrangement`() {
        val tableSource = sourceFile(
            "src/main/java/com/android/everytalk/ui/components/table/TableRenderer.kt",
            "app/src/main/java/com/android/everytalk/ui/components/table/TableRenderer.kt",
            "app1/app/src/main/java/com/android/everytalk/ui/components/table/TableRenderer.kt",
        )

        assertTrue(tableSource.contains("BoxWithConstraints"))
        assertTrue(tableSource.contains("TableUtils.calculateChatGptEqualColumnWidthPx"))
        assertFalse(tableSource.contains("horizontalScroll"))
        assertFalse(tableSource.contains("rememberScrollState"))
        assertFalse(tableSource.contains("TableUtils.calculateColumnWidths"))
    }

    @Test
    fun `table renderer follows chatgpt divider style semantics`() {
        val tableSource = sourceFile(
            "src/main/java/com/android/everytalk/ui/components/table/TableRenderer.kt",
            "app/src/main/java/com/android/everytalk/ui/components/table/TableRenderer.kt",
            "app1/app/src/main/java/com/android/everytalk/ui/components/table/TableRenderer.kt",
        )

        assertTrue(tableSource.contains("val headerDividerColor = rowDividerColor"))
        assertTrue(
            Regex(
                """\.background\(headerBackgroundColor\)\s*\.drawBehind\s*\{[\s\S]*color = headerDividerColor"""
            ).containsMatchIn(tableSource)
        )
        assertTrue(
            Regex(
                """if \(rowIndex < dataRows\.lastIndex\)\s*\{[\s\S]*color = rowDividerColor"""
            ).containsMatchIn(tableSource)
        )
        assertFalse(tableSource.contains("columnDividerColor"))
        assertFalse(tableSource.contains("drawRightSeparator"))
        assertFalse(tableSource.contains("separatorColor"))
    }
    private fun sourceFile(vararg candidates: String): String {
        return candidates
            .map(::File)
            .first { it.exists() }
            .readText(Charsets.UTF_8)
    }
}
