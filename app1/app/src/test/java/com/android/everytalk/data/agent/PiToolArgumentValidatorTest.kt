package com.android.everytalk.data.agent

import com.android.everytalk.data.computer.ComputerToolCatalog
import com.android.everytalk.data.computer.ComputerToolNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PiToolArgumentValidatorTest {
    @Test
    fun `基础类型按Pi规则转换后再校验`() {
        val call = AgentContentBlock.ToolCall(
            id = "call-1",
            name = ComputerToolNames.READ_FILE,
            arguments = buildJsonObject {
                put("path", "README.md")
                put("offset", "42")
                put("limit", true)
            },
        )

        val prepared = PiToolArgumentValidator.prepareAndValidate(call, ComputerToolCatalog.definitions())

        assertEquals(42L, prepared.arguments.getValue("offset").jsonPrimitive.longOrNull)
        assertEquals(1L, prepared.arguments.getValue("limit").jsonPrimitive.longOrNull)
    }

    @Test
    fun `字符串布尔值不会被错误转换为数字`() {
        val call = AgentContentBlock.ToolCall(
            id = "call-1",
            name = ComputerToolNames.READ_FILE,
            arguments = buildJsonObject {
                put("path", "README.md")
                put("offset", "true")
            },
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            PiToolArgumentValidator.prepareAndValidate(call, ComputerToolCatalog.definitions())
        }

        assertTrue(error.message.orEmpty().contains("root.offset must be integer"))
    }

    @Test
    fun `可选非空字段的null按Pi规则视为省略`() {
        val call = AgentContentBlock.ToolCall(
            id = "call-1",
            name = ComputerToolNames.READ_FILE,
            arguments = buildJsonObject {
                put("path", "README.md")
                put("offset", JsonNull)
            },
        )

        val prepared = PiToolArgumentValidator.prepareAndValidate(call, ComputerToolCatalog.definitions())

        assertFalse("offset" in prepared.arguments)
    }

    @Test
    fun `缺少必填字段和额外字段都在执行前拒绝`() {
        val call = AgentContentBlock.ToolCall(
            id = "call-1",
            name = ComputerToolNames.READ_FILE,
            arguments = buildJsonObject { put("unexpected", JsonPrimitive(true)) },
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            PiToolArgumentValidator.prepareAndValidate(call, ComputerToolCatalog.definitions())
        }

        assertTrue(error.message.orEmpty().contains("root.path is required"))
        assertTrue(error.message.orEmpty().contains("root.unexpected is not allowed"))
    }

    @Test
    fun `越界数值和非法枚举都在执行前拒绝`() {
        val call = AgentContentBlock.ToolCall(
            id = "call-1",
            name = ComputerToolNames.OPEN_PORT,
            arguments = buildJsonObject {
                put("port", 70_000)
                put("protocol", "ftp")
            },
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            PiToolArgumentValidator.prepareAndValidate(call, ComputerToolCatalog.definitions())
        }

        assertTrue(error.message.orEmpty().contains("root.port must be at most"))
        assertTrue(error.message.orEmpty().contains("root.protocol must be one of"))
    }

    @Test
    fun `不存在的工具作为参数错误返回模型`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PiToolArgumentValidator.prepareAndValidate(
                AgentContentBlock.ToolCall("call-1", "missing", buildJsonObject {}),
                ComputerToolCatalog.definitions(),
            )
        }

        assertEquals("Tool \"missing\" not found", error.message)
    }

    @Test
    fun `oneOf同时匹配多个分支时按Pi规则拒绝`() {
        val definitions = listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "ambiguous",
                    "parameters" to buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("value", buildJsonObject {
                                put("oneOf", buildJsonArray {
                                    add(buildJsonObject { put("type", "number") })
                                    add(buildJsonObject { put("minimum", 0) })
                                })
                            })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("value")) })
                        put("additionalProperties", false)
                    },
                ),
            ),
        )
        val call = AgentContentBlock.ToolCall(
            id = "call-one-of",
            name = "ambiguous",
            arguments = buildJsonObject { put("value", 1) },
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            PiToolArgumentValidator.prepareAndValidate(call, definitions)
        }

        assertTrue(error.message.orEmpty().contains("must match exactly one allowed schema"))
    }
}
