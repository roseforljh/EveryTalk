package com.android.everytalk.ui.screens.MainScreen.chat.text.skill

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.skill.MessageSkillReference
import com.android.everytalk.data.database.entities.SkillInstallationEntity
import com.android.everytalk.data.skill.effectivePackageName

internal const val SKILL_TAG_MARKER: Char = '\uFFFC'

internal data class SkillSlashQuery(
    val start: Int,
    val end: Int,
    val query: String,
)

internal data class SkillEditState(
    val value: TextFieldValue,
    val references: List<MessageSkillReference>,
)

internal fun rankSkillCandidates(
    skills: List<SkillInstallationEntity>,
    query: String,
): List<SkillInstallationEntity> {
    val needle = query.trim().lowercase()
    return skills.asSequence()
        .filter(SkillInstallationEntity::enabled)
        .map { skill ->
            val name = skill.name.lowercase()
            val description = skill.description.lowercase()
            val packageName = skill.effectivePackageName().lowercase()
            val score = when {
                needle.isEmpty() -> 0
                name == needle -> 500
                name.startsWith(needle) -> 400
                needle in name -> 300
                needle in packageName -> 200
                needle in description -> 100
                else -> -1
            }
            skill to score
        }
        .filter { (_, score) -> score >= 0 }
        .sortedWith(
            compareByDescending<Pair<SkillInstallationEntity, Int>> { it.second }
                .thenByDescending { it.first.lastUsedAt ?: Long.MIN_VALUE }
                .thenByDescending { it.first.useCount }
                .thenBy { it.first.name.lowercase() },
        )
        .map { it.first }
        .toList()
}

/** 只识别开头或空白后的 `/查询词`，URL、路径、日期和分数不会触发。 */
internal fun findSkillSlashQuery(value: TextFieldValue): SkillSlashQuery? {
    if (!value.selection.collapsed) return null
    val cursor = value.selection.start.coerceIn(0, value.text.length)
    val start = value.text.lastIndexOf('/', cursor - 1)
    if (start < 0) return null
    if (start > 0 && !value.text[start - 1].isWhitespace()) return null
    val query = value.text.substring(start + 1, cursor)
    if (query.any(Char::isWhitespace)) return null
    if (query.any { it == '/' || it == '\\' || it == ':' || it == '.' }) return null
    return SkillSlashQuery(start, cursor, query)
}

internal fun insertSkillReference(
    value: TextFieldValue,
    references: List<MessageSkillReference>,
    query: SkillSlashQuery,
    reference: MessageSkillReference,
): SkillEditState {
    val isDuplicate = references.any { it.skillId == reference.skillId }
    var replaceEnd = query.end
    if (!isDuplicate) {
        // 候选词后原本通常已有空格。吞掉连续横向空白后统一补一个，避免标签和正文被两个空格拉开。
        while (
            replaceEnd < value.text.length &&
            (value.text[replaceEnd] == ' ' || value.text[replaceEnd] == '\t')
        ) {
            replaceEnd++
        }
    }
    val replacement = if (isDuplicate) "" else "${SKILL_TAG_MARKER}"
    val newText = value.text.replaceRange(query.start, replaceEnd, replacement)
    val markerIndex = value.text.take(query.start).count { it == SKILL_TAG_MARKER }
    val newReferences = if (replacement.isEmpty()) {
        references
    } else {
        references.toMutableList().apply { add(markerIndex.coerceAtMost(size), reference) }
    }
    val cursor = query.start + replacement.length
    return SkillEditState(TextFieldValue(newText, TextRange(cursor)), newReferences)
}

/**
 * 用户删掉占位符时同步删掉对应引用。新增的裸占位符会被移除，防止粘贴伪造标签。
 */
internal fun normalizeSkillEdit(
    oldValue: TextFieldValue,
    newValue: TextFieldValue,
    references: List<MessageSkillReference>,
): SkillEditState {
    val oldCount = oldValue.text.count { it == SKILL_TAG_MARKER }
    val newCount = newValue.text.count { it == SKILL_TAG_MARKER }
    if (newCount == oldCount) return SkillEditState(newValue, references)
    if (newCount > oldCount) {
        val cleaned = newValue.text.filterNot { it == SKILL_TAG_MARKER }.let { plain ->
            // 保留原有合法标签，裸占位符只可能来自本次粘贴。
            var result = plain
            oldValue.text.forEachIndexed { index, char ->
                if (char == SKILL_TAG_MARKER && index <= result.length) result = result.substring(0, index) + char + result.substring(index)
            }
            result
        }
        return SkillEditState(TextFieldValue(cleaned, TextRange(newValue.selection.start.coerceAtMost(cleaned.length))), references)
    }

    var prefix = 0
    val maxPrefix = minOf(oldValue.text.length, newValue.text.length)
    while (prefix < maxPrefix && oldValue.text[prefix] == newValue.text[prefix]) prefix++
    var suffix = 0
    while (
        suffix < oldValue.text.length - prefix &&
        suffix < newValue.text.length - prefix &&
        oldValue.text[oldValue.text.lastIndex - suffix] == newValue.text[newValue.text.lastIndex - suffix]
    ) suffix++
    val removedStart = oldValue.text.take(prefix).count { it == SKILL_TAG_MARKER }
    val removedCount = oldValue.text.substring(prefix, oldValue.text.length - suffix).count { it == SKILL_TAG_MARKER }
    val nextReferences = references.toMutableList().apply {
        repeat(removedCount.coerceAtMost(size - removedStart)) { removeAt(removedStart) }
    }
    return SkillEditState(newValue, nextReferences)
}

internal fun buildSkillContentParts(
    editorText: String,
    references: List<MessageSkillReference>,
): List<MessageContentPart> {
    if (references.isEmpty()) return listOf(MessageContentPart.Text(editorText)).filterNot { it.text.isEmpty() }
    val parts = mutableListOf<MessageContentPart>()
    val text = StringBuilder()
    var referenceIndex = 0
    fun flushText() {
        if (text.isNotEmpty()) {
            parts += MessageContentPart.Text(text.toString())
            text.clear()
        }
    }
    editorText.forEach { char ->
        if (char == SKILL_TAG_MARKER && referenceIndex < references.size) {
            flushText()
            parts += MessageContentPart.SkillReference(references[referenceIndex++])
        } else {
            text.append(char)
        }
    }
    flushText()
    return parts
}

internal fun displaySkillEditorText(editorText: String, references: List<MessageSkillReference>): String {
    var referenceIndex = 0
    return buildString {
        editorText.forEach { char ->
            if (char == SKILL_TAG_MARKER && referenceIndex < references.size) {
                append('‹').append(references[referenceIndex++].displayName).append('›')
            } else {
                append(char)
            }
        }
    }
}

internal class SkillTagVisualTransformation(
    private val references: List<MessageSkillReference>,
    private val textColor: Color,
    private val backgroundColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val originalToTransformed = IntArray(text.length + 1)
        val transformedToOriginal = mutableListOf(0)
        var referenceIndex = 0
        val transformed = buildAnnotatedString {
            text.text.forEachIndexed { index, char ->
                originalToTransformed[index] = length
                if (char == SKILL_TAG_MARKER && referenceIndex < references.size) {
                    val label = "‹${references[referenceIndex++].displayName}›"
                    val start = length
                    append(label)
                    addStyle(
                        SpanStyle(
                            color = textColor,
                            background = backgroundColor,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        start,
                        length,
                    )
                    repeat(label.length) { transformedToOriginal += index }
                    transformedToOriginal[transformedToOriginal.lastIndex] = index + 1
                } else {
                    append(char)
                    transformedToOriginal += index + 1
                }
            }
            originalToTransformed[text.length] = length
        }
        return TransformedText(
            transformed,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    originalToTransformed[offset.coerceIn(0, originalToTransformed.lastIndex)]

                override fun transformedToOriginal(offset: Int): Int =
                    transformedToOriginal[offset.coerceIn(0, transformedToOriginal.lastIndex)]
            },
        )
    }
}
