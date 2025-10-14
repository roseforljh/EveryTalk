package com.example.everytalk.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.Message
import dev.jeziellago.compose.markdowntext.MarkdownText

/**
 * 预处理：保护三连反引号/波浪号围栏，避免后续内联反引号清理误伤代码围栏
 * 思路：
 * - 先把 ``` 和 ~~~ 临时替换为私用区占位符
 * - 再调用现有的 sanitizeAiOutput + removeInlineCodeBackticks
 * - 最后把占位符还原为原始围栏
 */
private fun fenceSafePreprocess(raw: String): String {
   if (raw.isEmpty()) return raw

   // 使用 Unicode 私用区字符作为占位，避免与正文冲突
   val backtickFencePlaceholder = "\uE000\uE001\uE000"
   val tildeFencePlaceholder = "\uE000\uE001\uE001"

   var tmp = raw
     .replace("```", backtickFencePlaceholder)
     .replace("~~~", tildeFencePlaceholder)

   // 保持原有清理顺序：先 sanitize 再移除内联反引号
   tmp = removeInlineCodeBackticks(sanitizeAiOutput(tmp))

   return tmp
     .replace(backtickFencePlaceholder, "```")
     .replace(tildeFencePlaceholder, "~~~")
}
@Composable
fun EnhancedMarkdownText(
    message: Message,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    messageOutputType: String = "",
    inTableContext: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    inSelectionDialog: Boolean = false
) {
    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }

    // 轻量清理 + 去除内联代码反引号，避免库的默认高亮底色
    val  processed  =  remember(message.text)  {  fenceSafePreprocess(message.text)  }

    // 🎯 关键修复：使用 derivedStateOf 来稳定解析结果
    // 流式输出时，只在文本有实质性变化时才重新解析，避免频繁重组
    val parts by remember(processed) {
        derivedStateOf {
            // 对于流式输出的表格等复杂内容，延迟完整解析直到内容稳定
            if (isStreaming && processed.contains("|") && processed.count { it == '\n' } < 3) {
                // 表格开始但还不完整时，暂时显示为纯文本，避免频繁重解析
                listOf(ContentPart(ContentType.TEXT, processed))
            } else {
                parseMessageContentRobust(processed)
            }
        }
    }

    // 使用分段渲染：普通文本交给 MarkdownText，代码块用自定义 CodeBlock（深色样式、避免"大白块"）
    Column(modifier = modifier.fillMaxWidth()) {
        parts.forEachIndexed { index, part ->
            val prevType = if (index > 0) parts[index - 1].type else null
            val nextType = if (index < parts.lastIndex) parts[index + 1].type else null
            
            when (part.type) {
                ContentType.TEXT -> {
                    val topPadding = if (prevType == ContentType.CODE) 12.dp else 0.dp
                    val bottomPadding = if (nextType == ContentType.CODE) 12.dp else 0.dp
                    
                    Box(modifier = Modifier.padding(top = topPadding, bottom = bottomPadding)) {
                        MarkdownText(
                            markdown = part.content,
                            style = style.copy(
                                color = textColor,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }
                }
                ContentType.CODE -> {
                    val topPadding = when (prevType) {
                        ContentType.CODE -> 16.dp
                        ContentType.TEXT -> 12.dp
                        null -> 0.dp
                    }
                    val bottomPadding = when (nextType) {
                        ContentType.CODE -> 0.dp
                        ContentType.TEXT -> 12.dp
                        null -> 0.dp
                    }
                    
                    CodeBlock(
                        code = part.content,
                        language = part.metadata,
                        textColor = textColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = topPadding, bottom = bottomPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun StableMarkdownText(
    markdown: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val cleaned = remember(markdown) {
        removeInlineCodeBackticks(sanitizeAiOutput(markdown))
    }
    
    // 🎯 同样使用 derivedStateOf 来稳定解析
    val partsStable by remember(cleaned) {
        derivedStateOf {
            parseMessageContent(cleaned)
        }
    }

    // 稳定版本也采用分段渲染，确保代码块使用自定义深色样式，避免"大白块"
    Column(modifier = modifier.fillMaxWidth()) {
        partsStable.forEachIndexed { index, part ->
            val prevType = if (index > 0) partsStable[index - 1].type else null
            val nextType = if (index < partsStable.lastIndex) partsStable[index + 1].type else null
            
            when (part.type) {
                ContentType.TEXT -> {
                    val topPadding = if (prevType == ContentType.CODE) 12.dp else 0.dp
                    val bottomPadding = if (nextType == ContentType.CODE) 12.dp else 0.dp
                    
                    Box(modifier = Modifier.padding(top = topPadding, bottom = bottomPadding)) {
                        MarkdownText(
                            markdown = part.content,
                            style = style.copy(
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }
                }
                ContentType.CODE -> {
                    val topPadding = when (prevType) {
                        ContentType.CODE -> 16.dp
                        ContentType.TEXT -> 12.dp
                        null -> 0.dp
                    }
                    val bottomPadding = when (nextType) {
                        ContentType.CODE -> 0.dp
                        ContentType.TEXT -> 12.dp
                        null -> 0.dp
                    }
                    
                    CodeBlock(
                        code = part.content,
                        language = part.metadata,
                        textColor = style.color.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = topPadding, bottom = bottomPadding)
                    )
                }
            }
        }
    }
}

// --- robust parser to avoid "white block" by capturing indented/fenced code and rendering via CodeBlock ---
private fun parseMessageContentRobust(text: String): List<ContentPart> {
    if (text.isEmpty()) return listOf(ContentPart(ContentType.TEXT, text))

    val parts = mutableListOf<ContentPart>()
    val lines = text.split("\n")
    var i = 0

    val textBuf = StringBuilder()
    fun flushText() {
        if (textBuf.isNotEmpty()) {
            parts.add(ContentPart(ContentType.TEXT, textBuf.toString()))
            textBuf.clear()
        }
    }

    var inFence = false
    var fenceChar = '`'
    var fenceLen = 0
    var currentLang: String? = null

    fun parseFenceOpen(raw: String): Triple<Boolean, String, String> {
        val s = raw.trimStart()
        if (s.length < 3) return Triple(false, "", "")
        val ch = s[0]
        if (ch != '`' && ch != '~') return Triple(false, "", "")
        var cnt = 0
        while (cnt < s.length && s[cnt] == ch) cnt++
        if (cnt < 3) return Triple(false, "", "")
        var pos = cnt
        while (pos < s.length && s[pos].isWhitespace()) pos++
        val langStart = pos
        while (pos < s.length && !s[pos].isWhitespace()) pos++
        val lang = if (pos > langStart) s.substring(langStart, pos) else ""
        val rest = if (pos < s.length) s.substring(pos).trimStart() else ""
        fenceChar = ch
        fenceLen = cnt
        return Triple(true, lang, rest)
    }

    fun isFenceClose(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return false
        var cnt = 0
        while (cnt < t.length && t[cnt] == fenceChar) cnt++
        return cnt >= fenceLen && (cnt == t.length || t.substring(cnt).isBlank())
    }

    fun isStructureBoundary(raw: String): Boolean {
        val ts = raw.trimStart()
        if (ts.isEmpty()) return true
        if (ts.startsWith("#")) return true
        if (Regex("^([*+\\-]|\\d+[.)])\\s+").containsMatchIn(ts)) return true
        val tt = ts.trim()
        if (tt.length >= 3 && tt.all { it == '-' }) return true
        return false
    }

    while (i < lines.size) {
        val line = lines[i]

        if (!inFence) {
            // fenced code
            val (open, lang, rest) = parseFenceOpen(line)
            if (open) {
                flushText()
                currentLang = lang.ifBlank { null }
                val codeLines = mutableListOf<String>()
                if (rest.isNotBlank()) codeLines.add(rest)

                i += 1
                var blanks = 0
                inFence = true
                while (i < lines.size) {
                    val cur = lines[i]
                    val t = cur.trim()
                    if (t.isEmpty()) blanks++ else blanks = 0

                    if (isFenceClose(cur)) {
                        val content = codeLines.joinToString("\n")
                        if (content.isNotBlank()) {
                            parts.add(ContentPart(ContentType.CODE, content, currentLang))
                        }
                        inFence = false
                        currentLang = null
                        i += 1
                        break
                    }

                    if (false && (isStructureBoundary(cur) || blanks >= 2)) {
                        val content = codeLines.joinToString("\n")
                        if (content.isNotBlank()) {
                            parts.add(ContentPart(ContentType.CODE, content, currentLang))
                        }
                        inFence = false
                        currentLang = null
                        // do not consume boundary line; handle again as normal
                        break
                    }

                    codeLines.add(cur)
                    i += 1
                }

                if (inFence) {
                    // EOF without explicit close
                    val content = codeLines.joinToString("\n")
                    if (content.isNotBlank()) {
                        parts.add(ContentPart(ContentType.CODE, content, currentLang))
                    }
                    inFence = false
                    currentLang = null
                }
                continue
            }

            // indented code block (>=4 spaces or tab) → treat as CODE to avoid MarkdownText's light background
            if (line.startsWith("    ") || line.startsWith("\t")) {
                flushText()
                val codeLines = mutableListOf<String>()
                var j = i
                while (j < lines.size) {
                    val l = lines[j]
                    when {
                        l.startsWith("    ") -> {
                            codeLines.add(l.removePrefix("    "))
                            j++
                        }
                        l.startsWith("\t") -> {
                            codeLines.add(l.removePrefix("\t"))
                            j++
                        }
                        l.isBlank() -> {
                            codeLines.add("")
                            j++
                        }
                        else -> break
                    }
                }
                val content = codeLines.joinToString("\n").trimEnd()
                if (content.isNotBlank()) {
                    parts.add(ContentPart(ContentType.CODE, content, null))
                }
                i = j
                continue
            }

            // normal text
            if (textBuf.isNotEmpty()) textBuf.append('\n')
            textBuf.append(line)
            i += 1
        } else {
            // safety; should be handled inside fence loop
            i += 1
        }
    }

    flushText()
    if (parts.isEmpty()) parts.add(ContentPart(ContentType.TEXT, text))
    return parts
}
