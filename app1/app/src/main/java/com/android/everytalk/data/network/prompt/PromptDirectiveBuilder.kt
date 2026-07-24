package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage

/** 构建紧凑 ETD 选择码，并只修改发送给 API 的消息副本。 */
internal object PromptDirectiveBuilder {

    private const val MAX_SELECTOR_CHARS = 280
    private const val SELECTOR_START = "[ETD v=${SystemPromptInjector.PROTOCOL_VERSION} "
    private val trailingSelectorRegex = Regex(
        "(?:\\n\\n)?\\[ETD v=${SystemPromptInjector.PROTOCOL_VERSION} " +
            "p=[A-Z_]+ o=(?:NONE|[A-Z_,]+) r=(?:NONE|[A-Z_,]+) x=(?:NONE|[A-Z_,]+)]$",
    )

    fun buildSelector(selection: PromptSelection): String {
        val outputs = orderedNames(PromptOutputModule.entries, selection.outputModules)
        val risks = orderedNames(PromptRiskModule.entries, selection.riskModules)
        val suppressed = orderedNames(PromptOutputModule.entries, selection.suppressedModules)
        val selector = buildString {
            append(SELECTOR_START)
            append("p=").append(selection.primary.name)
            append(" o=").append(outputs)
            append(" r=").append(risks)
            append(" x=").append(suppressed)
            append(']')
        }
        require(selector.length <= MAX_SELECTOR_CHARS) {
            "ETD 选择码超过 $MAX_SELECTOR_CHARS 字符: ${selector.length}"
        }
        return selector
    }

    fun decorateMessages(
        messages: List<AbstractApiMessage>,
        queryIntentNameResolver: (String) -> String? = { null },
    ): List<AbstractApiMessage> {
        val recentUserTexts = ArrayDeque<String>(2)

        return messages.map { message ->
            if (!message.role.equals("user", ignoreCase = true)) return@map message

            val rawText = extractRawText(message)
            val selection = SystemPromptRouter.select(
                currentText = rawText,
                recentUserTexts = recentUserTexts.toList(),
                attachmentNames = extractAttachmentNames(message),
                attachmentMimeTypes = extractAttachmentMimeTypes(message),
                queryIntentName = queryIntentNameResolver(rawText),
            )
            val decorated = appendSelector(message, buildSelector(selection))
            if (rawText.isNotBlank()) {
                if (recentUserTexts.size == 2) recentUserTexts.removeFirst()
                recentUserTexts.addLast(rawText)
            }
            decorated
        }
    }

    internal fun stripTrailingSelector(text: String): String {
        val trimmedEnd = text.trimEnd()
        val match = trailingSelectorRegex.find(trimmedEnd) ?: return text
        return trimmedEnd.removeRange(match.range).trimEnd()
    }

    private fun appendSelector(message: AbstractApiMessage, selector: String): AbstractApiMessage = when (message) {
        is SimpleTextApiMessage -> message.copy(content = appendSelector(message.content, selector))
        is PartsApiMessage -> {
            val parts = message.parts.toMutableList()
            val lastTextIndex = parts.indexOfLast { it is ApiContentPart.Text }
            if (lastTextIndex >= 0) {
                val textPart = parts[lastTextIndex] as ApiContentPart.Text
                parts[lastTextIndex] = textPart.copy(text = appendSelector(textPart.text, selector))
            } else {
                parts += ApiContentPart.Text(selector)
            }
            message.copy(parts = parts)
        }
    }

    private fun appendSelector(text: String, selector: String): String {
        val rawText = stripTrailingSelector(text)
        return if (rawText.isBlank()) selector else "$rawText\n\n$selector"
    }

    private fun extractRawText(message: AbstractApiMessage): String = when (message) {
        is SimpleTextApiMessage -> stripTrailingSelector(message.content)
        is PartsApiMessage -> message.parts
            .filterIsInstance<ApiContentPart.Text>()
            .joinToString("\n") { stripTrailingSelector(it.text) }
            .trim()
    }

    private fun extractAttachmentMimeTypes(message: AbstractApiMessage): List<String> = when (message) {
        is SimpleTextApiMessage -> emptyList()
        is PartsApiMessage -> message.parts.mapNotNull { part ->
            when (part) {
                is ApiContentPart.FileUri -> part.mimeType
                is ApiContentPart.InlineData -> part.mimeType
                is ApiContentPart.Text -> null
            }
        }
    }

    private fun extractAttachmentNames(message: AbstractApiMessage): List<String> = when (message) {
        is SimpleTextApiMessage -> emptyList()
        is PartsApiMessage -> message.parts.mapNotNull { part ->
            when (part) {
                is ApiContentPart.FileUri -> part.uri.substringAfterLast('/').substringAfterLast('\\').takeIf(String::isNotBlank)
                is ApiContentPart.InlineData -> part.mimeType.substringAfterLast('|').takeIf { '.' in it }
                is ApiContentPart.Text -> null
            }
        }
    }

    private fun <T : Enum<T>> orderedNames(order: List<T>, selected: Set<T>): String =
        order.asSequence()
            .filter(selected::contains)
            .joinToString(",") { it.name }
            .ifEmpty { "NONE" }
}
