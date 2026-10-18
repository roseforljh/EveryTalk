package com.android.everytalk.data.agent

import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.RequestContextManagement
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.database.entities.AgentCompactionEntryEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextManagerTest {
    private val manager = AgentContextManager()

    @Test
    fun `多工具调用缺少一个结果时保留已有事实并补失败结果`() {
        val assistant = assistantWithCalls("call-1", "call-2")
        val messages = listOf(
            SimpleTextApiMessage(id = "user-1", role = "user", content = "检查服务器"),
            assistant,
            toolResult("call-1"),
        )

        val cleaned = manager.removeOrphanToolResults(messages)

        assertEquals(listOf("user-1", "assistant-1", "result-call-1", "missing:assistant-1:call-2"), cleaned.map { it.id })
        val missing = cleaned.last() as AgentToolResultApiMessage
        assertTrue(missing.isError)
        assertEquals("call-2", missing.toolCallId)
    }

    @Test
    fun `所有结果齐全时按原顺序保留完整工具组`() {
        val assistant = assistantWithCalls("call-1", "call-2")
        val messages = listOf(
            assistant,
            toolResult("call-1"),
            toolResult("call-2"),
        )

        val cleaned = manager.removeOrphanToolResults(messages)

        assertEquals(messages, cleaned)
        assertTrue(cleaned[1] is AgentToolResultApiMessage)
    }

    @Test
    fun `工具结果没有紧跟调用时仍保留调用和已知结果`() {
        val messages = listOf(
            assistantWithCalls("call-1", "call-2"),
            toolResult("call-1"),
            SimpleTextApiMessage(id = "user-2", role = "user", content = "插入的新消息"),
            toolResult("call-2"),
        )

        val cleaned = manager.removeOrphanToolResults(messages)

        assertTrue(cleaned.any { it.id == "assistant-1" })
        assertTrue(cleaned.any { it.id == "result-call-1" })
        assertTrue(cleaned.any { it.id == "missing:assistant-1:call-2" })
        assertTrue(cleaned.any { it.id == "user-2" })
        assertTrue(cleaned.any { it.id == "history:result-call-2" })
    }

    @Test
    fun `没有新增历史时不会反复压缩同一检查点`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "user-1", role = "user", content = "很长的旧任务".repeat(200)),
        )
        val checkpoint = AgentCompactionEntryEntity(
            id = "compact-1",
            sessionId = "session-1",
            configIdSnapshot = "config-1",
            summary = "旧任务摘要",
            summarizedThroughItemId = "user-1",
            prefixFingerprint = agentTranscriptFingerprint(
                listOf("user-1|user|null|${messages.single().content}"),
            ),
            retainedTailJson = """{"summary_role":"user","retained_ids":[]}""",
            tokensBefore = 2_000,
            estimatedTokensAfter = 100,
            summaryRequestId = "request-1",
            status = AgentCompactionStatus.COMPLETED.name,
            createdAt = 1L,
        )

        val prepared = manager.prepare(
            requestId = "request-2",
            request = ChatRequest(
                messages = messages,
                provider = "OpenAI",
                channel = "OpenAI",
                apiAddress = "https://example.test",
                apiKey = "test",
                model = "model",
                contextManagement = RequestContextManagement(
                    configId = "config-1",
                    maxContextTokens = 1_000,
                    reservedOutputTokens = 100,
                    compactThresholdTokens = 1,
                    autoCompressionEnabled = true,
                ),
            ),
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
            checkpoint = checkpoint,
        )

        assertEquals(null, prepared.compactionPlan)
        val summary = prepared.messages.single() as SimpleTextApiMessage
        assertTrue(summary.content.contains("旧任务摘要"))
    }

    @Test
    fun `最新检查点边界被重新生成删除时回退到更早检查点`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "user-1", role = "user", content = "早期需求"),
            SimpleTextApiMessage(id = "assistant-1", role = "assistant", content = "早期结论"),
            SimpleTextApiMessage(id = "latest-user", role = "user", content = "换模型继续优化"),
        )
        val older = AgentCompactionEntryEntity(
            id = "compact-old",
            sessionId = "session-1",
            configIdSnapshot = "gpt-config",
            summary = "早期会话摘要",
            summarizedThroughItemId = "assistant-1",
            prefixFingerprint = agentTranscriptFingerprint(
                listOf(
                    "user-1|user|null|早期需求",
                    "assistant-1|assistant|null|早期结论",
                ),
            ),
            retainedTailJson = """{"summary_role":"system"}""",
            tokensBefore = 10_000,
            estimatedTokensAfter = 2_000,
            summaryRequestId = "request-old",
            status = AgentCompactionStatus.COMPLETED.name,
            createdAt = 1L,
        )
        val newest = older.copy(
            id = "compact-new",
            summarizedThroughItemId = "removed-assistant",
            prefixFingerprint = "stale-after-regeneration",
            summary = "不应重新放回的旧分支摘要",
            createdAt = 2L,
        )

        val prepared = manager.prepare(
            requestId = "request-gemini",
            request = request(
                messages = messages,
                maxContextTokens = 1_000_000,
                compactThresholdTokens = 900_000,
            ),
            limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 1_000_000),
            checkpoint = newest,
            checkpointFallbacks = listOf(older),
        )

        assertEquals("compact-old", prepared.snapshot.compactionId)
        assertTrue(prepared.messages.any { it is SimpleTextApiMessage && it.content.contains("早期会话摘要") })
        assertFalse(prepared.messages.any { it is SimpleTextApiMessage && it.content.contains("不应重新放回") })
    }

    @Test
    fun `压缩边界被分支删除时仍从活动摘要接续而不是恢复完整历史`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "user-1", role = "user", content = "早期需求"),
            SimpleTextApiMessage(id = "assistant-1", role = "assistant", content = "早期结论"),
            SimpleTextApiMessage(id = "regenerated-user", role = "user", content = "重新优化"),
        )
        val checkpoint = AgentCompactionEntryEntity(
            id = "compact-active",
            sessionId = "session-1",
            configIdSnapshot = "gpt-config",
            summary = "已经压缩的历史状态",
            summarizedThroughItemId = "deleted-assistant",
            prefixFingerprint = "stale-after-regeneration",
            retainedTailJson = """{
                "summary_role":"system",
                "source_prefix_ids":["user-1","assistant-1","deleted-assistant"]
            }""".trimIndent(),
            tokensBefore = 10_000,
            estimatedTokensAfter = 2_000,
            summaryRequestId = "request-old",
            status = AgentCompactionStatus.COMPLETED.name,
            createdAt = 1L,
        )

        val prepared = manager.prepare(
            requestId = "request-gemini",
            request = request(
                messages = messages,
                maxContextTokens = 1_000_000,
                compactThresholdTokens = 900_000,
            ),
            limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 1_000_000),
            checkpoint = checkpoint,
        )

        assertEquals("compact-active", prepared.snapshot.compactionId)
        assertEquals(
            listOf("regenerated-user"),
            prepared.messages.filterNot { it.role.equals("system", true) }.map { it.id },
        )
        assertTrue(prepared.messages.any { it is SimpleTextApiMessage && it.content.contains("已经压缩的历史状态") })
    }

    @Test
    fun `旧检查点只有retained_ids时也能恢复活动摘要`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "old-user", role = "user", content = "旧需求"),
            SimpleTextApiMessage(id = "retained-user", role = "user", content = "继续处理"),
        )
        val checkpoint = AgentCompactionEntryEntity(
            id = "legacy-compact",
            sessionId = "session-1",
            configIdSnapshot = "gpt-config",
            summary = "旧版本压缩摘要",
            summarizedThroughItemId = "deleted-boundary",
            prefixFingerprint = "stale",
            retainedTailJson = """{"summary_role":"system","retained_ids":["retained-user"]}""",
            tokensBefore = 10_000,
            estimatedTokensAfter = 2_000,
            summaryRequestId = "request-old",
            status = AgentCompactionStatus.COMPLETED.name,
            createdAt = 1L,
        )

        val prepared = manager.prepare(
            requestId = "request-gemini",
            request = request(
                messages = messages,
                maxContextTokens = 1_000_000,
                compactThresholdTokens = 900_000,
            ),
            limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 1_000_000),
            checkpoint = checkpoint,
        )

        assertTrue(prepared.messages.any { it is SimpleTextApiMessage && it.content.contains("旧版本压缩摘要") })
        assertEquals("retained-user", prepared.messages.last().id)
    }

    @Test
    fun `大窗口模型恢复截短历史时仍按保留边界替换全部已压缩原文`() {
        val checkpoint = AgentCompactionEntryEntity(
            id = "compact-truncated",
            sessionId = "session-1",
            configIdSnapshot = "model-a-config",
            summary = "模型A已经压缩的结论",
            summarizedThroughItemId = "missing-boundary",
            prefixFingerprint = "original-full-history",
            retainedTailJson = """{"summary_role":"system","source_prefix_ids":["missing-first","old-user","missing-boundary"],"retained_ids":["retained-user","retained-assistant"]}""",
            tokensBefore = 180_000,
            estimatedTokensAfter = 100_000,
            summaryRequestId = "summary-a",
            status = AgentCompactionStatus.COMPLETED.name,
            createdAt = 1L,
        )
        val history = listOf(
            SimpleTextApiMessage(id = "old-user", role = "user", content = "不应回灌的旧原文".repeat(200)),
            SimpleTextApiMessage(id = "retained-user", role = "user", content = "近期任务"),
            SimpleTextApiMessage(id = "retained-assistant", role = "assistant", content = "近期结论"),
            SimpleTextApiMessage(id = "new-user", role = "user", content = "切换模型继续"),
        )
        // 同时覆盖历史从保留边界开始的情况：替换数量为零也必须插入摘要。
        for (messages in listOf(history, history.drop(1))) {
            val prepared = manager.prepare(
                requestId = "request-model-b",
                request = request(messages, maxContextTokens = 1_000_000, compactThresholdTokens = 900_000)
                    .copy(model = "model-b"),
                limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 1_000_000),
                checkpoint = checkpoint,
            )

            assertEquals(checkpoint.id, prepared.snapshot.compactionId)
            assertNull(prepared.compactionPlan)
            assertEquals(
                listOf("agent-compaction:${checkpoint.id}", "retained-user", "retained-assistant", "new-user"),
                prepared.messages.map { it.id },
            )
        }

        // 最新记录不可用时，次新的活动摘要仍应优先于更老但全文指纹匹配的摘要。
        val older = checkpoint.copy(
            id = "older-direct",
            summarizedThroughItemId = history.first().id,
            prefixFingerprint = agentTranscriptFingerprint(
                listOf("old-user|user|null|${history.first().content}"),
            ),
            retainedTailJson = """{"summary_role":"system"}""",
        )
        val recovered = manager.prepare(
            requestId = "request-fallback",
            request = request(history, maxContextTokens = 1_000_000, compactThresholdTokens = 900_000),
            limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 1_000_000),
            checkpoint = checkpoint.copy(id = "failed-checkpoint", status = "FAILED"),
            checkpointFallbacks = listOf(checkpoint, older),
        )
        assertEquals(checkpoint.id, recovered.snapshot.compactionId)
        assertFalse(recovered.messages.any { it.id == "old-user" })
    }

    @Test
    fun `只有后续保留消息存在时不能跳过缺失边界之间的新分支`() {
        val checkpoint = AgentCompactionEntryEntity(
            id = "compact-branch",
            sessionId = "session-1",
            configIdSnapshot = "config-1",
            summary = "旧摘要",
            summarizedThroughItemId = "missing-boundary",
            prefixFingerprint = "stale",
            retainedTailJson = """{"summary_role":"system","source_prefix_ids":["old-user","missing-boundary"],"retained_ids":["missing-first-retained","retained-assistant"]}""",
            tokensBefore = 180_000,
            estimatedTokensAfter = 100_000,
            summaryRequestId = "summary-a",
            status = AgentCompactionStatus.COMPLETED.name,
            createdAt = 1L,
        )
        val messages = listOf(
            SimpleTextApiMessage(id = "old-user", role = "user", content = "旧需求"),
            SimpleTextApiMessage(id = "new-branch", role = "user", content = "必须保留的新指令"),
            SimpleTextApiMessage(id = "retained-assistant", role = "assistant", content = "保留回答"),
        )
        val prepared = manager.prepare(
            requestId = "branch",
            request = request(messages, maxContextTokens = 1_000_000, compactThresholdTokens = 900_000),
            limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 1_000_000),
            checkpoint = checkpoint,
        )

        assertEquals(listOf("agent-compaction:${checkpoint.id}", "new-branch", "retained-assistant"), prepared.messages.map { it.id })
    }

    @Test
    fun `压缩前缀插入排队指令且首条保留消息缺失时不回灌其他已摘要消息`() {
        val checkpoint = AgentCompactionEntryEntity(
            id = "compact-interleaved", sessionId = "session-1", configIdSnapshot = "config-a",
            summary = "已覆盖全部旧原文的摘要", summarizedThroughItemId = "missing-boundary",
            prefixFingerprint = "stale",
            retainedTailJson = """{"summary_role":"system","source_prefix_ids":["old-1","old-2","old-3","missing-boundary"],"retained_ids":["missing-first-retained","recent-user"]}""",
            tokensBefore = 180_000, estimatedTokensAfter = 80_000,
            summaryRequestId = "summary-a", status = AgentCompactionStatus.COMPLETED.name, createdAt = 1L,
        )
        val messages = listOf(
            SimpleTextApiMessage(id = "old-1", role = "user", content = "旧".repeat(30_000)),
            SimpleTextApiMessage(id = "queued", role = "user", content = "排队补充的新要求必须保留"),
            SimpleTextApiMessage(id = "old-2", role = "assistant", content = "文".repeat(30_000)),
            SimpleTextApiMessage(id = "old-3", role = "user", content = "本".repeat(30_000)),
            SimpleTextApiMessage(id = "recent-user", role = "user", content = "近期需求"),
            SimpleTextApiMessage(id = "latest-user", role = "user", content = "看看有无最新代码需要拉"),
        )
        val request = request(messages, maxContextTokens = 1_000_000, compactThresholdTokens = 900_000)
        val limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 1_000_000)
        val prepared = manager.prepare("model-b", request, limits, checkpoint)
        assertEquals(
            listOf("agent-compaction:${checkpoint.id}", "queued", "recent-user", "latest-user"),
            prepared.messages.map { it.id },
        )
        assertTrue(prepared.snapshot.activeContextTokens < 1_000)

        // 再次压缩也只总结新增内容，不能把前缀中断后散落的旧原文再次放入摘要请求。
        val plan = checkNotNull(manager.prepare("recompact", request, limits, checkpoint, forceLocalCompaction = true).compactionPlan)
        assertEquals(checkpoint.summary, plan.previousSummary)
        assertTrue(plan.messagesToSummarize.none { it.id.startsWith("old-") })
        assertTrue(plan.messagesToSummarize.any { it.id == "queued" })
    }

    @Test
    fun `检查点后的超长工具轮次继续使用user摘要角色`() {
        val user = SimpleTextApiMessage(id = "user-1", role = "user", content = "持续处理")
        val messages = listOf(
            user,
            assistantWithCalls("call-1").copy(id = "assistant-1"),
            toolResult("call-1", "x".repeat(4_000)),
            assistantWithCalls("call-2").copy(id = "assistant-2"),
            toolResult("call-2", "y".repeat(4_000)),
        )
        val checkpoint = AgentCompactionEntryEntity(
            id = "compact-user",
            sessionId = "session-1",
            configIdSnapshot = "config-1",
            summary = "当前任务摘要",
            summarizedThroughItemId = user.id,
            prefixFingerprint = agentTranscriptFingerprint(listOf("user-1|user|null|${user.content}")),
            retainedTailJson = """{"summary_role":"user","retained_ids":[]}""",
            tokensBefore = 4_000,
            estimatedTokensAfter = 1_000,
            summaryRequestId = "request-1",
            status = AgentCompactionStatus.COMPLETED.name,
            createdAt = 1L,
        )

        val plan = checkNotNull(
            manager.prepare(
                requestId = "request-checkpoint-split",
                request = request(messages, maxContextTokens = 8_192, compactThresholdTokens = 1),
                limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8_192),
                checkpoint = checkpoint,
            ).compactionPlan
        )

        assertEquals("user", plan.summaryRole)
        assertTrue(plan.isSplitTurn)
    }

    @Test
    fun `只有最新用户消息达到软阈值时保留原文继续`() {
        val prepared = manager.prepare(
            requestId = "request-latest",
            request = request(
                messages = listOf(
                    SimpleTextApiMessage(id = "latest-user", role = "user", content = "最新消息".repeat(40)),
                ),
                maxContextTokens = 4_096,
                compactThresholdTokens = 1,
            ),
            limits = ModelTokenLimits(maxOutputTokens = 256, maxContextTokens = 4_096),
        )

        assertNull(prepared.compactionPlan)
        assertEquals(listOf("latest-user"), prepared.messages.map { it.id })
    }

    @Test
    fun `Pi触发语义下最大输出不参与当前上下文压缩判断`() {
        val messages = buildList {
            repeat(4) { index ->
                add(SimpleTextApiMessage(id = "user-$index", role = "user", content = "问".repeat(9_650)))
                add(SimpleTextApiMessage(id = "assistant-$index", role = "assistant", content = "答".repeat(9_650)))
            }
        }

        val belowThreshold = manager.prepare(
            requestId = "request-below-pi-threshold",
            request = request(messages, maxContextTokens = 256_000, compactThresholdTokens = 204_800),
            limits = ModelTokenLimits(maxOutputTokens = 128_000, maxContextTokens = 256_000),
        )
        val aboveThreshold = manager.prepare(
            requestId = "request-above-pi-threshold",
            request = request(
                messages = messages.map { message ->
                    message.copy(content = message.content.repeat(3))
                },
                maxContextTokens = 256_000,
                compactThresholdTokens = 204_800,
            ),
            limits = ModelTokenLimits(maxOutputTokens = 128_000, maxContextTokens = 256_000),
        )

        assertNull(belowThreshold.compactionPlan)
        assertTrue(aboveThreshold.compactionPlan != null)
    }

    @Test
    fun `超过窗口减最大输出的硬上限时即使未到压缩线也先压缩`() {
        val messages = buildList {
            repeat(4) { index ->
                add(SimpleTextApiMessage(id = "user-$index", role = "user", content = "问".repeat(19_300)))
                add(SimpleTextApiMessage(id = "assistant-$index", role = "assistant", content = "答".repeat(19_300)))
            }
        }

        // 约 15.4 万 token：高于 25.6 万 - 12.8 万的硬上限，低于 20.48 万压缩线。
        val prepared = manager.prepare(
            requestId = "request-between-budget-and-threshold",
            request = request(messages, maxContextTokens = 256_000, compactThresholdTokens = 204_800),
            limits = ModelTokenLimits(maxOutputTokens = 128_000, maxContextTokens = 256_000),
        )

        val plan = checkNotNull(prepared.compactionPlan)
        assertTrue(plan.targetAfterCompaction < 128_000)
    }

    @Test
    fun `单个用户任务产生多轮工具调用时从原子组边界切分`() {
        val messages = buildList {
            add(SimpleTextApiMessage(id = "user-1", role = "user", content = "持续检查服务器"))
            repeat(4) { index ->
                val callId = "call-${index + 1}"
                add(assistantWithCalls(callId).copy(id = "assistant-${index + 1}"))
                add(toolResult(callId, "x".repeat(4_000)))
            }
        }

        val plan = manager.prepare(
            requestId = "request-split-turn",
            request = request(messages, maxContextTokens = 8_192, compactThresholdTokens = 1),
            limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8_192),
        ).compactionPlan

        val splitPlan = checkNotNull(plan)
        assertTrue(splitPlan.isSplitTurn)
        assertEquals("user", splitPlan.summaryRole)
        assertTrue(splitPlan.messagesToSummarize.any { it.id == "user-1" })
        assertFalse(splitPlan.retainedTailIds.contains("user-1"))
        assertTrue(splitPlan.retainedTailIds.first().startsWith("assistant-"))
    }

    @Test
    fun `多轮历史按近期Token预算保留多个回合`() {
        val messages = buildList {
            repeat(12) { index ->
                val turn = index + 1
                add(SimpleTextApiMessage(id = "user-$turn", role = "user", content = "u".repeat(1_000)))
                add(SimpleTextApiMessage(id = "assistant-$turn", role = "assistant", content = "a".repeat(1_000)))
            }
        }

        val plan = checkNotNull(
            manager.prepare(
                requestId = "request-recent-budget",
                // 触发线为 6,000 时近期预算能稳定容纳两个完整回合，测试目标不依赖极小阈值副作用。
                request = request(messages, maxContextTokens = 8_192, compactThresholdTokens = 6_000),
                limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8_192),
            ).compactionPlan
        )

        assertTrue(plan.retainedTailIds.contains("user-11"))
        assertTrue(plan.retainedTailIds.contains("user-12"))
        assertFalse(plan.retainedTailIds.contains("user-1"))
    }

    @Test
    fun `压缩文本保留工具调用ID和失败状态`() {
        val plan = AgentCompactionPlan(
            messagesToSummarize = listOf(
                toolResult("call-failed", "permission denied").copy(isError = true),
            ),
            previousSummary = null,
            summarizedThroughItemId = "result-call-failed",
            prefixFingerprint = "fingerprint",
            retainedTailIds = emptyList(),
            summaryRole = "user",
            tokensBefore = 100,
        )

        val serialized = manager.serializeForCompaction(plan)

        assertTrue(serialized.contains("id=call-failed"))
        assertTrue(serialized.contains("status=失败"))
        assertTrue(serialized.contains("permission denied"))
    }

    @Test
    fun `官方原生压缩在软阈值先获得处理机会`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "user-1", role = "user", content = "旧消息".repeat(300)),
            SimpleTextApiMessage(id = "assistant-1", role = "assistant", content = "旧回复".repeat(300)),
            SimpleTextApiMessage(id = "user-2", role = "user", content = "继续"),
        )

        val prepared = manager.prepare(
            requestId = "request-native-first",
            request = request(
                messages = messages,
                channel = "Codex",
                apiAddress = "https://api.openai.com/v1/responses",
                maxContextTokens = 8_192,
                compactThresholdTokens = 1,
            ),
            limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8_192),
        )

        assertNull(prepared.compactionPlan)
        assertEquals(messages.map { it.id }, prepared.messages.map { it.id })
    }

    @Test
    fun `bash工具压缩保留命令退出码和尾部错误`() {
        val result = AgentToolResultApiMessage(
            id = "tool-exec",
            toolCallId = "call-exec",
            toolName = "exec",
            content = JsonObject(
                mapOf(
                    "command" to JsonPrimitive("bun test"),
                    "cwd" to JsonPrimitive("/workspace"),
                    "exit_code" to JsonPrimitive(1),
                    "stdout" to JsonPrimitive("正常输出"),
                    "stderr" to JsonPrimitive("x".repeat(2_000) + "\nERROR: failed at /workspace/src/App.kt:42"),
                )
            ),
            isError = true,
        )

        val compacted = compactAgentToolResultForCompaction(result)

        assertTrue(compacted.contains("command: bun test"))
        assertTrue(compacted.contains("exitCode: 1"))
        assertTrue(compacted.contains("ERROR: failed at /workspace/src/App.kt:42"))
    }

    private fun assistantWithCalls(vararg ids: String): AgentAssistantApiMessage =
        AgentAssistantApiMessage(
            id = "assistant-1",
            toolCalls = ids.map { id ->
                AgentToolCallApiPart(id = id, name = "exec", arguments = JsonObject(emptyMap()))
            },
        )

    private fun toolResult(id: String, content: String = "ok"): AgentToolResultApiMessage = AgentToolResultApiMessage(
        id = "result-$id",
        toolCallId = id,
        toolName = "exec",
        content = JsonPrimitive(content),
    )

    private fun request(
        messages: List<com.android.everytalk.data.DataClass.AbstractApiMessage>,
        channel: String = "OpenAI兼容",
        apiAddress: String = "https://example.test",
        maxContextTokens: Int,
        compactThresholdTokens: Long,
    ): ChatRequest = ChatRequest(
        messages = messages,
        provider = "OpenAI",
        channel = channel,
        apiAddress = apiAddress,
        apiKey = "test",
        model = "model",
        contextManagement = RequestContextManagement(
            configId = "config-1",
            maxContextTokens = maxContextTokens,
            reservedOutputTokens = 512,
            compactThresholdTokens = compactThresholdTokens,
            autoCompressionEnabled = true,
        ),
    )
}
