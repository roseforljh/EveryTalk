package com.android.everytalk.data.computer

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ComputerToolCallSafetyTest {
    @Test
    fun `Queue Peek 不要求写确认但创建和删除要求`() {
        val args = buildJsonObject { put("queue_id", "queue") }
        assertTrue(ComputerToolCallSafety.isReadOnly(ComputerToolNames.QUEUES_PEEK, args))
        assertFalse(ComputerToolCallSafety.isReadOnly(ComputerToolNames.QUEUES_CREATE, args))
        assertFalse(ComputerToolCallSafety.isReadOnly(ComputerToolNames.QUEUES_DELETE, args))
    }

    @Test
    fun `D1 只读查询不要求写确认`() {
        assertTrue(ComputerToolCallSafety.isReadOnly(ComputerToolNames.D1_QUERY, buildJsonObject {
            put("sql", JsonPrimitive("SELECT name FROM users"))
        }))
    }

    @Test
    fun `D1 写查询必须进入确认路径`() {
        assertFalse(ComputerToolCallSafety.isReadOnly(ComputerToolNames.D1_QUERY, buildJsonObject {
            put("sql", JsonPrimitive("UPDATE users SET name = 'x'"))
        }))
    }

    @Test
    fun `Worker 部署可以由当前 Workspace 打包而不要求模型传源码`() {
        val deploy = ComputerToolCatalog.cloudflareDefinitions()
            .first { (it["function"] as? Map<*, *>)?.get("name") == ComputerToolNames.WORKER_DEPLOY }
        val parameters = (deploy["function"] as Map<*, *>)["parameters"] as Map<*, *>
        assertEquals(listOf("worker_name"), parameters["required"])
    }
}
