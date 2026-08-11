package com.android.everytalk.data.computer

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
}
