package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoContextCompressionTest {
    @Test
    fun `未达到阈值时不生成压缩计划`() {
        val messages = conversation(turns = 3, repeatedChars = 10)

        val plan = planAutoContextCompression(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 10_000),
            thresholdPercent = 80,
        )

        assertFalse(plan.needsSummary)
        assertEquals(messages, plan.effectiveMessages)
    }

    @Test
    fun `最大输出预留不参与自动压缩触发判断`() {
        val messages = conversation(turns = 4, repeatedChars = 9_650)

        val plan = planAutoContextCompression(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 128_000, maxContextTokens = 256_000),
            thresholdPercent = 80,
        )

        assertFalse(plan.needsSummary)
        assertTrue(plan.usedTokens < plan.triggerTokens)
    }

    @Test
    fun `达到阈值时总结旧轮次并保留最近两轮`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "system", role = "system", content = "系统提示"),
        ) + conversation(turns = 4, repeatedChars = 150)

        val plan = planAutoContextCompression(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
            thresholdPercent = 50,
        )

        assertTrue(plan.needsSummary)
        assertEquals(listOf("u1", "a1", "u2", "a2"), plan.messagesToSummarize.map { it.id })
        assertEquals("a2", plan.summarizedThroughMessageId)
        assertFalse(plan.messagesToSummarize.any { it.role == "system" })
    }

    @Test
    fun `只有两轮时压缩第一轮并直接保留最新轮`() {
        val messages = conversation(turns = 2, repeatedChars = 300)

        val plan = planAutoContextCompression(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
            thresholdPercent = 50,
        )

        assertTrue(plan.needsSummary)
        assertEquals(listOf("u1", "a1"), plan.messagesToSummarize.map { it.id })
        assertEquals("a1", plan.summarizedThroughMessageId)
    }

    @Test
    fun `运行时触发值强制钳制到百分之九十`() {
        val plan = planAutoContextCompression(
            messages = conversation(turns = 2, repeatedChars = 10),
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
            thresholdPercent = 95,
        )

        assertEquals(900L, plan.triggerTokens)
    }

    @Test
    fun `服务端输入校准差值参与发送前触发判断`() {
        val messages = conversation(turns = 3, repeatedChars = 20)
        val withoutCalibration = planAutoContextCompression(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
            thresholdPercent = 80,
        )
        val withCalibration = planAutoContextCompression(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
            thresholdPercent = 80,
            inputTokenCalibration = 700,
        )

        assertFalse(withoutCalibration.needsSummary)
        assertTrue(withCalibration.needsSummary)
    }

    @Test
    fun `工具结果与所属轮次一起压缩`() {
        val messages = buildList {
            add(SimpleTextApiMessage(id = "u1", role = "user", content = "问题".repeat(150)))
            add(SimpleTextApiMessage(id = "a1", role = "assistant", content = "调用工具".repeat(150)))
            add(SimpleTextApiMessage(id = "t1", role = "tool", content = "工具结果".repeat(150)))
            addAll(conversation(turns = 3, repeatedChars = 150, startAt = 2))
        }

        val plan = planAutoContextCompression(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
            thresholdPercent = 50,
        )

        assertTrue(plan.needsSummary)
        assertTrue(plan.messagesToSummarize.any { it.id == "a1" })
        assertTrue(plan.messagesToSummarize.any { it.id == "t1" })
    }

    @Test
    fun `检查点替换旧历史并在前缀未变化时复用`() {
        val messages = conversation(turns = 4, repeatedChars = 150)
        val initialPlan = planAutoContextCompression(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
            thresholdPercent = 50,
        )
        val checkpoint = AutoContextCompressionCheckpoint(
            summary = "旧会话摘要",
            summarizedThroughMessageId = checkNotNull(initialPlan.summarizedThroughMessageId),
            summarizedPrefixFingerprint = checkNotNull(initialPlan.summarizedPrefixFingerprint),
        )

        val reused = planAutoContextCompression(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 5_000),
            thresholdPercent = 80,
            checkpoint = checkpoint,
        )

        assertEquals(checkpoint, reused.acceptedCheckpoint)
        assertFalse(reused.needsSummary)
        assertEquals(listOf("system", "u3", "a3", "u4", "a4"), reused.effectiveMessages.map { message ->
            if (message.id.startsWith("auto-context-summary:")) "system" else message.id
        })
        assertTrue((reused.effectiveMessages[0] as SimpleTextApiMessage).content.contains("旧会话摘要"))
    }

    @Test
    fun `旧消息被编辑后检查点立即失效`() {
        val messages = conversation(turns = 4, repeatedChars = 150)
        val initialPlan = planAutoContextCompression(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
            thresholdPercent = 50,
        )
        val checkpoint = AutoContextCompressionCheckpoint(
            summary = "旧会话摘要",
            summarizedThroughMessageId = checkNotNull(initialPlan.summarizedThroughMessageId),
            summarizedPrefixFingerprint = checkNotNull(initialPlan.summarizedPrefixFingerprint),
        )
        val edited = messages.map { message ->
            if (message.id == "u1") message.copy(content = "已编辑") else message
        }

        val plan = planAutoContextCompression(
            messages = edited,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 5_000),
            thresholdPercent = 80,
            checkpoint = checkpoint,
        )

        assertNull(plan.acceptedCheckpoint)
        assertEquals(edited, plan.effectiveMessages)
    }

    @Test
    fun `系统提示仅重建ID时仍复用检查点`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "system-old", role = "system", content = "固定系统提示"),
        ) + conversation(turns = 4, repeatedChars = 150)
        val initialPlan = planAutoContextCompression(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
            thresholdPercent = 50,
        )
        val checkpoint = AutoContextCompressionCheckpoint(
            summary = "旧会话摘要",
            summarizedThroughMessageId = checkNotNull(initialPlan.summarizedThroughMessageId),
            summarizedPrefixFingerprint = checkNotNull(initialPlan.summarizedPrefixFingerprint),
        )
        val rebuiltSystemPrompt = messages.map { message ->
            if (message.role == "system") message.copy(id = "system-new") else message
        }

        val plan = planAutoContextCompression(
            messages = rebuiltSystemPrompt,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 5_000),
            thresholdPercent = 80,
            checkpoint = checkpoint,
        )

        assertEquals(checkpoint, plan.acceptedCheckpoint)
    }

    @Test
    fun `摘要失败时完整保留压缩前有效上下文`() = runTest {
        val messages = conversation(turns = 4, repeatedChars = 150)
        val plan = planAutoContextCompression(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
            thresholdPercent = 50,
        )

        val outcome = completeAutoContextCompressionPlan(messages, plan) {
            throw IllegalStateException("summary failed")
        }

        assertTrue(outcome is AutoContextCompressionOutcome.Failure)
        assertEquals(plan.effectiveMessages, outcome.messages)
    }

    @Test
    fun `压缩请求开始时立即触发普通加载回调`() = runTest {
        val messages = conversation(turns = 4, repeatedChars = 150)
        val plan = planAutoContextCompression(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
            thresholdPercent = 50,
        )
        val events = mutableListOf<String>()

        completeAutoContextCompressionPlan(
            messages = messages,
            plan = plan,
            onSummaryStarted = { events += "loading" },
        ) {
            events += "summary"
            "旧会话摘要"
        }

        assertEquals(listOf("loading", "summary"), events)
    }

    @Test
    fun `摘要协程取消继续向上传播`() = runTest {
        val messages = conversation(turns = 4, repeatedChars = 150)
        val plan = planAutoContextCompression(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
            thresholdPercent = 50,
        )
        var cancellationPropagated = false

        try {
            completeAutoContextCompressionPlan(messages, plan) {
                throw CancellationException("cancelled")
            }
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue(cancellationPropagated)
    }

    private fun conversation(
        turns: Int,
        repeatedChars: Int,
        startAt: Int = 1,
    ) = buildList {
        repeat(turns) { offset ->
            val turn = startAt + offset
            add(SimpleTextApiMessage(id = "u$turn", role = "user", content = "问".repeat(repeatedChars)))
            add(SimpleTextApiMessage(id = "a$turn", role = "assistant", content = "答".repeat(repeatedChars)))
        }
    }
}
