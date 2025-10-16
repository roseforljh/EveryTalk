package com.example.everytalk.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.ui.theme.chatColors
import com.example.everytalk.ui.theme.ChatDimensions

/**
 * 优化的文本布局组件 - 支持Markdown渲染但不处理数学公式
 * 
 * 主要功能:
 * 1. 完整的Markdown格式支持
 * 2. 代码块语法高亮
 * 3. 表格、列表、标题等格式
 * 4. 紧凑的布局设计
 * 5. 响应式字体大小
 */
@Composable
fun OptimizedTextLayout(
    message: Message,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge
) {
    val optimizedTextStyle = remember(style) {
        style.copy(
            lineHeight = (style.fontSize.value * 1.3f).sp,
            letterSpacing = (-0.1).sp,
            fontWeight = FontWeight.Normal
        )
    }
    
    // 直接显示原始文本，完全跳过任何处理
    Text(
        text = message.text,
        style = optimizedTextStyle.copy(color = textColor),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        textAlign = TextAlign.Start
    )
}

/**
 * 代码块组件（自定义样式）
 * - 固定高度（可调），大圆角
 * - 支持水平/垂直双向滚动
 * - 顶部右侧“复制”按钮
 * - 适配明暗主题
 */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    textColor: Color,
    modifier: Modifier = Modifier,
    maxHeight: Int = 300,
    cornerRadius: Int = 10
) {
    val isDark = isSystemInDarkTheme()
    val codeColor = if (isDark) Color(0xFFD4D4D4) else Color(0xFF24292F)
    val topBarColor = if (isDark) Color.White else Color(0xFF24292F)

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp, max = maxHeight.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(vScroll)
                .padding(start = 4.dp, end = 4.dp, top = 32.dp, bottom = 4.dp)
        ) {
            SelectionContainer {
                Row(modifier = Modifier.horizontalScroll(hScroll)) {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = codeColor,
                            letterSpacing = 0.sp
                        )
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val langText = language?.takeIf { it.isNotBlank() } ?: "code"
            Text(
                text = langText.lowercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = topBarColor,
                    letterSpacing = 0.3.sp
                )
            )

            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    Toast.makeText(ctx, "代码已复制", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "复制代码",
                    tint = topBarColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * 内容类型枚举 - 只保留文本和代码，不包含数学公式
 */
enum class ContentType {
    TEXT,
    CODE
}

/**
 * 内容部分数据类
 */
data class ContentPart(
    val type: ContentType,
    val content: String,
    val metadata: String? = null
)

/**
 * 启发式判断：该“代码块”内容是否应降级为文本
 * 典型触发场景：
 * - 语言标注为 text/plaintext/md/markdown/code，但内容是中文段落、列表或标题
 * - 内容很短（<80 字且行数 ≤ 3）且不含明显代码指示符
 */
private fun shouldDowngradeCodeBlock(content: String, lang: String?): Boolean {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return true

    val lc = (lang ?: "").lowercase()
    val langSuggestsText = lc in setOf("text", "plaintext", "plain", "md", "markdown", "code")

    val lines = trimmed.split('\n')
    val lineCount = lines.size

    // 代码指示符（出现其一基本可视为代码）
    val codeIndicators = listOf(
        "{", "}", "();", "()", ";", "::", "==", "!=", ">=", "<=", "/*", "*/",
        "var ", "let ", "const ", "function ", "def ", "class ", "import ", "export ",
        "#include", "#!/bin", "<html", "</", "<html", "SELECT ", "INSERT ", "curl ",
        "sudo ", "pip install", "npm install", " fmt.", " println", " System.out",
        "return ", "if (", "for (", "while (", "try {", "except ", " catch "
    )
    val codeIndicatorCount = codeIndicators.count { indicator ->
        trimmed.contains(indicator, ignoreCase = true)
    }
    val hasCodeIndicator = codeIndicatorCount > 0

    // 自然语言/Markdown 的特征
    val prosePuncts = listOf('。', '，', '：', '；', '？', '！', '、')
    val hasProsePunct = trimmed.any { it in prosePuncts }
    val looksLikeList = Regex("^\\s*([*+\\-]|\\d+[.)])\\s+", RegexOption.MULTILINE).containsMatchIn(trimmed)
    val looksLikeHeading = Regex("^\\s*#{1,6}\\s+.+$", RegexOption.MULTILINE).containsMatchIn(trimmed)
    val looksLikeMd = looksLikeList || looksLikeHeading

    val chineseRatio = run {
        val c = trimmed.count { it.code in 0x4E00..0x9FFF }
        if (trimmed.isEmpty()) 0.0 else c.toDouble() / trimmed.length
    }

    // 每行“代码符号密度”
    val symbolChars = setOf('{','}',';','(',')','[',']','<','>','=','+','-','*','/','\\','|','&','%','@','#','~','^','`',':')
    val codeishLines = lines.count { line ->
        // 含中文的行大概率是说明文本，直接视为非代码行
        val hasCjk = line.any { ch -> ch.code in 0x4E00..0x9FFF }
        if (hasCjk) return@count false
        val nonSpace = line.count { !it.isWhitespace() }.coerceAtLeast(1)
        val sym = line.count { it in symbolChars }
        // 符号密度较高且不以 Markdown 列表/标题开头才认为是代码行
        val notMarkdownLead = !Regex("^\\s*([*+\\-]|\\d+[.)]|#{1,6})\\b").containsMatchIn(line)
        notMarkdownLead && (sym.toDouble() / nonSpace) > 0.25
    }
    val proseLikeLines = lines.count { line ->
        line.any { it in prosePuncts } || Regex("^\\s*([*+\\-]|\\d+[.)]|#{1,6})\\s+").containsMatchIn(line)
    }
    val headingCount = lines.count { Regex("^\\s*#{1,6}\\s+").containsMatchIn(it) }
    val listCount = lines.count { Regex("^\\s*([*+\\-]|\\d+[.)])\\s+").containsMatchIn(it) }
    val firstNonEmptyIsHeading = lines.firstOrNull { it.trim().isNotEmpty() }
        ?.let { Regex("^\\s*#{1,6}\\s+").containsMatchIn(it) } ?: false

    val veryShortAndSimple = trimmed.length < 80 && lineCount <= 3 && !hasCodeIndicator

    // 强化降级策略：
    // 1) 语言暗示为文本；
    // 2) 很短且无代码特征；
    // 3) 未声明语言或语言为空，且：
    //    - 中文占比高(>=0.2) 或 大量 Markdown 结构(≥60%)，
    //    - 且代码行很少(≤20%)、代码指示符稀少(≤2)。
    val majorityProse = proseLikeLines >= kotlin.math.max(3, (lineCount * 0.6).toInt())
    val fewCodeish = codeishLines <= (lineCount * 0.2).toInt()
    val sparseIndicators = codeIndicatorCount <= 2
    val noLang = lc.isEmpty()

    return when {
        langSuggestsText -> true
        veryShortAndSimple -> true
        // 多行 Markdown 标题/列表或首行就是标题，且代码特征很弱
        (headingCount >= 2 || listCount >= 2 || firstNonEmptyIsHeading) && fewCodeish -> true
        // 无语言且呈现明显“中文/Markdown”特征，且代码痕迹稀少
        noLang && (chineseRatio >= 0.2 || majorityProse) && fewCodeish && sparseIndicators -> true
        else -> false
    }
}

/**
 * 解析消息内容，分离文本和代码块
 */
fun parseMessageContent(text: String): List<ContentPart> {
    // 防御：对于超大文本（>500KB），直接返回纯文本，避免复杂解析导致OOM
    if (text.length > 500_000) {
        return listOf(ContentPart(ContentType.TEXT, text))
    }
    
    // 更鲁棒的围栏扫描器，修复"代码块大白块"根因：
    // - MarkdownText 对围栏代码使用浅色背景；我们用自定义 CodeBlock 深色样式替代
    // - 之前用单个正则无法识别：缩进围栏、列表内围栏、~~~ 围栏、开行携带内联内容、缺失闭合围栏等
    // - 这里用逐行扫描，支持 ``` 与 ~~~，支持任意 Unicode 语言标签，兼容开行尾部"内联代码"迁移到下一行
    val parts = mutableListOf<ContentPart>()
    val lines = text.split("\n")
    val textBuf = StringBuilder()

    var i = 0
    var inFence = false
    var fenceChar: Char = '`'
    var fenceLen = 0
    var currentLang: String? = null
    val fenceOpen = Regex("""^\s*([`~]{3,})\s*([^\s`~]*)\s*(.*)$""") // group1=fence, group2=lang?, group3=rest

    fun isFenceClose(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        if (fenceChar == '`') {
            // 至少 fenceLen 个 `
            if (t.first() != '`') return false
            var cnt = 0
            for (ch in t) {
                if (ch == '`') cnt++ else break
            }
            return cnt >= fenceLen
        } else {
            if (t.first() != '~') return false
            var cnt = 0
            for (ch in t) {
                if (ch == '~') cnt++ else break
            }
            return cnt >= fenceLen
        }
    }

    fun flushText() {
        if (textBuf.isNotEmpty()) {
            parts.add(ContentPart(ContentType.TEXT, textBuf.toString()))
            textBuf.clear()
        }
    }

    // ===== 无围栏场景：启发式连续代码段聚合 =====
    val codeBuf = StringBuilder()
    var codeRun = 0

    fun flushHeuristicCode() {
        if (codeRun >= 2 && codeBuf.isNotBlank()) {
            parts.add(ContentPart(ContentType.CODE, codeBuf.toString(), null))
        } else if (codeBuf.isNotBlank()) {
            if (textBuf.isNotEmpty()) textBuf.append('\n')
            textBuf.append(codeBuf)
        }
        codeBuf.clear()
        codeRun = 0
    }

    fun isCodeLikeLine(raw: String): Boolean {
        val s = raw.trimEnd()
        if (s.isEmpty()) return false

        // 首先跳过 Markdown 标题/列表（这些应该走文本）
        if (Regex("^\\s*([*+\\-]|\\d+[.)]|#{1,6})\\s+").containsMatchIn(s)) return false

        // 中文比例高的说明性文字，不判为代码
        val cjk = s.count { it.code in 0x4E00..0x9FFF }
        val ratio = cjk.toDouble() / s.length.coerceAtLeast(1)
        if (ratio >= 0.15) return false

        // 典型代码关键字/模式
        val patterns = listOf(
            "def ", "class ", "import ", "from ", "return ", "try:", "except ",
            "func ", "package ", "var ", "let ", "const ",
            "public ", "private ", "static ",
            "if (", "for (", "while (", "switch (", "case ", "break;", "continue;",
            "#include", "#!/bin", "SELECT ", "INSERT ", "UPDATE ", "DELETE ",
            "</", "<html", "fmt.", "System.out", "console.log", "::", "->", "=>"
        )
        val hasKeyword = patterns.any { s.contains(it, ignoreCase = true) }

        // 符号密度
        val symbolChars = setOf('{','}',';','(',')','[',']','<','>','=','+','-','*','/','\\','|','&','%','@','#','~','^','`',':','.',',')
        val nonSpace = s.count { !it.isWhitespace() }.coerceAtLeast(1)
        val sym = s.count { it in symbolChars }
        val symbolDense = sym.toDouble() / nonSpace > 0.28

        // 行首缩进
        val leadingSpaces = s.takeWhile { it == ' ' || it == '\t' }.length
        val hasIndent = leadingSpaces >= 2

        return hasKeyword || symbolDense || hasIndent
    }

    while (i < lines.size) {
        val line = lines[i]
        if (!inFence) {
            val m = fenceOpen.find(line)
            if (m != null) {
                // 命中开围栏：先输出之前累积的文本
                flushText()

                val fenceToken = m.groupValues[1]
                fenceChar = fenceToken.first()
                fenceLen = fenceToken.length
                currentLang = m.groupValues[2].takeIf { it.isNotBlank() }
                val rest = m.groupValues[3].trimStart()

                val codeLines = mutableListOf<String>()
                if (rest.isNotEmpty()) codeLines.add(rest)

                // 收集直到闭围栏或 EOF
                i += 1
                while (i < lines.size) {
                    val cur = lines[i]
                    if (isFenceClose(cur)) {
                        // 完成一个代码块 - 只有当有实际内容时才添加
                        val codeContent = codeLines.joinToString("\n")
                        if (codeContent.isNotBlank()) {
                            if (shouldDowngradeCodeBlock(codeContent, currentLang)) {
                                parts.add(ContentPart(ContentType.TEXT, codeContent))
                            } else {
                                parts.add(ContentPart(ContentType.CODE, codeContent, currentLang))
                            }
                        }
                        inFence = false
                        currentLang = null
                        break
                    } else {
                        codeLines.add(cur)
                    }
                    i += 1
                }

                // EOF 未闭合：按代码块处理 - 只有当有实际内容时才添加
                if (i >= lines.size) {
                    val codeContent = codeLines.joinToString("\n")
                    if (codeContent.isNotBlank()) {
                        if (shouldDowngradeCodeBlock(codeContent, currentLang)) {
                            parts.add(ContentPart(ContentType.TEXT, codeContent))
                        } else {
                            parts.add(ContentPart(ContentType.CODE, codeContent, currentLang))
                        }
                    }
                    inFence = false
                    currentLang = null
                }
                // 继续外层 while（此时 i 指向闭围栏行或 EOF；外层循环自增）
            } else {
                // 无围栏：尝试按连续“代码样式行”聚合为代码块
                val codeLike = isCodeLikeLine(line)
                if (codeLike) {
                    if (codeRun == 0) {
                        // 把前面文本先落盘
                        flushText()
                        codeBuf.append(line)
                        codeRun = 1
                    } else {
                        codeBuf.append('\n').append(line)
                        codeRun += 1
                    }
                } else {
                    // 当前是非代码行：若之前在累计代码，则尝试落代码块
                    if (codeRun > 0) {
                        flushHeuristicCode()
                    }
                    if (textBuf.isNotEmpty()) textBuf.append('\n')
                    textBuf.append(line)
                }
                i += 1
            }
        } else {
            // 理论上不会到达，inFence 在内部循环已处理
            i += 1
        }
    }

    // 尾部刷新：先尝试落启发式代码，再落文本
    if (codeRun > 0) {
        flushHeuristicCode()
    }
    // 尾部残留文本
    flushText()

    if (parts.isEmpty()) {
        parts.add(ContentPart(ContentType.TEXT, text))
    }
    return parts
}

/**
 * 匹配项数据类
 */
private data class MatchItem(
    val range: IntRange,
    val type: ContentType,
    val content: String,
    val metadata: String? = null
)

// --- Anti-white-block: robust fenced code parser with early-close heuristics ---
// Reason:
// In streaming or imperfect Markdown, a starting fence (```/~~~) may appear without a proper
// closing fence. Many Markdown renderers then treat a huge following region as a single
// code block, producing a large light/white rectangle. We fix this by:
// - Detecting structural boundaries (headings/lists/---) or two consecutive blank lines;
// - If encountered before an explicit close fence, we early-close the code block;
// - We also keep the existing downgrade-to-text heuristic for prose-like "code".
private fun parseMessageContentRobust(text: String): List<ContentPart> {
    if (text.length > 500_000) return listOf(ContentPart(ContentType.TEXT, text))

    val parts = mutableListOf<ContentPart>()
    val lines = text.split("\n")
    val textBuf = StringBuilder()

    var i = 0
    var inFence = false
    var fenceChar = '`'
    var fenceLen = 0
    var currentLang: String? = null

    val fenceOpen = Regex("^\\s*([`~]{3,})\\s*([^\\s`~]*)\\s*(.*)$")

    fun flushText() {
        if (textBuf.isNotEmpty()) {
            parts.add(ContentPart(ContentType.TEXT, textBuf.toString()))
            textBuf.clear()
        }
    }

    fun isStructureBoundary(raw: String): Boolean {
        val ts = raw.trimStart()
        if (ts.isEmpty()) return true
        if (ts.startsWith("#")) return true
        if ((ts.startsWith("-") || ts.startsWith("*") || ts.startsWith("+")) &&
            (ts.length == 1 || ts[1].isWhitespace())
        ) return true
        var k = 0
        var hasDigit = false
        while (k < ts.length && ts[k].isDigit()) { hasDigit = true; k++ }
        if (hasDigit && k < ts.length &&
            (ts[k] == '.' || ts[k] == ')') &&
            (k + 1 < ts.length && ts[k + 1].isWhitespace())
        ) return true
        val tt = ts.trim()
        if (tt.length >= 3 && tt.all { it == '-' }) return true
        return false
    }

    while (i < lines.size) {
        val line = lines[i]
        if (!inFence) {
            val m = fenceOpen.find(line)
            if (m != null) {
                flushText()
                val token = m.groupValues[1]
                fenceChar = token.first()
                fenceLen = token.length
                currentLang = m.groupValues[2].takeIf { it.isNotBlank() }
                val rest = m.groupValues[3].trimStart()

                val codeLines = mutableListOf<String>()
                if (rest.isNotEmpty()) codeLines.add(rest)

                // Collect until explicit close, or early-close on structure boundary / two blanks
                i += 1
                var consecutiveBlanks = 0
                while (i < lines.size) {
                    val cur = lines[i]
                    val t = cur.trim()
                    if (t.isEmpty()) consecutiveBlanks++ else consecutiveBlanks = 0

                    // explicit close?
                    val explicitClose = run {
                        if (fenceChar == '`') {
                            val head = "`".repeat(fenceLen)
                            t.startsWith(head) && (t.length == head.length || t.substring(head.length).isBlank())
                        } else {
                            val head = "~".repeat(fenceLen)
                            t.startsWith(head) && (t.length == head.length || t.substring(head.length).isBlank())
                        }
                    }
                    if (explicitClose) {
                        val codeContent = codeLines.joinToString("\n")
                        if (codeContent.isNotBlank()) {
                            if (shouldDowngradeCodeBlock(codeContent, currentLang)) {
                                parts.add(ContentPart(ContentType.TEXT, codeContent))
                            } else {
                                parts.add(ContentPart(ContentType.CODE, codeContent, currentLang))
                            }
                        }
                        inFence = false
                        currentLang = null
                        break
                    }

                    // early-close heuristics
                    if (isStructureBoundary(cur) || consecutiveBlanks >= 2) {
                        val codeContent = codeLines.joinToString("\n")
                        if (codeContent.isNotBlank()) {
                            if (shouldDowngradeCodeBlock(codeContent, currentLang)) {
                                parts.add(ContentPart(ContentType.TEXT, codeContent))
                            } else {
                                parts.add(ContentPart(ContentType.CODE, codeContent, currentLang))
                            }
                        }
                        inFence = false
                        currentLang = null
                        // DO NOT consume this boundary line; process again as normal text
                        // (i unchanged)
                        break
                    }

                    codeLines.add(cur)
                    i += 1
                }

                // EOF without explicit close: still finalize as a code block (or downgrade)
                if (inFence) {
                    val codeContent = codeLines.joinToString("\n")
                    if (codeContent.isNotBlank()) {
                        if (shouldDowngradeCodeBlock(codeContent, currentLang)) {
                            parts.add(ContentPart(ContentType.TEXT, codeContent))
                        } else {
                            parts.add(ContentPart(ContentType.CODE, codeContent, currentLang))
                        }
                    }
                    inFence = false
                    currentLang = null
                }
            } else {
                if (textBuf.isNotEmpty()) textBuf.append('\n')
                textBuf.append(line)
                i += 1
            }
        } else {
            // Should not happen; inFence loop consumes until break.
            i += 1
        }
    }

    flushText()
    if (parts.isEmpty()) parts.add(ContentPart(ContentType.TEXT, text))
    return parts
}

// NOTE:
// Replace the original call site to use parseMessageContentRobust(...) instead of parseMessageContent(...)
// to enable early-close behavior and eliminate large white blocks created by unterminated fences.
