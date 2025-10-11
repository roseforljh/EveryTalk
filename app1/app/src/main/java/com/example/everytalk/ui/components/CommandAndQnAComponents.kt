package com.example.everytalk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.everytalk.ui.theme.chatColors

/**
 * Commands/QnA/Risks 三类结构化卡片组件（移动端友好）
 * - Commands：整体复制按钮、统一等宽字体背景、支持 Markdown 渲染（含 ```bash 块）
 * - QnA：按 “Prompt/Answer/Reason” 分段显示，窄屏上下堆叠
 * - Risks：强调色边框与标题，适合放置危险操作与不可逆提示
 */

@Composable
fun CommandListCard(
    content: String,
    modifier: Modifier = Modifier,
    title: String = "Commands"
) {
    val shape = RoundedCornerShape(10.dp)
    val clipboard = LocalClipboardManager.current
    val bg = MaterialTheme.chatColors.codeBlockBackground
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, outline, shape),
        shape = shape,
        color = Color.Transparent
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(extractCopyPayloadForCommands(content)))
                    }
                ) {
                    Text(text = "复制")
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg, shape)
                    .padding(10.dp)
            ) {
                // 纯文本渲染（下线 Markdown 渲染）
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun QnAListCard(
    content: String,
    modifier: Modifier = Modifier,
    title: String = "QnA"
) {
    val shape = RoundedCornerShape(10.dp)
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)

    val items = remember(content) { parseQnA(content) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, outline, shape),
        shape = shape,
        color = Color.Transparent
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))

            items.forEachIndexed { idx, triple ->
                val (prompt, answer, reason) = triple
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(10.dp)
                ) {
                    Text("Prompt: $prompt", style = MaterialTheme.typography.bodyMedium)
                    if (answer.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Answer: $answer", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (reason.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Reason: $reason", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (idx != items.lastIndex) {
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
fun RiskAlert(
    content: String,
    modifier: Modifier = Modifier,
    title: String = "Risks"
) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor = MaterialTheme.colorScheme.error
    val titleColor = MaterialTheme.colorScheme.error

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, shape),
        shape = shape,
        color = Color.Transparent
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                color = titleColor,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 复制命令时的净化策略：
 * - 若含 ```fenced code```，优先提取围栏内文本并拼接（多围栏用空行分隔）
 * - 否则返回原文（可能是行内命令）
 */
private fun extractCopyPayloadForCommands(content: String): String {
    val fence = Regex("(?s)```\\s*([a-zA-Z0-9_+\\-#.]*)[ \\t]*\\r?\\n?([\\s\\S]*?)\\r?\\n?```")
    val matches = fence.findAll(content).toList()
    if (matches.isEmpty()) return content.trim()
    return matches.joinToString(separator = "\n\n") { it.groups[2]?.value?.trimEnd() ?: "" }.trim()
}

/**
 * 简单 QnA 解析器：
 * 输入可以是以下形式（行内或多行均可）：
 *   Prompt: ...
 *   Answer: ...
 *   Reason: ...
 * 空字段可省略；相邻的 Prompt/Answer/Reason 组合视为一个 QnA 项。
 */
private fun parseQnA(content: String): List<Triple<String, String, String>> {
    val lines = content.lines()
    val res = mutableListOf<Triple<String, String, String>>()
    var p = StringBuilder()
    var a = StringBuilder()
    var r = StringBuilder()

    fun flushIfAny() {
        if (p.isNotEmpty() || a.isNotEmpty() || r.isNotEmpty()) {
            res += Triple(p.toString().trim(), a.toString().trim(), r.toString().trim())
            p = StringBuilder(); a = StringBuilder(); r = StringBuilder()
        }
    }

    for (raw in lines) {
        val line = raw.trim()
        when {
            line.startsWith("Prompt:", ignoreCase = true) -> {
                flushIfAny()
                p.append(line.removePrefix("Prompt:").trim())
            }
            line.startsWith("Answer:", ignoreCase = true) -> {
                a.append(line.removePrefix("Answer:").trim())
            }
            line.startsWith("Reason:", ignoreCase = true) -> {
                r.append(line.removePrefix("Reason:").trim())
            }
            else -> {
                // 连续内容归并到最近的非空段
                when {
                    r.isNotEmpty() -> if (line.isNotEmpty()) r.appendLine(line) else r.appendLine()
                    a.isNotEmpty() -> if (line.isNotEmpty()) a.appendLine(line) else a.appendLine()
                    p.isNotEmpty() -> if (line.isNotEmpty()) p.appendLine(line) else p.appendLine()
                    else -> { /* skip leading noise */ }
                }
            }
        }
    }
    flushIfAny()
    return res.ifEmpty { listOf(Triple(content.trim(), "", "")) }
}
