package com.android.everytalk.statecontroller.viewmodel

import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.ComputerCredentialState
import com.android.everytalk.data.computer.ComputerErrorCodes
import com.android.everytalk.data.computer.ComputerException
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.data.computer.ComputerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ComputerAgentToggleValidationTest {
    @Test
    fun `Agent开启只读取内存中的已选服务器状态`() {
        val computer = testComputer(ComputerStatus.READY)

        assertEquals(
            computer,
            requireSelectedReadyComputer(
                conversationId = "conversation_1",
                selections = mapOf("conversation_1" to computer.id),
                computers = listOf(computer),
            ),
        )
    }

    @Test
    fun `未选服或服务器不可用时立即拒绝开启`() {
        val missing = runCatching {
            requireSelectedReadyComputer("conversation_1", emptyMap(), emptyList())
        }.exceptionOrNull() as ComputerException
        val unavailable = testComputer(ComputerStatus.ERROR)
        val invalid = runCatching {
            requireSelectedReadyComputer(
                "conversation_1",
                mapOf("conversation_1" to unavailable.id),
                listOf(unavailable),
            )
        }.exceptionOrNull() as ComputerException

        assertEquals(ComputerErrorCodes.SERVER_NOT_SELECTED, missing.code)
        assertEquals(ComputerErrorCodes.COMPUTER_NOT_READY, invalid.code)
    }

    @Test
    fun `Agent点击路径先更新开关再异步准备服务器`() {
        val source = sourceFile("AppViewModelActions.kt")
        val function = source.substringAfter("internal fun AppViewModel.setAgentEnabled")
            .substringBefore("internal fun AppViewModel.selectComputerForCurrentConversation")

        val enabledIndex = function.indexOf("stateHolder._isAgentEnabled.value = true")
        val launchIndex = function.indexOf("viewModelScope.launch(Dispatchers.IO)")
        val prepareIndex = function.indexOf("computerManager.prepareRequest")
        assertEquals(true, enabledIndex in 0 until launchIndex)
        assertEquals(true, launchIndex in 0 until prepareIndex)
        assertEquals(false, function.contains("_isAgentPreparing.value = true"))
    }

    private fun testComputer(status: ComputerStatus): Computer = Computer(
        id = "computer_1",
        displayName = "VPS",
        host = "example.com",
        port = 22,
        username = "user",
        authKind = ComputerAuthKind.PASSWORD,
        credentialState = ComputerCredentialState.ORIGINAL_ENCRYPTED,
        hostKeyFingerprint = "SHA256:test",
        runMode = ComputerRunMode.DIRECT,
        status = status,
    )

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            java.io.File("src/main/java/com/android/everytalk/statecontroller/viewmodel/$name"),
            java.io.File("app/src/main/java/com/android/everytalk/statecontroller/viewmodel/$name"),
            java.io.File("app1/app/src/main/java/com/android/everytalk/statecontroller/viewmodel/$name"),
        )
        return requireNotNull(candidates.firstOrNull(java.io.File::isFile)).readText(Charsets.UTF_8)
    }
}
