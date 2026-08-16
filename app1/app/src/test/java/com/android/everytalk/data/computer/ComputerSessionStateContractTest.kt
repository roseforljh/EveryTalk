package com.android.everytalk.data.computer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerSessionStateContractTest {
    @Test
    fun `模型轮次读取任务状态时不得同步连接SSH`() {
        val source = sourceFile().readText(Charsets.UTF_8)
        val method = source.substringAfter("suspend fun getComputerSessionState(")
            .substringBefore("suspend fun", missingDelimiterValue = source)

        assertTrue(method.contains("dao.getRemoteExecutionsForWorkspace(workspaceId)"))
        assertFalse(method.contains("executionReconciler"))
    }

    private fun sourceFile(): File {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/data/computer/ComputerRepository.kt"),
            File("app/src/main/java/com/android/everytalk/data/computer/ComputerRepository.kt"),
            File("app1/app/src/main/java/com/android/everytalk/data/computer/ComputerRepository.kt"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile))
    }
}
