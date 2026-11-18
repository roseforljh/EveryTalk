package com.android.everytalk.data.agent

import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.DataClass.MessageQuoteReference
import com.android.everytalk.data.skill.MessageSkillReference
import com.android.everytalk.data.skill.SkillSourceType
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.screens.MainScreen.chat.text.skill.buildSkillContentParts
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudSteerFieldsTest {
    private val uuid = "10000000-0000-4000-8000-000000000001"

    @Test
    fun `编辑器生成的纯文字内容可以发给云端方向调整接口`() {
        val text = "换个方向\n先看原文"
        val parts = buildSkillContentParts(text, emptyList())
        assertEquals(listOf(MessageContentPart.Text(text)), parts)
        val expected = buildJsonObject { put("messageUuid", uuid); put("text", text) }
        assertEquals(expected, buildCloudSteerFields(uuid, text, parts, emptyList()))
        assertEquals(expected, buildCloudSteerFields(uuid, text, emptyList(), emptyList()))
    }

    @Test
    fun `附件和结构化引用继续留在队列而不是丢掉再发送`() {
        val text = "看一下"
        val parts = buildSkillContentParts(text, emptyList())
        val quote = MessageContentPart.QuoteReference(MessageQuoteReference(
            sourceConversationId = "chat", sourceMessageId = "source", quotedText = "原文",
        ))
        val skill = MessageContentPart.SkillReference(MessageSkillReference(
            "skill", "技能", SkillSourceType.USER_CREATED, contentHash = "hash",
        ))
        val attachment = SelectedMediaItem.Audio("audio", "audio/wav")
        for (reference in listOf(quote, skill)) {
            assertFalse(assertThrows(CloudSteerNotAccepted::class.java) {
                buildCloudSteerFields(uuid, text, listOf(reference) + parts, emptyList())
            }.unknown)
        }
        assertFalse(assertThrows(CloudSteerNotAccepted::class.java) {
            buildCloudSteerFields(uuid, text, parts, listOf(attachment))
        }.unknown)
    }

    @Test
    fun `空白文本仍不发给云端`() {
        assertFalse(assertThrows(CloudSteerNotAccepted::class.java) {
            buildCloudSteerFields(uuid, " \n", buildSkillContentParts(" \n", emptyList()), emptyList())
        }.unknown)
    }
}
