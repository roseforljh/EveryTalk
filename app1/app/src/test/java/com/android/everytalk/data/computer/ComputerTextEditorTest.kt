package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ComputerTextEditorTest {
    @Test
    fun `edit保留文件格式并一次应用多段修改`() {
        val original = "\uFEFF第一行  \r\nval quote = “旧值”\r\n最后一行\r\n"

        val result = ComputerTextEditor.apply(
            rawContent = original,
            edits = listOf(
                ComputerTextEdit("第一行\n", "新第一行\n"),
                ComputerTextEdit("val quote = \"旧值\"", "val quote = \"新值\""),
            ),
            path = "demo.kt",
        )

        assertEquals("\uFEFF新第一行\r\nval quote = \"新值\"\r\n最后一行\r\n", result.content)
        assertEquals(2, result.replacements)
    }

    @Test
    fun `edit拒绝不唯一和重叠的修改`() {
        assertThrows(IllegalArgumentException::class.java) {
            ComputerTextEditor.apply(
                rawContent = "重复\n重复\n",
                edits = listOf(ComputerTextEdit("重复", "完成")),
                path = "duplicate.txt",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComputerTextEditor.apply(
                rawContent = "abcdef",
                edits = listOf(
                    ComputerTextEdit("abcd", "A"),
                    ComputerTextEdit("cdef", "B"),
                ),
                path = "overlap.txt",
            )
        }
    }
}
