package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Test

class SensitiveTerminalViewTest {
    @Test
    fun `可信同步点只移除敏感窗口`() {
        val view = SensitiveTerminalView()
        view.begin(4)
        view.finish(10, trustedSyncPoint = true)

        assertEquals("safeafter", view.sanitize("safeSECRETafter", 0, 15))
    }

    @Test
    fun `无法确认同步点时丢弃窗口后的全部 Agent View`() {
        val view = SensitiveTerminalView()
        view.begin(4)
        view.finish(10, trustedSyncPoint = false)

        assertEquals("safe", view.sanitize("safeSECRETafter", 0, 15))
    }

    @Test
    fun `接管尚未结束时 Agent View 不显示新增输出`() {
        val view = SensitiveTerminalView()
        view.begin(4)

        assertEquals("safe", view.sanitize("safeOTP", 0, 7))
    }
}
