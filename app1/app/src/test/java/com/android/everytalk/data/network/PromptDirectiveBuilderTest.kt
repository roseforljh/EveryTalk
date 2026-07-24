package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptDirectiveBuilderTest {

    @Test
    fun `selector should use stable field and enum order`() {
        val selection = PromptSelection(
            primary = PromptPrimary.CODING_DEBUGGING,
            outputModules = linkedSetOf(PromptOutputModule.TABLE, PromptOutputModule.CODE_BLOCK),
            riskModules = linkedSetOf(PromptRiskModule.PRIVACY_CAUTION, PromptRiskModule.SECURITY_CAUTION),
            suppressedModules = linkedSetOf(PromptOutputModule.LONG_FORM),
        )

        val selector = PromptDirectiveBuilder.buildSelector(selection)

        assertEquals(
            "[ETD v=1 p=CODING_DEBUGGING o=CODE_BLOCK,TABLE r=SECURITY_CAUTION,PRIVACY_CAUTION x=LONG_FORM]",
            selector,
        )
    }

    @Test
    fun `decorating a text message should preserve original fields`() {
        val message = SimpleTextApiMessage(
            id = "user-1",
            role = "user",
            content = "修复这段崩溃日志",
            name = "tester",
        )

        val decorated = PromptDirectiveBuilder.decorateMessages(listOf(message)).single() as SimpleTextApiMessage

        assertEquals(message.id, decorated.id)
        assertEquals(message.role, decorated.role)
        assertEquals(message.name, decorated.name)
        assertTrue(decorated.content.startsWith(message.content))
        assertTrue(decorated.content.endsWith("]"))
    }

    @Test
    fun `history selectors should be deterministic and idempotent`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "u1", role = "user", content = "证明这个矩阵公式"),
            SimpleTextApiMessage(id = "a1", role = "assistant", content = "历史回答"),
            SimpleTextApiMessage(id = "u2", role = "user", content = "继续"),
        )

        val once = PromptDirectiveBuilder.decorateMessages(messages)
        val twice = PromptDirectiveBuilder.decorateMessages(once)

        assertEquals(once, twice)
        val firstUser = once[0] as SimpleTextApiMessage
        val secondUser = once[2] as SimpleTextApiMessage
        assertTrue(firstUser.content.contains("p=MATH_SYMBOLIC"))
        assertTrue(secondUser.content.contains("p=MATH_SYMBOLIC"))
    }

    @Test
    fun `multimodal decoration should preserve binary parts and order`() {
        val image = ApiContentPart.InlineData(base64Data = "abc123", mimeType = "image/png")
        val message = PartsApiMessage(
            id = "image-user",
            role = "user",
            parts = listOf(image, ApiContentPart.Text("分析这张截图")),
        )

        val decorated = PromptDirectiveBuilder.decorateMessages(listOf(message)).single() as PartsApiMessage

        assertEquals(image, decorated.parts[0])
        assertTrue((decorated.parts[1] as ApiContentPart.Text).text.contains("p=MULTIMODAL_ANALYSIS"))
    }

    @Test
    fun `file name should participate in attachment routing`() {
        val message = PartsApiMessage(
            id = "file-user",
            role = "user",
            parts = listOf(
                ApiContentPart.FileUri(uri = "content://files/report.csv", mimeType = "application/octet-stream"),
                ApiContentPart.Text("分析附件"),
            ),
        )

        val decorated = PromptDirectiveBuilder.decorateMessages(listOf(message)).single() as PartsApiMessage
        val text = decorated.parts.filterIsInstance<ApiContentPart.Text>().single().text

        assertTrue(text.contains("p=DATA_ANALYSIS"))
    }

    @Test
    fun `attachment only message should remain idempotent`() {
        val message = PartsApiMessage(
            id = "attachment-only",
            role = "user",
            parts = listOf(ApiContentPart.InlineData(base64Data = "abc", mimeType = "image/png")),
        )

        val once = PromptDirectiveBuilder.decorateMessages(listOf(message))
        val twice = PromptDirectiveBuilder.decorateMessages(once)

        assertEquals(once, twice)
    }

    @Test
    fun `later requests should preserve every completed user prefix`() {
        val intentResolver: (String) -> String = { text ->
            if (text.contains("Ktor", ignoreCase = true)) "DOCS_LOOKUP" else "LOCAL_REASONING"
        }
        val firstTurn = PromptDirectiveBuilder.decorateMessages(
            messages = listOf(SimpleTextApiMessage(id = "u1", role = "user", content = "Ktor bearer auth 怎么配")),
            queryIntentNameResolver = intentResolver,
        )
        val secondTurn = PromptDirectiveBuilder.decorateMessages(
            messages = listOf(
                SimpleTextApiMessage(id = "u1", role = "user", content = "Ktor bearer auth 怎么配"),
                SimpleTextApiMessage(id = "a1", role = "assistant", content = "第一轮回答"),
                SimpleTextApiMessage(id = "u2", role = "user", content = "继续"),
            ),
            queryIntentNameResolver = intentResolver,
        )
        val thirdTurn = PromptDirectiveBuilder.decorateMessages(
            messages = listOf(
                SimpleTextApiMessage(id = "u1", role = "user", content = "Ktor bearer auth 怎么配"),
                SimpleTextApiMessage(id = "a1", role = "assistant", content = "第一轮回答"),
                SimpleTextApiMessage(id = "u2", role = "user", content = "继续"),
                SimpleTextApiMessage(id = "a2", role = "assistant", content = "第二轮回答"),
                SimpleTextApiMessage(id = "u3", role = "user", content = "再详细一点"),
            ),
            queryIntentNameResolver = intentResolver,
        )

        assertEquals(firstTurn.single(), secondTurn.first())
        assertEquals(secondTurn, thirdTurn.take(secondTurn.size))
    }
}
