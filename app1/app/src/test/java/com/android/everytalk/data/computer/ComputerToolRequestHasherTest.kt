package com.android.everytalk.data.computer

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ComputerToolRequestHasherTest {
    private val context = ComputerRequestContext("conversation-1", "computer-1", "workspace-1")

    @Test
    fun `request hash ignores json object key order and includes request snapshot`() {
        val first = buildJsonObject { put("a", 1); put("b", "two") }
        val reordered = buildJsonObject { put("b", "two"); put("a", 1) }

        assertEquals(
            ComputerToolRequestHasher.requestHash("exec", first, context),
            ComputerToolRequestHasher.requestHash("exec", reordered, context),
        )
        assertNotEquals(
            ComputerToolRequestHasher.requestHash("exec", first, context),
            ComputerToolRequestHasher.requestHash("exec", first, context.copy(computerId = "computer-2")),
        )
    }

    @Test
    fun `permission refresh uses current computer mode and keeps approval marker`() = runTest {
        val approvedContext = context.copy(
            permissionMode = ComputerPermissionMode.MANUAL,
            approvedToolCallId = "tool-call-1",
        )
        val computer = Computer(
            id = "computer-1",
            displayName = "VPS",
            host = "example.com",
            port = 22,
            username = "root",
            authKind = ComputerAuthKind.PASSWORD,
            runMode = ComputerRunMode.DIRECT,
            permissionMode = ComputerPermissionMode.FULL,
        )

        val refreshed = refreshComputerPermissionMode(approvedContext) { computer }

        assertEquals(ComputerPermissionMode.FULL, refreshed.permissionMode)
        assertEquals("tool-call-1", refreshed.approvedToolCallId)
    }
}
