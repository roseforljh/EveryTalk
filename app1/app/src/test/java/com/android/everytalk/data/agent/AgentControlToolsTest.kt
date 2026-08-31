package com.android.everytalk.data.agent

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentControlToolsTest {
    @Test
    fun `request_agent 只生成申请且保留去重后的 Skill ID`() {
        val call = AgentContentBlock.ToolCall(
            id = "call-1",
            name = AgentControlToolNames.REQUEST_AGENT,
            arguments = buildJsonObject {
                put("reason", "需要执行 Skill 脚本")
                put("required_skill_ids", buildJsonArray {
                    add(JsonPrimitive("skill-a"))
                    add(JsonPrimitive("skill-a"))
                    add(JsonPrimitive("skill-b"))
                })
            },
        )

        val request = requireNotNull(agentPauseRequest(call)) as AgentPauseRequest.EnableAgent

        assertEquals("需要执行 Skill 脚本", request.reason)
        assertEquals(listOf("skill-a", "skill-b"), request.requiredSkillIds)
    }

    @Test
    fun `request_skill_secret 只能申请当前快照中的 Skill`() {
        val call = AgentContentBlock.ToolCall(
            id = "call-secret",
            name = AgentControlToolNames.REQUEST_SKILL_SECRET,
            arguments = buildJsonObject {
                put("skill_id", "skill-a")
                put("name", "GITHUB_TOKEN")
                put("reason", "读取授权仓库")
            },
        )

        val request = agentPauseRequest(call, setOf("skill-a")) as AgentPauseRequest.SkillSecret

        assertEquals("skill-a", request.skillId)
        assertEquals("GITHUB_TOKEN", request.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `request_skill_secret 拒绝快照外的 Skill`() {
        agentPauseRequest(
            AgentContentBlock.ToolCall(
                id = "call-secret-invalid",
                name = AgentControlToolNames.REQUEST_SKILL_SECRET,
                arguments = buildJsonObject {
                    put("skill_id", "other")
                    put("name", "TOKEN")
                    put("reason", "测试")
                },
            ),
            setOf("skill-a"),
        )
    }

    @Test
    fun `其他工具不会触发 Agent 申请`() {
        assertNull(
            agentPauseRequest(
                AgentContentBlock.ToolCall("call-2", "load_skill", buildJsonObject {}),
            ),
        )
    }

    @Test
    fun `request_capability 只接受 capability 和安全原因`() {
        val request = agentPauseRequest(
            AgentContentBlock.ToolCall(
                "call-capability",
                AgentControlToolNames.REQUEST_CAPABILITY,
                buildJsonObject {
                    put("requested_capability", "git.push")
                    put("reason_safe", "推送当前仓库需要认证")
                    put("user_visible_context", "当前仓库")
                },
            ),
        ) as AgentPauseRequest.Capability

        assertEquals("git.push", request.request.requestedCapability)
        assertEquals("推送当前仓库需要认证", request.request.reasonSafe)
    }
}
