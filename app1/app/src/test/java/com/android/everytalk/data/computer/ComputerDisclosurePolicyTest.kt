package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Test

class ComputerDisclosurePolicyTest {
    @Test
    fun `Container 只需要模型数据流确认`() {
        assertEquals(
            setOf(ComputerDisclosureKind.MODEL_DATA_FLOW),
            ComputerDisclosurePolicy.requiredFor(computer(ComputerRunMode.CONTAINER, "ubuntu")),
        )
    }

    @Test
    fun `旧Direct记录迁移期间也只在首次开启说明模型数据流`() {
        assertEquals(
            setOf(ComputerDisclosureKind.MODEL_DATA_FLOW),
            ComputerDisclosurePolicy.requiredFor(computer(ComputerRunMode.DIRECT, "ubuntu")),
        )
        assertEquals(
            setOf(ComputerDisclosureKind.MODEL_DATA_FLOW),
            ComputerDisclosurePolicy.requiredFor(computer(ComputerRunMode.DIRECT, "root")),
        )
    }

    private fun computer(runMode: ComputerRunMode, username: String) = Computer(
        id = "computer-1",
        displayName = "测试服务器",
        host = "vps.example.com",
        port = 22,
        username = username,
        authKind = ComputerAuthKind.PRIVATE_KEY,
        runMode = runMode,
        status = ComputerStatus.READY,
    )
}
