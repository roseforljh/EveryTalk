package com.android.everytalk.ui

import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerCredentialState
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.data.computer.ComputerStatus
import com.android.everytalk.data.computer.ComputerCredential
import com.android.everytalk.ui.screens.computer.ComputerAddFormError
import com.android.everytalk.ui.screens.computer.ComputerAddFormState
import com.android.everytalk.ui.screens.computer.toEditFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerAddFormTest {
    @Test
    fun `表单逐项阻止缺失的 SSH 信息`() {
        assertEquals(ComputerAddFormError.HOST_REQUIRED, ComputerAddFormState().validationError())
        assertEquals(
            ComputerAddFormError.PORT_INVALID,
            ComputerAddFormState(host = "vps.example.com", port = "70000").validationError(),
        )
        assertEquals(
            ComputerAddFormError.USERNAME_REQUIRED,
            ComputerAddFormState(host = "vps.example.com", username = "").validationError(),
        )
        assertEquals(
            ComputerAddFormError.PRIVATE_KEY_REQUIRED,
            ComputerAddFormState(
                host = "vps.example.com",
                authKind = ComputerAuthKind.PRIVATE_KEY,
            ).validationError(),
        )
    }

    @Test
    fun `有效表单只在提交时创建凭据和 sudo 字符数组`() {
        val form = ComputerAddFormState(
            host = "vps.example.com",
            port = "2222",
            username = "ubuntu",
            password = "ssh-password",
            sudoPassword = "sudo-password",
        )

        assertNull(form.validationError())
        val prepared = form.prepare()
        assertTrue(prepared.request.credential is ComputerCredential.Password)
        assertEquals(2222, prepared.request.port)
        assertEquals(ComputerRunMode.CONTAINER, prepared.request.runMode)
        assertEquals("sudo-password", prepared.sudoPassword?.concatToString())
        prepared.clear()
    }

    @Test
    fun `关闭沙箱后直接使用SSH`() {
        val prepared = ComputerAddFormState(
            host = "tiny.example.com",
            username = "root",
            password = "ssh-password",
            sandboxEnabled = false,
        ).prepare()

        assertEquals(ComputerRunMode.DIRECT, prepared.request.runMode)
        prepared.clear()
    }

    @Test
    fun `添加表单明确展示沙箱安全说明和Direct选择`() {
        val source = sourceFile("ComputerAddCard.kt")

        assertTrue(source.contains("computer_sandbox_title"))
        assertTrue(source.contains("computer_sandbox_enabled_description"))
        assertTrue(source.contains("computer_sandbox_disabled_description"))
        assertTrue(source.contains("form.copy(sandboxEnabled = it)"))
        assertTrue(source.contains("uncheckedTrackColor"))
        assertTrue(source.contains("uncheckedBorderColor"))
    }

    @Test
    fun `编辑表单回填全部非敏感参数且留空沿用原凭据`() {
        val computer = Computer(
            id = "computer-1",
            displayName = "主服务器",
            host = "vps.example.com",
            port = 2222,
            username = "ubuntu",
            authKind = ComputerAuthKind.PASSWORD,
            credentialState = ComputerCredentialState.DEDICATED_KEY,
            runMode = ComputerRunMode.CONTAINER,
            status = ComputerStatus.READY,
        )

        val form = computer.toEditFormState()
        assertEquals("主服务器", form.displayName)
        assertEquals("vps.example.com", form.host)
        assertEquals("2222", form.port)
        assertEquals("ubuntu", form.username)
        assertEquals(ComputerAuthKind.PASSWORD, form.authKind)
        assertEquals("", form.password)
        assertNull(form.validationError(computer.authKind))

        val prepared = form.prepareUpdate(computer)
        assertNull(prepared.request.credential)
        org.junit.Assert.assertFalse(prepared.replaceSudoPassword)
        prepared.clear()
    }

    @Test
    fun `编辑时切换登录方式必须输入对应新凭据`() {
        val computer = Computer(
            id = "computer-1",
            displayName = "主服务器",
            host = "vps.example.com",
            port = 22,
            username = "root",
            authKind = ComputerAuthKind.PASSWORD,
            runMode = ComputerRunMode.CONTAINER,
        )

        assertEquals(
            ComputerAddFormError.PRIVATE_KEY_REQUIRED,
            computer.toEditFormState()
                .copy(authKind = ComputerAuthKind.PRIVATE_KEY)
                .validationError(computer.authKind),
        )
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            java.io.File("src/main/java/com/android/everytalk/ui/screens/computer/$name"),
            java.io.File("app/src/main/java/com/android/everytalk/ui/screens/computer/$name"),
            java.io.File("app1/app/src/main/java/com/android/everytalk/ui/screens/computer/$name"),
        )
        return requireNotNull(candidates.firstOrNull(java.io.File::isFile)).readText(Charsets.UTF_8)
    }
}
