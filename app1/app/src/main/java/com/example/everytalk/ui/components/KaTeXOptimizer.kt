package com.example.everytalk.ui.components

import java.net.URLEncoder

/**
 * KaTeX渲染优化器 - 解决双重渲染和性能问题
 * 
 * 核心优化策略：
 * 1. 单次渲染：避免renderMathInElement和katex.render重复执行
 * 2. 批量处理：合并多个公式的渲染操作
 * 3. 缓存机制：避免重复计算相同公式
 * 4. 延迟加载：大型公式分批渲染
 */
object KaTeXOptimizer {
    
    /**
     * 生成优化的KaTeX HTML模板
     */
    fun createOptimizedMathHtml(
        content: String,
        textColor: String,
        backgroundColor: String,
        fontSize: Float,
        containsTables: Boolean = false
    ): String {
        
        val mathSegments = extractMathSegments(content)
        val processedContent = if (mathSegments.isNotEmpty()) {
            createBatchProcessedContent(content, mathSegments)
        } else {
            processTextToHtml(content)
        }
        
        return buildOptimizedHtmlTemplate(
            processedContent,
            textColor,
            backgroundColor,
            fontSize,
            containsTables
        )
    }
    
    /**
     * 提取数学公式片段
     */
    private fun extractMathSegments(content: String): List<MathSegment> {
        val segments = mutableListOf<MathSegment>()
        val patterns = listOf(
            Regex("\\$\\$([^$]+)\\$\\$") to true,  // 显示模式
            Regex("\\$([^$]+)\\$") to false,       // 行内模式
            Regex("\\\\\\[([^\\]]+)\\\\\\]") to true, // LaTeX显示
            Regex("\\\\\\(([^\\)]+)\\\\\\)") to false  // LaTeX行内
        )
        
        patterns.forEach { (pattern, isDisplay) ->
            pattern.findAll(content).forEach { match ->
                segments.add(MathSegment(
                    original = match.value,
                    latex = match.groupValues[1],
                    isDisplay = isDisplay,
                    start = match.range.first,
                    end = match.range.last
                ))
            }
        }
        
        return segments.sortedBy { it.start }
    }
    
    /**
     * 创建批量处理的内容
     */
    private fun createBatchProcessedContent(
        content: String,
        segments: List<MathSegment>
    ): String {
        if (segments.isEmpty()) return processTextToHtml(content)
        
        val result = StringBuilder()
        var lastEnd = 0
        
        segments.forEach { segment ->
            // 添加非数学文本
            if (segment.start > lastEnd) {
                result.append(processTextToHtml(
                    content.substring(lastEnd, segment.start)
                ))
            }
            
            // 添加数学占位符
            val mathId = "math_${segments.indexOf(segment)}"
            result.append("<span class=\"math-placeholder\" data-math-id=\"$mathId\" data-latex=\"${
                URLEncoder.encode(segment.latex, "UTF-8")
            }\" data-display=\"${segment.isDisplay}\"></span>")
            
            lastEnd = segment.end + 1
        }
        
        // 添加剩余文本
        if (lastEnd < content.length) {
            result.append(processTextToHtml(content.substring(lastEnd)))
        }
        
        return result.toString()
    }
    
    /**
     * 构建优化的HTML模板
     */
    private fun buildOptimizedHtmlTemplate(
        processedContent: String,
        textColor: String,
        backgroundColor: String,
        fontSize: Float,
        containsTables: Boolean
    ): String {
        val tableStyles = if (containsTables) {
            """
            table { 
                border-collapse: collapse; 
                width: 100%; 
                margin: 1em 0;
                table-layout: auto;
            }
            th, td { 
                border: 1px solid $textColor; 
                padding: 8px; 
                text-align: left;
                word-wrap: break-word;
                max-width: 200px;
            }
            th { 
                background-color: rgba(127, 127, 127, 0.1);
                font-weight: bold;
            }
            """
        } else ""
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                <link rel="stylesheet" href="file:///android_asset/katex.min.css">
                <script src="file:///android_asset/katex.min.js"></script>
                <style>
                    :root { color-scheme: light dark; }
                    * {
                        -webkit-user-select: none;
                        -moz-user-select: none;
                        -ms-user-select: none;
                        user-select: none;
                        -webkit-touch-callout: none;
                        -webkit-tap-highlight-color: transparent;
                    }
                    html, body {
                        margin: 0;
                        padding: 12px;
                        background-color: $backgroundColor;
                        color: $textColor;
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        font-size: ${fontSize}px;
                        line-height: 1.6;
                        word-wrap: break-word;
                        overflow-wrap: break-word;
                    }
                    
                    /* KaTeX优化样式 */
                    .math-placeholder {
                        display: inline-block;
                        min-height: 1.2em;
                        background: transparent;
                    }
                    .katex {
                        color: $textColor !important;
                        background: transparent !important;
                        font-size: inherit !important;
                        line-height: 1.28;
                    }
                    .katex * {
                        background: transparent !important;
                        color: inherit !important;
                    }
                    .katex-display {
                        margin: 1.2em 0;
                        text-align: left;
                        overflow-x: auto;
                        -webkit-overflow-scrolling: touch;
                    }
                    
                    /* 基础文本样式 */
                    pre {
                        background-color: rgba(127, 127, 127, 0.1);
                        padding: 1em;
                        border-radius: 8px;
                        white-space: pre-wrap;
                        word-wrap: break-word;
                        overflow-x: auto;
                    }
                    code {
                        font-family: 'Consolas', 'Monaco', monospace;
                        background-color: rgba(127, 127, 127, 0.15);
                        padding: 2px 4px;
                        border-radius: 3px;
                    }
                    pre code {
                        background: transparent;
                        padding: 0;
                    }
                    
                    $tableStyles
                    
                    /* 性能优化 */
                    .math-placeholder[data-rendered="true"] {
                        visibility: visible;
                    }
                    .math-placeholder[data-rendered="false"] {
                        visibility: hidden;
                    }
                </style>
            </head>
            <body>
                <div id="content">$processedContent</div>
                <script>
                    // 优化的KaTeX渲染器
                    (function() {
                        let renderQueue = [];
                        let isRendering = false;
                        
                        function renderMathBatch() {
                            if (isRendering || renderQueue.length === 0) return;
                            
                            isRendering = true;
                            const batchSize = 3; // 每批处理3个公式
                            const batch = renderQueue.splice(0, batchSize);
                            
                            batch.forEach(element => {
                                try {
                                    const latex = decodeURIComponent(element.dataset.latex);
                                    const isDisplay = element.dataset.display === 'true';
                                    
                                    katex.render(latex, element, {
                                        displayMode: isDisplay,
                                        throwOnError: false,
                                        errorColor: '$textColor',
                                        output: 'htmlAndMathml',
                                        strict: 'ignore',
                                        minRuleThickness: 0.09
                                    });
                                    
                                    element.dataset.rendered = 'true';
                                } catch (e) {
                                    element.innerHTML = '<span style="color: red;">Math Error</span>';
                                    element.dataset.rendered = 'true';
                                }
                            });
                            
                            isRendering = false;
                            
                            // 继续处理下一批
                            if (renderQueue.length > 0) {
                                setTimeout(renderMathBatch, 16); // 下一帧
                            }
                        }
                        
                        // 初始化渲染队列
                        function initMathRendering() {
                            const placeholders = document.querySelectorAll('.math-placeholder');
                            renderQueue = Array.from(placeholders);
                            
                            // 标记所有为未渲染
                            renderQueue.forEach(el => el.dataset.rendered = 'false');
                            
                            // 开始批量渲染
                            renderMathBatch();
                        }
                        
                        // DOM加载完成后执行
                        if (document.readyState === 'loading') {
                            document.addEventListener('DOMContentLoaded', initMathRendering);
                        } else {
                            initMathRendering();
                        }
                    })();
                </script>
            </body>
            </html>
        """.trimIndent()
    }
    
    /**
     * 处理普通文本为HTML（增强版）
     * - 支持 Markdown 标题（#..######）
     * - 支持 Markdown 表格 (header | header + 分隔行)
     * - 支持围栏代码块 ``` ```
     * - 支持行内代码/加粗/斜体/基础链接
     */
    private fun processTextToHtml(text: String): String {
        val normalized = normalizeBasicMarkdown(text)
        if (normalized.isEmpty()) return ""

        fun escapeHtml(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        fun inlineFormat(escaped: String): String {
            var r = escaped
            // 行内代码
            r = r.replace(Regex("`([^`]+)`")) { mr -> "<code>${mr.groupValues[1]}</code>" }
            // 加粗/斜体（次序很重要）
            r = r.replace(Regex("\\*\\*([^*]+)\\*\\*")) { mr -> "<strong>${mr.groupValues[1]}</strong>" }
            r = r.replace(Regex("\\*([^*]+)\\*")) { mr -> "<em>${mr.groupValues[1]}</em>" }
            // 基础链接 [text](url)
            r = r.replace(Regex("\\[([^\\]]+)\\]\\(([^\\s)]+)\\)")) { mr ->
                val textPart = mr.groupValues[1]
                val href = mr.groupValues[2]
                "<a href=\"$href\">$textPart</a>"
            }
            return r
        }

        fun convertHeadingLine(raw: String): String? {
            var line = raw
            // 将全角＃转半角#
            if (line.startsWith("＃")) {
                val count = line.takeWhile { it == '＃' }.length
                line = "#".repeat(count) + line.drop(count)
            }
            val m = Regex("^(#{1,6})\\s+(.+)$").find(line) ?: return null
            val level = m.groupValues[1].length
            val content = inlineFormat(escapeHtml(m.groupValues[2].trim()))
            return "<h$level>" + content + "</h$level>"
        }

        fun splitTableCells(line: String): List<String> {
            // 去掉首尾竖线后按 | 切分
            val trimmed = line.trim().trim('|')
            return trimmed.split("|").map { it.trim() }
        }

        fun tryParseTable(start: Int, lines: List<String>): Pair<Int, String>? {
            // 寻找 header | header
            var i = start
            if (i >= lines.size) return null
            val headerLine = lines[i]
            if (!headerLine.contains("|")) return null

            // 分隔行存在性检测
            val sepIdx = i + 1
            if (sepIdx >= lines.size) return null
            val sepLine = lines[sepIdx].trim()
            val sepRegex = Regex("^\\|?\\s*:?[-]{3,}:?\\s*(\\|\\s*:?[-]{3,}:?\\s*)+\\|?$")
            if (!sepRegex.containsMatchIn(sepLine)) return null

            // 解析表头/对齐方式
            val headers = splitTableCells(headerLine)
            val aligns = splitTableCells(sepLine).map { cell ->
                val left = cell.startsWith(":")
                val right = cell.endsWith(":")
                when {
                    left && right -> "center"
                    right -> "right"
                    else -> "left"
                }
            }

            val sb = StringBuilder()
            sb.append("<table><thead><tr>")
            headers.forEachIndexed { idx, h ->
                val align = aligns.getOrNull(idx) ?: "left"
                sb.append("<th style=\"text-align: $align;\">")
                sb.append(inlineFormat(escapeHtml(h)))
                sb.append("</th>")
            }
            sb.append("</tr></thead><tbody>")

            // 数据行
            i = sepIdx + 1
            while (i < lines.size && lines[i].contains("|")) {
                val rowLine = lines[i].trim()
                if (rowLine.isEmpty()) break
                val cells = splitTableCells(rowLine)
                sb.append("<tr>")
                cells.forEachIndexed { idx, c ->
                    val align = aligns.getOrNull(idx) ?: "left"
                    sb.append("<td style=\"text-align: $align;\">")
                    sb.append(inlineFormat(escapeHtml(c)))
                    sb.append("</td>")
                }
                sb.append("</tr>")
                i++
            }
            sb.append("</tbody></table>")
            return i to sb.toString()
        }

        // 🎯 新增：列表解析（无序 / 有序）
        fun tryParseList(start: Int, lines: List<String>): Pair<Int, String>? {
            if (start >= lines.size) return null
            val unorderedRegex = Regex("^\\s*([*+\\-])\\s+(.+)$")
            val orderedRegex = Regex("^\\s*(\\d+)[.)]\\s+(.+)$")
            val taskRegex = Regex("^\\[([ xX])\\]\\s+(.*)$")
            var i = start
            val first = lines[i]
            val unorderedFirst = unorderedRegex.find(first)
            val orderedFirst = orderedRegex.find(first)
            if (unorderedFirst == null && orderedFirst == null) return null

            val isOrdered = orderedFirst != null
            val sb = StringBuilder()
            if (isOrdered) {
                val startNum = orderedFirst!!.groupValues[1].toIntOrNull()
                if (startNum != null && startNum != 1) sb.append("<ol start=\"$startNum\">") else sb.append("<ol>")
            } else {
                sb.append("<ul>")
            }

            while (i < lines.size) {
                val line = lines[i]
                if (line.trim().isEmpty()) break
                // 遇到代码围栏或表格分隔行时中断列表
                if (line.trim().startsWith("```") || Regex("^\\|?\\s*:?[-]{3,}:?\\s*(\\|\\s*:?[-]{3,}:?\\s*)+\\|?$").containsMatchIn(line.trim())) {
                    break
                }
                val mUn = unorderedRegex.find(line)
                val mOr = orderedRegex.find(line)
                if (isOrdered) {
                    if (mOr == null) break
                    val itemRaw = mOr.groupValues[2]
                    val task = taskRegex.find(itemRaw)
                    if (task != null) {
                        val checked = task.groupValues[1].equals("x", ignoreCase = true)
                        val text = task.groupValues[2]
                        sb.append("<li><input type=\"checkbox\" disabled" + (if (checked) " checked" else "") + "> ")
                        sb.append(inlineFormat(escapeHtml(text)))
                        sb.append("</li>")
                    } else {
                        sb.append("<li>").append(inlineFormat(escapeHtml(itemRaw))).append("</li>")
                    }
                } else {
                    if (mUn == null) break
                    val itemRaw = mUn.groupValues[2]
                    val task = taskRegex.find(itemRaw)
                    if (task != null) {
                        val checked = task.groupValues[1].equals("x", ignoreCase = true)
                        val text = task.groupValues[2]
                        sb.append("<li><input type=\"checkbox\" disabled" + (if (checked) " checked" else "") + "> ")
                        sb.append(inlineFormat(escapeHtml(text)))
                        sb.append("</li>")
                    } else {
                        sb.append("<li>").append(inlineFormat(escapeHtml(itemRaw))).append("</li>")
                    }
                }
                i++
            }

            sb.append(if (isOrdered) "</ol>" else "</ul>")
            return i to sb.toString()
        }

        val lines = normalized.split("\n")
        val out = StringBuilder()
        var i = 0
        var insideFence = false

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw

            // 围栏代码块处理
            if (line.trim().startsWith("```") ) {
                insideFence = !insideFence
                if (insideFence) {
                    out.append("<pre><code>")
                } else {
                    out.append("</code></pre>")
                }
                i++
                continue
            }

            if (!insideFence) {
                // 表格解析（高优先级，避免与普通行冲突）
                val tableParsed = tryParseTable(i, lines)
                if (tableParsed != null) {
                    out.append(tableParsed.second)
                    i = tableParsed.first
                    continue
                }
                // 标题
                val heading = convertHeadingLine(line)
                if (heading != null) {
                    out.append(heading)
                    i++
                    continue
                }
                // 🎯 新增：列表解析
                val listParsed = tryParseList(i, lines)
                if (listParsed != null) {
                    out.append(listParsed.second)
                    i = listParsed.first
                    continue
                }
                // 普通行（带行内样式）
                out.append(inlineFormat(escapeHtml(line)))
                out.append("<br />")
            } else {
                // 代码块内，原样转义输出
                out.append(escapeHtml(raw))
                out.append("\n")
            }
            i++
        }

        return out.toString()
    }
}

/**
 * 数学片段数据类
 */
private data class MathSegment(
    val original: String,
    val latex: String,
    val isDisplay: Boolean,
    val start: Int,
    val end: Int
)