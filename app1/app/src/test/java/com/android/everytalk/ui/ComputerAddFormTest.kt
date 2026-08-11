package com.android.everytalk.ui

import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.ComputerCredential
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.ui.screens.computer.ComputerAddFormError
import com.android.everytalk.ui.screens.computer.ComputerAddFormState
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
            runMode = ComputerRunMode.CONTAINER,
            sudoPassword = "sudo-password",
        )

        assertNull(form.validationError())
        val prepared = form.prepare()
        assertTrue(prepared.request.credential is ComputerCredential.Password)
        assertEquals(2222, prepared.request.port)
        assertEquals("sudo-password", prepared.sudoPassword?.concatToString())
        prepared.clear()
    }
}
