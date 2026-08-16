package com.android.everytalk.ui.screens.MainScreen.chat.text.skill

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.android.everytalk.data.skill.MessageSkillReference
import com.android.everytalk.data.skill.SkillSourceType
import com.android.everytalk.data.database.entities.SkillInstallationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillInlineEditorTest {
    private val reference = MessageSkillReference(
        skillId = "local:test",
        displayName = "PDF",
        sourceType = SkillSourceType.USER_CREATED,
        contentHash = "hash",
    )

    @Test
    fun `斜杠只在开头或空白后触发`() {
        assertEquals("", findSkillSlashQuery(value("/"))?.query)
        assertEquals("pd", findSkillSlashQuery(value("请用 /pd"))?.query)
        assertEquals("pd", findSkillSlashQuery(value("/pd"))?.query)
        assertNull(findSkillSlashQuery(value("https://example.com/pdf")))
        assertNull(findSkillSlashQuery(value("C:/pdf")))
        assertNull(findSkillSlashQuery(value("文字/pdf")))
        assertNull(findSkillSlashQuery(value("/pdf 后续")))
    }

    @Test
    fun `标签插入原位置并按一个字符整体删除`() {
        val original = value("前面 /pd 后面", cursor = 6)
        val query = requireNotNull(findSkillSlashQuery(original))
        val inserted = insertSkillReference(original, emptyList(), query, reference)
        assertEquals("前面 ${SKILL_TAG_MARKER}  后面", inserted.value.text)

        val marker = inserted.value.text.indexOf(SKILL_TAG_MARKER)
        val deletedText = inserted.value.text.removeRange(marker, marker + 1)
        val normalized = normalizeSkillEdit(inserted.value, value(deletedText, marker), inserted.references)
        assertEquals(emptyList<MessageSkillReference>(), normalized.references)
    }

    @Test
    fun `包名匹配会返回全部子 Skill 且不限制六条`() {
        val skills = (1..8).map { index -> installation("child-$index", packageName = "Ponytail") }

        val ranked = rankSkillCandidates(skills, "pon")

        assertEquals(8, ranked.size)
    }

    private fun installation(name: String, packageName: String) = SkillInstallationEntity(
        skillId = "local:$name",
        name = name,
        description = "说明",
        sourceType = SkillSourceType.LOCAL_IMPORT.name,
        sourceRepository = null,
        sourcePath = ".",
        currentHash = "hash-$name",
        enabled = true,
        invocationMode = "AUTO",
        updateHash = null,
        createdAt = 1,
        updatedAt = 1,
        lastUsedAt = null,
        useCount = 0,
        packageId = "local:package",
        packageName = packageName,
    )

    private fun value(text: String, cursor: Int = text.length) = TextFieldValue(text, TextRange(cursor))
}
