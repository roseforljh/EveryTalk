package com.android.everytalk.data.computer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerDiagnosticsTest {
    @Test
    fun `诊断日志保留类型和调用栈且移除敏感消息`() {
        val sensitiveMessage = "host=173.254.230.17 username=root password=secret"
        val cause = IllegalStateException(sensitiveMessage).apply {
            stackTrace = arrayOf(StackTraceElement("CipherLayer", "encrypt", "CipherLayer.kt", 42))
        }
        val error = ComputerException(
            code = ComputerErrorCodes.KEYSTORE_UNAVAILABLE,
            message = sensitiveMessage,
            cause = cause,
        ).apply {
            stackTrace = arrayOf(StackTraceElement("CredentialStore", "save", "CredentialStore.kt", 88))
        }

        val header = ComputerDiagnostics.buildHeader(ComputerFailureStage.ADD_SERVER, error)
        val stackTrace = ComputerDiagnostics.sanitizedThrowable(error).stackTraceToString()

        assertTrue(header.contains("stage=ADD_SERVER"))
        assertTrue(header.contains("code=${ComputerErrorCodes.KEYSTORE_UNAVAILABLE}"))
        assertTrue(stackTrace.contains(ComputerException::class.java.name))
        assertTrue(stackTrace.contains(IllegalStateException::class.java.name))
        assertTrue(stackTrace.contains("CredentialStore.save(CredentialStore.kt:88)"))
        assertTrue(stackTrace.contains("CipherLayer.encrypt(CipherLayer.kt:42)"))
        assertFalse(stackTrace.contains(sensitiveMessage))
        assertFalse(stackTrace.contains("secret"))
    }
}
