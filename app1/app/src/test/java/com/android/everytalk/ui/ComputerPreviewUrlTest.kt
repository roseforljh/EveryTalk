package com.android.everytalk.ui

import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.ComputerPreview
import com.android.everytalk.data.computer.ComputerPreviewVisibility
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.ui.screens.computer.computerPreviewUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputerPreviewUrlTest {
    private val computer = Computer(
        id = "computer_test",
        displayName = "Test",
        host = "2001:db8::1",
        port = 22,
        username = "user",
        authKind = ComputerAuthKind.PRIVATE_KEY,
        runMode = ComputerRunMode.CONTAINER,
    )

    @Test
    fun `Private Preview 始终使用手机回环地址`() {
        val preview = ComputerPreview(
            workspaceId = "ws_test",
            remotePort = 3000,
            localPort = 41234,
            protocol = "http",
        )

        assertEquals("http://127.0.0.1:41234", computerPreviewUrl(computer, preview))
    }

    @Test
    fun `Public Preview 正确包裹 IPv6 Host`() {
        val preview = ComputerPreview(
            workspaceId = "ws_test",
            remotePort = 3000,
            publicPort = 32000,
            protocol = "https",
            visibility = ComputerPreviewVisibility.PUBLIC,
        )

        assertEquals("https://[2001:db8::1]:32000", computerPreviewUrl(computer, preview))
    }

    @Test
    fun `没有活动端口时不生成伪地址`() {
        val preview = ComputerPreview(workspaceId = "ws_test", remotePort = 3000)

        assertNull(computerPreviewUrl(computer, preview))
    }
}
