package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerWorkspacePathTest {
    @Test
    fun `workspace path accepts relative and workspace absolute paths`() {
        assertEquals("src/main.kt", ComputerWorkspacePath.normalize("src/./main.kt"))
        assertEquals("src/main.kt", ComputerWorkspacePath.normalize("/workspace/src/main.kt"))
        assertEquals("", ComputerWorkspacePath.normalize("/workspace", allowRoot = true))
    }

    @Test
    fun `workspace path rejects escape and other absolute roots`() {
        listOf("../secret", "src/../../secret", "/etc/passwd", "C:\\secret", "file\nname").forEach { path ->
            val failure = runCatching { ComputerWorkspacePath.normalize(path) }.exceptionOrNull()
            assertTrue("路径应被拒绝：$path", failure is ComputerException)
        }
    }
}
