package com.example.everytalk.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.ui.theme.chatColors
import dev.jeziellago.compose.markdowntext.MarkdownText
import com.example.everytalk.util.messageprocessor.parseMarkdownParts
import java.util.UUID

// 瀹夊叏鐨?MarkdownText 鍖呰鍣紝闃叉璐熷搴﹀竷灞€閿欒
@Composable
private fun SafeMarkdownText(
    markdown: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        // 馃幆 淇锛氱Щ闄や簡 try-catch 鍧椾互瑙ｅ喅缂栬瘧閿欒銆?
        // Compose 涓嶆敮鎸佸湪 @Composable 鍑芥暟璋冪敤鍛ㄥ洿浣跨敤 try-catch銆?
        // 濡傛灉 MarkdownText 鍙戠敓杩愯鏃跺穿婧冿紝闇€瑕佸鎵惧叾浠栨柟寮忔潵澶勭悊锛?
        // 渚嬪鍦ㄨ皟鐢ㄥ墠瀵?markdown 鍐呭杩涜鏇翠弗鏍肩殑楠岃瘉銆?
        MarkdownText(
            markdown = markdown,
            style = style,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// 馃幆 鏂扮殑鍚堝苟鍐呭鏁版嵁绫?
private sealed class ConsolidatedContent {
    data class FlowContent(val parts: List<MarkdownPart>) : ConsolidatedContent()
    data class BlockContent(val part: MarkdownPart) : ConsolidatedContent()
}

// 馃幆 鏅鸿兘妫€娴嬫槸鍚﹀簲璇ュ悎骞舵墍鏈夊唴瀹硅繘琛岀粺涓€娓叉煋
private fun shouldMergeAllContent(parts: List<MarkdownPart>, originalText: String): Boolean {
    // 馃幆 閲嶈淇锛氬鏋滃寘鍚玀athBlock锛岀粷瀵逛笉瑕佸悎骞讹紝璁╂暟瀛﹀叕寮忔纭覆鏌?
    val hasMathBlocks = parts.any { it is MarkdownPart.MathBlock }
    if (hasMathBlocks) {
        android.util.Log.d("shouldMergeAllContent", "馃幆 Found MathBlocks, will NOT merge to preserve math rendering")
        return false
    }

    // 浼樺厛锛氬己鐗瑰緛鐨?澶氳鍒楄〃/缂栧彿娈佃惤"鈫?鍚堝苟鏁存娓叉煋,閬垮厤琚媶鏁ｅ悗涓㈠け鍒楄〃涓婁笅鏂?
    run {
        val lines = originalText.lines()
        // 缁熻椤圭洰绗﹀彿鎴栨湁搴忕紪鍙峰紑澶寸殑琛屾暟锛堝厑璁稿墠瀵肩┖鏍硷級
        val bulletRegex = Regex("^\\s*([*+\\-]|\\d+[.)])\\s+")
        val bulletLines = lines.count { bulletRegex.containsMatchIn(it) }
        // 鑻ュ瓨鍦?缂栧彿鏍囬琛?+ 鑻ュ共缂╄繘瀛愰」"鐨勭粨鏋勶紝涔熷己鍒跺悎骞?
        val hasHeadingNumber = lines.any { Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(it) }
        if (bulletLines >= 2 || (hasHeadingNumber && bulletLines >= 1)) {
            return true
        }
    }
    
    // 鏉′欢1锛氬鏋滃師濮嬫枃鏈緢鐭紙灏忎簬200瀛楃锛夛紝鍊惧悜浜庡悎骞?
    if (originalText.length < 200) {
        // 鏉′欢1a锛氭病鏈夊鏉傜殑鍧楃骇鍐呭
        val hasComplexBlocks = parts.any { part ->
            when (part) {
                is MarkdownPart.CodeBlock -> true
                is MarkdownPart.MathBlock -> part.displayMode // 鍙湁鏄剧ず妯″紡鐨勬暟瀛﹀叕寮忕畻澶嶆潅
                else -> false
            }
        }
        
        if (!hasComplexBlocks) {
            return true
        }
        
        // 鏉′欢1b锛歱arts鏁伴噺杩囧鐩稿浜庡唴瀹归暱搴︼紙鍙兘琚繃搴﹀垎鍓诧級
        if (parts.size > originalText.length / 20) {
            return true
        }
        
        // 鏉′欢1c锛氬ぇ澶氭暟parts閮藉緢鐭紙鍙兘鏄敊璇垎鍓茬殑缁撴灉锛?
        val shortParts = parts.count { part ->
            when (part) {
                is MarkdownPart.Text -> part.content.trim().length < 10
                is MarkdownPart.MathBlock -> !part.displayMode && part.latex.length < 20
                else -> false
            }
        }
        
        if (shortParts > parts.size * 0.7) { // 瓒呰繃70%鐨刾arts閮藉緢鐭?
            return true
        }
    }
    
    // 鏉′欢2锛氭娴嬫槑鏄剧殑閿欒鍒嗗壊妯″紡
    // 濡傛灉鏈夊緢澶氬崟瀛楃鎴栬秴鐭殑鏂囨湰part锛屽彲鑳芥槸鍒嗗壊閿欒
    val singleCharParts = parts.count { part ->
        part is MarkdownPart.Text && part.content.trim().length <= 2
    }
    
    if (singleCharParts > 2 && singleCharParts > parts.size * 0.4) {
        return true
    }
    
    // 鏉′欢3锛氬鏋滃師濮嬫枃鏈寘鍚槑鏄剧殑杩炵画鍐呭浣嗚鍒嗗壊浜?
    // 妫€娴嬬被浼?"- *鏂囧瓧 e^x 路 **" 杩欐牱鐨勬ā寮?
    val isListLikePattern = originalText.trim().let { text ->
        (text.startsWith("-") || text.startsWith("*") || text.startsWith("路")) &&
        text.length < 100 &&
        parts.size > 3
    }
    
    if (isListLikePattern) {
        return true
    }
    
    return false
}

// 馃幆 婵€杩涚殑鍐呭鍚堝苟鍑芥暟
private fun consolidateInlineContent(parts: List<MarkdownPart>): List<ConsolidatedContent> {
    val result = mutableListOf<ConsolidatedContent>()
    var currentInlineGroup = mutableListOf<MarkdownPart>()
    
    fun flushInlineGroup() {
        if (currentInlineGroup.isNotEmpty()) {
            result.add(ConsolidatedContent.FlowContent(currentInlineGroup.toList()))
            currentInlineGroup.clear()
        }
    }
    
    parts.forEach { part ->
        when (part) {
            is MarkdownPart.Text -> {
                currentInlineGroup.add(part)
            }
            is MarkdownPart.MathBlock -> {
                if (!part.displayMode) {
                    // 琛屽唴鏁板鍏紡鍔犲叆褰撳墠缁?
                    currentInlineGroup.add(part)
                } else {
                    // 鍧楃骇鏁板鍏紡锛氬厛杈撳嚭褰撳墠缁勶紝鐒跺悗鐙珛澶勭悊
                    flushInlineGroup()
                    result.add(ConsolidatedContent.BlockContent(part))
                }
            }
            is MarkdownPart.CodeBlock -> {
                // 浠ｇ爜鍧楋細鍏堣緭鍑哄綋鍓嶇粍锛岀劧鍚庣嫭绔嬪鐞?
                flushInlineGroup()
                result.add(ConsolidatedContent.BlockContent(part))
            }
            is MarkdownPart.HtmlContent -> {
                // HTML鍐呭锛氬厛杈撳嚭褰撳墠缁勶紝鐒跺悗鐙珛澶勭悊
                flushInlineGroup()
                result.add(ConsolidatedContent.BlockContent(part))
            }
            is MarkdownPart.Table -> {
                // 琛ㄦ牸锛氬厛杈撳嚭褰撳墠缁勶紝鐒跺悗鐙珛澶勭悊
                flushInlineGroup()
                result.add(ConsolidatedContent.BlockContent(part))
            }
            is MarkdownPart.MixedContent -> {
                // 娣峰悎鍐呭锛氬厛杈撳嚭褰撳墠缁勶紝鐒跺悗鐙珛澶勭悊
                flushInlineGroup()
                result.add(ConsolidatedContent.BlockContent(part))
            }
        }
    }
    
    flushInlineGroup()
    return result
}

// 馃幆 鑷畾涔夎鍐呭唴瀹规覆鏌撳櫒
@Composable
private fun InlineContentRenderer(
    parts: List<MarkdownPart>,
    textColor: Color,
    style: TextStyle
) {
    // 馃幆 淇锛氭敼鐢?Center 鏇夸唬 Bottom锛屽疄鐜板瀭鐩村眳涓?
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.Center,  // 鉁?鍨傜洿灞呬腑瀵归綈
        maxItemsInEachRow = Int.MAX_VALUE
    ) {
        var i = 0
        fun String.endsWithoutSpace(): Boolean {
            val t = this.trimEnd()
            return t.isNotEmpty() && !t.last().isWhitespace()
        }
        while (i < parts.size) {
            val part = parts[i]
            val next = if (i + 1 < parts.size) parts[i + 1] else null
            // 妫€娴?"鏂囨湰(鏃犲熬绌烘牸) + 琛屽唴鍏紡" 缁勫悎锛屼綔涓轰竴涓笉鍙崲琛屽崟鍏冩覆鏌?
            if (part is MarkdownPart.Text &&
                next is MarkdownPart.MathBlock &&
                !next.displayMode
            ) {
                // 灏嗗墠涓€娈垫枃鏈媶鎴?鍙崲琛屽墠缂€ + 涓嶅彲鎹㈣鐨勭粨灏捐瘝"锛?
                // 鐢ㄧ粨灏捐瘝涓庢暟瀛﹀叕寮忕矘杩烇紝閬垮厤鍏紡鍗曠嫭璺戝埌涓嬩竴琛?
                val (prefix, glue) = splitForNoWrapTail(part.content)
                if (prefix.isNotBlank()) {
                    SmartTextRenderer(
                        text = prefix,
                        textColor = textColor,
                        style = style,
                        modifier = Modifier.wrapContentWidth()
                    )
                }
                val glueText = glue.trimEnd() // 鍘绘帀灏鹃儴绌烘牸锛岄伩鍏?涓?"+ 鍏紡涔嬮棿鍑虹幇鏂/闂撮殭
                if (glueText.isNotBlank()) {
                    NoWrapTextAndMath(
                        text = glueText,
                        latex = next.latex,
                        textColor = textColor,
                        style = style
                    )
                    i += 2
                    continue
                }
                // 鑻ユ棤娉曟湁鏁堟媶鍒嗭紝鍒欒蛋榛樿娴佺▼
            }

            when (part) {
                is MarkdownPart.Text -> {
                    if (part.content.isNotBlank()) {
                        val processedText = part.content
                        // 鍏堝鐞嗏€滅函瑁?LaTeX 鍗曡鈥濃€斺€斾緥濡傦細\boxed{275.5}
                        if (isPureBareLatexLine(processedText)) {
                            LatexMath(
                                latex = processedText.trim(),
                                inline = false,
                                color = textColor,
                                style = style,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                messageId = "pure_bare_latex_line"
                            )
                        } else if (processedText.contains("$")) {
                            RenderTextWithInlineMath(
                                text = processedText,
                                textColor = textColor,
                                style = style
                            )
                        } else {
                            SmartTextRenderer(
                                text = processedText,
                                textColor = textColor,
                                style = style,
                                modifier = Modifier.wrapContentWidth()
                            )
                        }
                    }
                }
                is MarkdownPart.MathBlock -> {
                    if (!part.displayMode) {
                        LatexMath(
                            latex = part.latex,
                            inline = true,
                            color = textColor,
                            style = style,
                            modifier = Modifier.wrapContentWidth(),
                            messageId = "inline_render"
                        )
                    }
                }
                else -> { /* 蹇界暐鍏朵粬绫诲瀷 */ }
            }
            i += 1
        }
    }
}

// 馃幆 鏅鸿兘鏂囨湰娓叉煋鍣細鑷姩妫€娴嬪唴鑱斾唬鐮佸拰鏁板鍏紡
@Composable
private fun SmartTextRenderer(
    text: String,
    textColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    // 鍏抽敭淇锛氬湪鍋氳兘鍔涘垎鏀墠锛屽厛鏈€灏忓寲灏嗏€滆８ sqrt/frac 绛夆€濆寘瑁逛负 $...$
    // 杩欐牱 hasMath 鍒ゅ畾鎵嶈兘鎹曟崏鍒板悗缁覆鏌撲负 LaTeX锛岃€屼笉鏄璧板埌 Markdown 娓叉煋璺緞
    val preWrapped = remember(text) {
        if (!text.contains('$') &&
            containsBareLatexToken(text) &&
            !text.contains('`') &&
            !text.contains('|')
        ) {
            wrapBareLatexForInline(text)
        } else {
            text
        }
    }
    val hasInlineCode = preWrapped.contains('`') && !preWrapped.startsWith("```")
    val hasMath = preWrapped.contains('$')
    
    when {
        hasInlineCode && hasMath -> {
            // 鍚屾椂鍖呭惈鍐呰仈浠ｇ爜鍜屾暟瀛﹀叕寮忥紝浣跨敤澶嶅悎娓叉煋
            RenderTextWithInlineCodeAndMath(preWrapped, textColor, style, modifier)
        }
        hasInlineCode -> {
            // 鍐呰仈浠ｇ爜淇濇寔鍐呰仈鏍峰紡锛屼笉杞崲涓轰唬鐮佸潡
            val segments = splitInlineCodeSegments(preWrapped)
            // 浣跨敤Column鑰屼笉鏄疐lowRow浠ラ伩鍏嶅竷灞€闂
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // 灏嗗唴鑱斾唬鐮佸拰鏂囨湰缁勫悎鎴愯
                var currentLine = mutableListOf<InlineSegment>()
                segments.forEach { segment ->
                    currentLine.add(segment)
                    // 濡傛灉閬囧埌鎹㈣绗︼紝娓叉煋褰撳墠琛?
                    if (segment.text.contains('\n')) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            currentLine.forEach { seg ->
                                if (seg.isCode) {
                                    // 鍐呰仈浠ｇ爜浣跨敤鐗规畩鏍峰紡锛屼絾淇濇寔鍐呰仈
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.chatColors.codeBlockBackground,
                                        modifier = Modifier.wrapContentSize()
                                    ) {
                                        Text(
                                            text = seg.text.replace("\n", ""),
                                            style = style.copy(
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                color = textColor
                                            ),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                } else {
                                    if (seg.text.isNotBlank()) {
                                        Text(
                                            text = seg.text.replace("\n", ""),
                                            style = style.copy(color = textColor),
                                            modifier = Modifier.wrapContentWidth()
                                        )
                                    }
                                }
                            }
                        }
                        currentLine.clear()
                    }
                }
                // 娓叉煋鍓╀綑鐨勫唴瀹?
                if (currentLine.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        currentLine.forEach { seg ->
                            if (seg.isCode) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.chatColors.codeBlockBackground,
                                    modifier = Modifier.wrapContentSize()
                                ) {
                                    Text(
                                        text = seg.text,
                                        style = style.copy(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = textColor
                                        ),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            } else {
                                if (seg.text.isNotBlank()) {
                                    Text(
                                        text = seg.text,
                                        style = style.copy(color = textColor),
                                        modifier = Modifier.wrapContentWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        hasMath -> {
            // 鍙湁鏁板鍏紡锛屼娇鐢ㄦ暟瀛︽覆鏌撳櫒
            RenderTextWithInlineMath(preWrapped, textColor, style)
        }
        else -> {
            // 0) 浼樺厛澶勭悊鈥滃潡绾ф暟瀛?$$...$$鈥濆満鏅紙涓ユ牸鎴愬锛夛紝閬垮厤璇垏瀵艰嚧鏂囨湰缂哄け
            run {
                val pairCount = Regex("\\$\\$").findAll(text).count()
                if (pairCount >= 2 && pairCount % 2 == 0) {
                    RenderTextWithBlockMath(
                        text = text,
                        textColor = textColor,
                        style = style
                    )
                    return
                }
            }
            // 0b) 瑁?LaTeX 鐩磋揪琛屽唴鏁板绠＄嚎锛堝 \boxed{...} / \frac 绛夋湭鍔?$ 鐨勬儏褰級
            if (containsBareLatexToken(text)) {
                RenderTextWithInlineMath(
                    text = wrapBareLatexForInline(text),
                    textColor = textColor,
                    style = style
                )
                return
            }
            // 1) 鍥存爮浠ｇ爜锛氬鏉惧尮閰嶅苟"鍏ㄩ儴閬嶅巻"锛岀‘淇濇瘡涓唬鐮佸潡閮借蛋鑷畾涔?CodePreview
            // 鏀硅繘锛氭敮鎸佹棤鎹㈣绗︾殑浠ｇ爜鍧楋紝鏇村鏉剧殑鍖归厤
            val fencedRegex = Regex("(?s)```\\s*([a-zA-Z0-9_+\\-#.]*)[ \\t]*\\r?\\n?([\\s\\S]*?)\\r?\\n?```")
            val matches = fencedRegex.findAll(text).toList()
            if (matches.isNotEmpty()) {
                var last = 0
                matches.forEachIndexed { idx, mr ->
                    val before = text.substring(last, mr.range.first)
                    if (before.isNotBlank()) {
                        SmartTextRenderer(
                            text = before,
                            textColor = textColor,
                            style = style,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    val lang = mr.groups[1]?.value?.trim().orEmpty()
                    val code = mr.groups[2]?.value ?: ""
                    CodePreview(
                        code = code,
                        language = if (lang.isBlank()) null else lang,
                        modifier = Modifier.fillMaxWidth()
                    )
                    last = mr.range.last + 1
                    if (idx != matches.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                if (last < text.length) {
                    val tail = text.substring(last)
                    if (tail.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        SmartTextRenderer(
                            text = tail,
                            textColor = textColor,
                            style = style,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                // 宸插鐞嗗叏閮ㄤ唬鐮佸潡锛岀洿鎺ヨ繑鍥?
                return
            } else if (text.contains("```")) {
                // 瀹芥澗鍏滃簳锛氬嵆浣垮洿鏍忎笉瑙勮寖锛堢己灏戞崲琛屾垨鏈熬鏈棴鍚堬級锛屼篃鎸夊洿鏍忓垏鐗囨覆鏌?
                // 杩欐牱涓嶄細閫€鍥炲簱榛樿鐨?MarkdownText锛屼粠鑰屼繚璇佸惎鐢ㄨ嚜瀹氫箟 CodePreview锛堝甫澶嶅埗/棰勮锛?
                FallbackFencedRenderer(
                    raw = text,
                    textColor = textColor,
                    style = style
                )
                return
            }
 
            // 1b) 璇嗗埆鈥滅缉杩涘紡浠ｇ爜鍧椻€濓紙浠?涓┖鏍兼垨Tab璧峰鐨勮繛缁锛夛紝缁熶竴璧?CodePreview 娓叉煋
            run {
                val blocks = extractIndentedCodeBlocks(text)
                if (blocks != null && blocks.blocks.isNotEmpty()) {
                    var last = 0
                    blocks.blocks.forEachIndexed { idx, b ->
                        val before = text.substring(last, b.range.first)
                        if (before.isNotBlank()) {
                            SmartTextRenderer(
                                text = before,
                                textColor = textColor,
                                style = style,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        CodePreview(
                            code = b.code,
                            language = null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        last = b.range.last + 1
                        if (idx != blocks.blocks.lastIndex) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    if (last < text.length) {
                        val tail = text.substring(last)
                        if (tail.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SmartTextRenderer(
                                text = tail,
                                textColor = textColor,
                                style = style,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    return
                }
            }
 
            // 2) 琛ㄦ牸鍏滃簳妫€娴嬶細鍗充娇涓婃父缁欏埌 Text锛屼篃鍒嗘祦鍒拌〃鏍兼覆鏌?
            if (detectMarkdownTable(text)) {
                // 馃幆 淇锛氶€掑綊娓叉煋琛ㄦ牸鍓嶅悗鍐呭锛岄伩鍏嶆埅鏂悗缁殑 Markdown 娓叉煋
                val (before, tableBlock, after) = splitByFirstMarkdownTable(text)
                if (before.isNotBlank()) {
                    SmartTextRenderer(
                        text = before,
                        textColor = textColor,
                        style = style,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (tableBlock.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SafeMarkdownText(
                        markdown = tableBlock,
                        style = style,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (after.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SmartTextRenderer(
                        text = after,
                        textColor = textColor,
                        style = style,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                // 3) 绾疢arkdown鏂囨湰锛屼娇鐢ㄥ師濮嬫覆鏌撳櫒
                // 馃幆 淇锛氫娇鐢⊿afeMarkdownText閬垮厤璐熷搴﹀竷灞€閿欒
                SafeMarkdownText(
                    markdown = normalizeBasicMarkdown(text),
                    style = style.copy(color = textColor, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    modifier = modifier.fillMaxWidth()
                )
            }
        }
    }
}

// 馃幆 澶勭悊鍖呭惈鍧楃骇鏁板锛?$...$$锛変笌鏅€氭枃鏈殑娣峰悎鍐呭
@Composable
private fun RenderTextWithBlockMath(
    text: String,
    textColor: Color,
    style: TextStyle
) {
    // 浠ユ垚瀵?$$ 浣滀负鍒嗘锛屽鏁版涓烘枃鏈紝鍋舵暟娈典负鏁板锛堜笌 split 缁撴灉涓€鑷达級
    val parts = text.split("$$")
    Column(modifier = Modifier.fillMaxWidth()) {
        parts.forEachIndexed { idx, seg ->
            if (seg.isEmpty()) return@forEachIndexed
            if (idx % 2 == 1) {
                // 鏁板娈碉紙鍧楃骇锛?
                LatexMath(
                    latex = seg.trim(),
                    inline = false,
                    color = textColor,
                    style = style,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    messageId = "block_segment"
                )
            } else {
                // 鏅€氭枃鏈紙涓嶅仛鏁板鏀瑰啓锛?
                SmartTextRenderer(
                    text = seg,
                    textColor = textColor,
                    style = style,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// 馃幆 澶勭悊鍚屾椂鍖呭惈鍐呰仈浠ｇ爜鍜屾暟瀛﹀叕寮忕殑鏂囨湰
@Composable
private fun RenderTextWithInlineCodeAndMath(
    text: String,
    textColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    // 涓嶅啀杞崲鍐呰仈浠ｇ爜涓哄洿鏍忎唬鐮佸潡锛屼繚鎸佸唴鑱旀牱寮?
    // 浣跨敤鏇存櫤鑳界殑鍒嗗壊鏂规硶锛屽悓鏃跺鐞嗗唴鑱斾唬鐮佸拰鏁板鍏紡
    
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.Center
    ) {
        var currentPos = 0
        val segments = mutableListOf<Pair<String, String>>() // content, type: "text", "code", "math"
        
        while (currentPos < text.length) {
            // 鏌ユ壘涓嬩竴涓壒娈婃爣璁?
            val nextCode = text.indexOf('`', currentPos)
            val nextMath = text.indexOf('$', currentPos)
            
            when {
                nextCode == -1 && nextMath == -1 -> {
                    // 娌℃湁鏇村鐗规畩鏍囪锛屾坊鍔犲墿浣欐枃鏈?
                    if (currentPos < text.length) {
                        segments.add(text.substring(currentPos) to "text")
                    }
                    break
                }
                nextCode != -1 && (nextMath == -1 || nextCode < nextMath) -> {
                    // 鍏堥亣鍒颁唬鐮佹爣璁?
                    if (nextCode > currentPos) {
                        segments.add(text.substring(currentPos, nextCode) to "text")
                    }
                    val codeEnd = text.indexOf('`', nextCode + 1)
                    if (codeEnd != -1) {
                        segments.add(text.substring(nextCode + 1, codeEnd) to "code")
                        currentPos = codeEnd + 1
                    } else {
                        segments.add(text.substring(nextCode) to "text")
                        break
                    }
                }
                else -> {
                    // 鍏堥亣鍒版暟瀛︽爣璁?
                    if (nextMath > currentPos) {
                        segments.add(text.substring(currentPos, nextMath) to "text")
                    }
                    val mathEnd = text.indexOf('$', nextMath + 1)
                    if (mathEnd != -1) {
                        segments.add(text.substring(nextMath + 1, mathEnd) to "math")
                        currentPos = mathEnd + 1
                    } else {
                        segments.add(text.substring(nextMath) to "text")
                        break
                    }
                }
            }
        }
        
        // 娓叉煋鎵€鏈夌墖娈?
        segments.forEach { (content, type) ->
            when (type) {
                "code" -> {
                    // 鍐呰仈浠ｇ爜浣跨敤鐗规畩鏍峰紡锛屼絾淇濇寔鍐呰仈
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.chatColors.codeBlockBackground,
                        modifier = Modifier.wrapContentSize()
                    ) {
                        Text(
                            text = content,
                            style = style.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = textColor
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                "math" -> {
                    LatexMath(
                        latex = content,
                        inline = true,
                        color = textColor,
                        style = style,
                        modifier = Modifier.wrapContentWidth(),
                        messageId = "inline_math"
                    )
                }
                "text" -> {
                    if (content.isNotBlank()) {
                        // 閬垮厤浣跨敤MarkdownText锛岀洿鎺ヤ娇鐢═ext鏉ラ槻姝㈠竷灞€閿欒
                        Text(
                            text = content,
                            style = style.copy(color = textColor),
                            modifier = Modifier.wrapContentWidth()
                        )
                    }
                }
            }
        }
    }
}

// 馃幆 澶勭悊鍖呭惈琛屽唴鏁板鍏紡鐨勬枃鏈?
@Composable
private fun RenderTextWithInlineMath(
    text: String,
    textColor: Color,
    style: TextStyle
) {
    // 绠€鍗曠殑$...$鍒嗗壊澶勭悊
    val segments = splitMathSegments(text)
    
    // 馃幆 淇锛氭敼鐢?Center锛屽疄鐜板瀭鐩村眳涓?
    FlowRow(
        modifier = Modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.Center,  // 鉁?鍨傜洿灞呬腑
        maxItemsInEachRow = Int.MAX_VALUE
    ) {
        fun String.endsWithoutSpace(): Boolean {
            val t = this.trimEnd()
            return t.isNotEmpty() && !t.last().isWhitespace()
        }
        var i = 0
        while (i < segments.size) {
            val seg = segments[i]
            val next = if (i + 1 < segments.size) segments[i + 1] else null
            // 灏?"鏂囨湰(鏃犲熬绌烘牸) + 鏁板" 鍚堝苟涓轰笉鍙崲琛屽崟鍏?
            if (!seg.isMath && next != null && next.isMath) {
                // 灏嗗垎娈垫枃鏈殑灏鹃儴璇嶄笌鎺ヤ笅鏉ョ殑鏁板娈电矘杩烇紝褰㈡垚涓嶅彲鎹㈣鍗曞厓
                val (prefix, glue) = splitForNoWrapTail(seg.content)
                if (prefix.isNotBlank()) {
                    SafeMarkdownText(
                        markdown = normalizeBasicMarkdownNoMath(prefix),
                        style = style.copy(color = textColor, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                val glueText = glue.trimEnd()
                if (glueText.isNotBlank()) {
                    NoWrapTextAndMath(
                        text = glueText,
                        latex = next.content,
                        textColor = textColor,
                        style = style
                    )
                    i += 2
                    continue
                }
                // 鎷嗗垎澶辫触鍒欒蛋榛樿娴佺▼
            }

            if (seg.isMath) {
                LatexMath(
                    latex = seg.content,
                    inline = true,
                    color = textColor,
                    style = style,
                    modifier = Modifier.wrapContentWidth(),
                    messageId = "math_segment"
                )
            } else {
                // 閬垮厤閫掑綊璋冪敤SmartTextRenderer
                if (seg.content.isNotBlank()) {
                    SafeMarkdownText(
                        markdown = normalizeBasicMarkdownNoMath(seg.content),
                        style = style.copy(color = textColor, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            i += 1
        }
    }
}

// 灏?鏂囨湰+琛屽唴鏁板"娓叉煋涓轰笉鍙崲琛屽崟鍏冿紝閬垮厤琚媶琛?
@Composable
private fun NoWrapTextAndMath(
    text: String,
    latex: String,
    textColor: Color,
    style: TextStyle
) {
    Row(
        modifier = Modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 閬垮厤閫掑綊璋冪敤SmartTextRenderer
        SafeMarkdownText(
            markdown = normalizeBasicMarkdownNoMath(text),
            style = style.copy(color = textColor, platformStyle = PlatformTextStyle(includeFontPadding = false)),
            modifier = Modifier.fillMaxWidth()
        )
        LatexMath(
            latex = latex,
            inline = true,
            color = textColor,
            style = style,
            modifier = Modifier.wrapContentWidth(),
            messageId = "nowrap_pair"
        )
    }
}

// 灏嗘枃鏈垎鍓蹭负鍙崲琛屽墠缂€鍜屼笉鍙崲琛屽熬閮?
private fun splitForNoWrapTail(text: String): Pair<String, String> {
    if (text.isBlank()) return Pair("", "")
    
    // 鎵惧埌鏈€鍚庝竴涓┖鏍兼垨鏍囩偣绗﹀彿鐨勪綅缃?
    val words = text.split(Regex("\\s+"))
    if (words.size <= 1) {
        return Pair("", text) // 鍙湁涓€涓瘝锛屽叏閮ㄤ綔涓哄熬閮?
    }
    
    // 鍙栨渶鍚庝竴涓瘝浣滀负涓嶅彲鎹㈣閮ㄥ垎
    val prefix = words.dropLast(1).joinToString(" ")
    val tail = words.last()
    
    return Pair(prefix, tail)
}

// 馃幆 绠€鍗曠殑鏁板鍏紡鍒嗗壊鍣?
private data class TextSegment(val content: String, val isMath: Boolean)

private fun splitMathSegments(text: String): List<TextSegment> {
    val segments = mutableListOf<TextSegment>()
    var currentPos = 0
    
    while (currentPos < text.length) {
        val mathStart = text.indexOf('$', currentPos)
        if (mathStart == -1) {
            // 娌℃湁鏇村鏁板鍏紡锛屾坊鍔犲墿浣欐枃鏈?
            if (currentPos < text.length) {
                segments.add(TextSegment(text.substring(currentPos), false))
            }
            break
        }
        
        // 娣诲姞鏁板鍏紡鍓嶇殑鏂囨湰
        if (mathStart > currentPos) {
            segments.add(TextSegment(text.substring(currentPos, mathStart), false))
        }
        
        // 鏌ユ壘鏁板鍏紡缁撴潫
        val mathEnd = text.indexOf('$', mathStart + 1)
        if (mathEnd == -1) {
            // 娌℃湁鎵惧埌缁撴潫$锛屽綋浣滄櫘閫氭枃鏈?
            segments.add(TextSegment(text.substring(mathStart), false))
            break
        }
        
        // 娣诲姞鏁板鍏紡
        val mathContent = text.substring(mathStart + 1, mathEnd)
        if (mathContent.isNotBlank()) {
            segments.add(TextSegment(mathContent, true))
        }
        
        currentPos = mathEnd + 1
    }
    
    return segments
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
    val startTime = remember { System.currentTimeMillis() }
    val systemDark = isSystemInDarkTheme()
    
    val baseStyle = remember(style) { style.normalizeForChat() }
    val textColor = when {
        color != Color.Unspecified -> color
        baseStyle.color != Color.Unspecified -> baseStyle.color
        else -> if (systemDark) Color(0xFFFFFFFF) else Color(0xFF000000)
    }
    
    // 馃敡 缁熶竴娓呮礂锛氬幓琛屽熬鈥淺鈥濅笌鐩搁偦閲嶅娈碉紝閬垮厤閲嶅/鑴忓瓧绗﹀鑷寸殑鏍煎紡娣蜂贡
    val cleanedText = remember(message.text) { sanitizeAiOutput(message.text) }
 
     DisposableEffect(message.id) {
        onDispose {
            RenderingMonitor.trackRenderingPerformance(message.id, startTime)
            // 娓呯悊娓叉煋鐘舵€?
            MathRenderingManager.clearMessageStates(message.id)
        }
    }

    // 馃幆 妫€鏌ユ秷鎭槸鍚﹀寘鍚暟瀛﹀叕寮忥紝鎻愪氦娓叉煋浠诲姟锛堝熀浜庢竻娲楀悗鐨勬枃鏈級
    LaunchedEffect(message.id, cleanedText) {
        if (message.sender == com.example.everytalk.data.DataClass.Sender.AI &&
            MathRenderingManager.hasRenderableMath(cleanedText)) {
            val mathBlocks = ConversationLoadManager.extractMathBlocks(cleanedText, message.id)
            if (mathBlocks.isNotEmpty()) {
                MathRenderingManager.submitMessageMathTasks(message.id, mathBlocks)
            }
        }
    }

    Column(modifier = modifier) {
        // 浼樺厛锛氶潪 AI 娑堟伅锛堢敤鎴?绯荤粺/宸ュ叿锛変娇鐢ㄥ唴瀹硅嚜閫傚簲娓叉煋锛岄伩鍏嶈鍚庣画 fillMaxWidth 鍒嗘敮鎾戞弧
        if (message.sender != com.example.everytalk.data.DataClass.Sender.AI) {
            Text(
                text = message.text,
                style = style.copy(color = textColor, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                modifier = Modifier.wrapContentWidth()
            )
            return@Column
        }

        // 鑻ュ凡缁撴潫娴佸紡锛堟渶缁堟€侊級锛屼紭鍏堜娇鐢?鏁存鏂囨湰绾?鐨勬覆鏌撶瓥鐣ワ紝閬垮厤琚鐗囧寲 parts 骞叉壈
        if (!isStreaming) {
            val t = cleanedText
            
            // 馃幆 鏈€楂樹紭鍏堢骇锛氬厛澶勭悊鍥存爮浠ｇ爜鍧楋紝纭繚浣跨敤CodePreview缁勪欢
            val fencedRegex = Regex("(?s)```\\s*([a-zA-Z0-9_+\\-#.]*)[ \\t]*\\r?\\n?([\\s\\S]*?)\\r?\\n?```")
            val codeMatches = fencedRegex.findAll(t).toList()
            
            if (codeMatches.isNotEmpty()) {
                // 鎵惧埌浠ｇ爜鍧楋紝鍒嗘娓叉煋
                var lastPos = 0
                codeMatches.forEachIndexed { idx, matchResult ->
                    // 娓叉煋浠ｇ爜鍧楀墠鐨勫唴瀹?
                    val beforeCode = t.substring(lastPos, matchResult.range.first)
                    if (beforeCode.isNotBlank()) {
                        SmartTextRenderer(
                            text = beforeCode,
                            textColor = textColor,
                            style = baseStyle,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // 娓叉煋浠ｇ爜鍧?- 浣跨敤CodePreview缁勪欢
                    val language = matchResult.groups[1]?.value?.trim().orEmpty()
                    val codeContent = matchResult.groups[2]?.value ?: ""
                    
                    android.util.Log.d("EnhancedMarkdownText", "馃幆 Rendering code block with language: '$language'")
                    CodePreview(
                        code = codeContent,
                        language = if (language.isBlank()) null else language,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    lastPos = matchResult.range.last + 1
                    if (idx != codeMatches.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                
                // 娓叉煋鏈€鍚庝竴涓唬鐮佸潡鍚庣殑鍐呭
                if (lastPos < t.length) {
                    val afterCode = t.substring(lastPos)
                    if (afterCode.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        SmartTextRenderer(
                            text = afterCode,
                            textColor = textColor,
                            style = baseStyle,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                return@Column
            }
            
            // 妫€娴嬭〃鏍?
            if (detectMarkdownTable(t)) {
                val (before, tableBlock, after) = splitByFirstMarkdownTable(t)
                if (before.isNotBlank()) {
                    SmartTextRenderer(
                        text = before,
                        textColor = textColor,
                        style = baseStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (tableBlock.isNotBlank()) {
                    SafeMarkdownText(
                        markdown = tableBlock,
                        style = baseStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (after.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SmartTextRenderer(
                        text = after,
                        textColor = textColor,
                        style = baseStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                return@Column
            }

            // 鏁板浼樺厛锛氳嫢瀛樺湪鎴愬 $$锛堝潡绾ф暟瀛︼級锛屼娇鐢ㄥ潡绾ф暟瀛︽覆鏌?
            run {
                val pairCount = Regex("\\$\\$").findAll(t).count()
                if (pairCount >= 2 && pairCount % 2 == 0) {
                    RenderTextWithBlockMath(
                        text = t,
                        textColor = textColor,
                        style = baseStyle
                    )
                    return@Column
                }
            }
            // 琛屽唴鏁板鎴栬８ LaTeX锛氳蛋鍐呰仈鏁板娓叉煋锛堝繀瑕佹椂鍏堟渶灏忓寘瑁癸級
            if (t.contains('$') || containsBareLatexToken(t)) {
                val prepared = if (!t.contains('$')) wrapBareLatexForInline(t) else t
                RenderTextWithInlineMath(
                    text = prepared,
                    textColor = textColor,
                    style = baseStyle
                )
                return@Column
            }

            // 鏃犳暟瀛?浠ｇ爜/琛ㄦ牸锛氱洿鎺ヤ娇鐢?Markdown 娓叉煋锛屽苟绉婚櫎鍐呰仈鍙嶅紩鍙蜂互閬垮厤鐧借壊鍐呰仈浠ｇ爜鍧?
            SafeMarkdownText(
                markdown = removeInlineCodeBackticks(normalizeBasicMarkdown(t)),
                style = baseStyle.copy(color = textColor, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                modifier = Modifier.fillMaxWidth()
            )
            return@Column
        }

        // 馃幆 璋冭瘯鏃ュ織锛氭鏌ユ秷鎭殑瑙ｆ瀽鐘舵€?
        android.util.Log.d("EnhancedMarkdownText", "=== Rendering Message ${message.id} ===")
        android.util.Log.d("EnhancedMarkdownText", "Message sender: ${message.sender}")
        android.util.Log.d("EnhancedMarkdownText", "Message text: ${message.text.take(100)}...")
        android.util.Log.d("EnhancedMarkdownText", "Message parts count: ${message.parts.size}")
        android.util.Log.d("EnhancedMarkdownText", "Message contentStarted: ${message.contentStarted}")
        
        // 宸插湪鍑芥暟寮€澶翠紭鍏堝鐞嗕簡闈?AI 鍒嗘敮锛堝唴瀹硅嚜閫傚簲 + 鎻愬墠杩斿洖锛?
        
        // 馃幆 绠€鍗昅arkdown蹇€熻矾寰勶細鏃?$鍧楃骇鏁板銆佹棤鍥存爮浠ｇ爜銆佹棤琛ㄦ牸鏃讹紝鐩存帴浜ょ粰SmartTextRenderer缁熶竴娓叉煋
        run {
            val t = cleanedText
            val hasBlockMath = t.contains("$$")
            val hasFenced = t.contains("```")  // 绠€鍖栨娴?
            val hasTable = detectMarkdownTable(t)
            
            android.util.Log.d("EnhancedMarkdownText", "Quick check - hasBlockMath: $hasBlockMath, hasFenced: $hasFenced, hasTable: $hasTable")
            
            if (!hasBlockMath && !hasFenced && !hasTable) {
                // 鐩存帴鐢?SafeMarkdownText 娓叉煋锛屽苟绉婚櫎鍐呰仈鍙嶅紩鍙凤紝閬垮厤鐧借壊鍐呰仈浠ｇ爜鍧?
                SafeMarkdownText(
                    markdown = removeInlineCodeBackticks(normalizeBasicMarkdown(t)),
                    style = baseStyle.copy(color = textColor, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    modifier = Modifier.fillMaxWidth()
                )
                return@Column
            }
        }
        
        // 浼樺厛绾ф洿楂橈細鏁存潯娑堟伅绾у埆鐨勮〃鏍兼娴嬩笌鍒囧垎娓叉煋锛堥伩鍏嶈鍒嗙墖鎵撴暎鑰屾娴嬪け璐ワ級
        if (detectMarkdownTable(cleanedText)) {
            val (before, tableBlock, after) = splitByFirstMarkdownTable(cleanedText)
            if (before.isNotBlank()) {
                SmartTextRenderer(
                    text = before,
                    textColor = textColor,
                    style = baseStyle,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (tableBlock.isNotBlank()) {
                SafeMarkdownText(
                    markdown = tableBlock,
                    style = baseStyle,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (after.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                SmartTextRenderer(
                    text = after,
                    textColor = textColor,
                    style = baseStyle,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            return@Column
        }

        // 馃幆 鏈€楂樹紭鍏堢骇锛氬鐞嗗洿鏍忎唬鐮佸潡锛岀‘淇濅娇鐢–odePreview缁勪欢
        val fencedRegex = Regex("(?s)```\\s*([a-zA-Z0-9_+\\-#.]*)[ \\t]*\\r?\\n?([\\s\\S]*?)\\r?\\n?```")
        val codeMatches = fencedRegex.findAll(cleanedText).toList()
        
        if (codeMatches.isNotEmpty()) {
            android.util.Log.d("EnhancedMarkdownText", "馃幆 Found ${codeMatches.size} code blocks in streaming message")
            var lastPos = 0
            codeMatches.forEachIndexed { idx, matchResult ->
                val beforeCode = cleanedText.substring(lastPos, matchResult.range.first)
                if (beforeCode.isNotBlank()) {
                    SmartTextRenderer(
                        text = beforeCode,
                        textColor = textColor,
                        style = baseStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                val language = matchResult.groups[1]?.value?.trim().orEmpty()
                val codeContent = matchResult.groups[2]?.value ?: ""
                
                android.util.Log.d("EnhancedMarkdownText", "馃幆 Rendering code block with language: '$language'")
                CodePreview(
                    code = codeContent,
                    language = if (language.isBlank()) null else language,
                    modifier = Modifier.fillMaxWidth()
                )
                
                lastPos = matchResult.range.last + 1
                if (idx != codeMatches.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            
            if (lastPos < cleanedText.length) {
                val afterCode = cleanedText.substring(lastPos)
                if (afterCode.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SmartTextRenderer(
                        text = afterCode,
                        textColor = textColor,
                        style = baseStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            return@Column
        }
        
        if (message.parts.isEmpty()) {
            android.util.Log.w("EnhancedMarkdownText", "鈿狅笍 AI Message parts is EMPTY, attempting to parse math formulas")
            // 馃幆 涓存椂淇锛氬嵆浣縫arts涓虹┖锛屼篃灏濊瘯瑙ｆ瀽鏁板鍏紡锛堜粎閽堝AI娑堟伅锛?
            if (cleanedText.contains("$") || cleanedText.contains("\\")) {
                android.util.Log.d("EnhancedMarkdownText", "Found potential math content, parsing...")
                
                val parsedParts = try {
                    parseMarkdownParts(cleanedText)
                } catch (e: Exception) {
                    android.util.Log.e("EnhancedMarkdownText", "Failed to parse math content: ${e.message}")
                    emptyList()
                }
                
                android.util.Log.d("EnhancedMarkdownText", "Parsed ${parsedParts.size} parts from empty-parts message")
                parsedParts.forEachIndexed { index, part ->
                    android.util.Log.d("EnhancedMarkdownText", "Part $index: ${part::class.simpleName} - ${part.toString().take(100)}...")
                }
                
                if (parsedParts.isNotEmpty()) {
                    // 浣跨敤瑙ｆ瀽鍚庣殑parts杩涜娓叉煋
                    parsedParts.forEach { part ->
                        when (part) {
                            is MarkdownPart.Text -> {
                                if (part.content.isNotBlank()) {
                                    SmartTextRenderer(
                                        text = part.content,
                                        textColor = textColor,
                                        style = baseStyle,
                                        modifier = Modifier
                                    )
                                }
                            }
                            is MarkdownPart.CodeBlock -> {
                                CodePreview(
                                    code = part.content,
                                    language = part.language
                                )
                            }
                            is MarkdownPart.MathBlock -> {
                                LatexMath(
                                    latex = part.latex,
                                    inline = !part.displayMode,
                                    color = textColor,
                                    style = style,
                                    modifier = if (part.displayMode)
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    else
                                        Modifier.wrapContentWidth(),
                                    messageId = message.id
                                )
                            }
                            is MarkdownPart.Table -> {
                                // 琛ㄦ牸鍘熺敓娓叉煋锛堢姝?WebView/HTML锛?
                                SafeMarkdownText(
                                    markdown = part.content,
                                    style = baseStyle,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            else -> {
                                // 鍏朵粬绫诲瀷
                            }
                        }
                    }
                } else {
                    // 瑙ｆ瀽澶辫触锛屼娇鐢ㄦ櫤鑳芥覆鏌撳櫒
                    SmartTextRenderer(
                        text = cleanedText,
                        textColor = textColor,
                        style = baseStyle,
                        modifier = Modifier
                    )
                }
            } else {
                // 娌℃湁鏁板鍐呭锛屼娇鐢ㄦ櫤鑳芥覆鏌撳櫒
                SmartTextRenderer(
                    text = cleanedText,
                    textColor = textColor,
                    style = baseStyle,
                    modifier = Modifier
                )
            }
        } else {
            // 妫€鏌?parts 鐨勬湁鏁堟€?
            val hasValidParts = message.parts.any { part ->
                when (part) {
                    is MarkdownPart.Text -> part.content.isNotBlank()
                    is MarkdownPart.CodeBlock -> part.content.isNotBlank()
                    is MarkdownPart.MathBlock -> part.latex.isNotBlank() || part.content.isNotBlank()
                    is MarkdownPart.HtmlContent -> part.html.isNotBlank()
                    is MarkdownPart.Table -> part.content.isNotBlank()
                    is MarkdownPart.MixedContent -> part.content.isNotBlank()
                }
            }
            
            android.util.Log.d("EnhancedMarkdownText", "Has valid parts: $hasValidParts")
            message.parts.forEachIndexed { index, part ->
                android.util.Log.d("EnhancedMarkdownText", "Checking Part $index: ${part::class.simpleName}")
                when (part) {
                    is MarkdownPart.Text -> android.util.Log.d("EnhancedMarkdownText", "  Text: '${part.content.take(30)}...'")
                    is MarkdownPart.MathBlock -> android.util.Log.d("EnhancedMarkdownText", "  MathBlock: '${part.latex}' (displayMode=${part.displayMode})")
                    is MarkdownPart.CodeBlock -> android.util.Log.d("EnhancedMarkdownText", "  CodeBlock: '${part.content.take(30)}...'")
                    is MarkdownPart.HtmlContent -> android.util.Log.d("EnhancedMarkdownText", "  HtmlContent: '${part.html.take(30)}...'")
                    is MarkdownPart.Table -> android.util.Log.d("EnhancedMarkdownText", "  Table: '${part.content.take(30)}...'")
                    is MarkdownPart.MixedContent -> android.util.Log.d("EnhancedMarkdownText", "  MixedContent: '${part.content.take(30)}...'")
                }
            }
            
            if (!hasValidParts && message.text.isNotBlank()) {
                // 鍥為€€鍒板師濮嬫枃鏈覆鏌擄紝浣跨敤鏅鸿兘娓叉煋鍣?
                RenderingMonitor.logRenderingIssue(message.id, "Parts invalid, fallback to original text", cleanedText)
                SmartTextRenderer(
                    text = cleanedText,
                    textColor = textColor,
                    style = style,
                    modifier = Modifier
                )
            } else {
                // 馃幆 鏅鸿兘妫€娴嬶細濡傛灉鍐呭寰堢煭涓斿彲鑳借閿欒鍒嗗壊锛岀洿鎺ュ悎骞舵覆鏌?
                val shouldMergeContent = shouldMergeAllContent(message.parts, cleanedText)
                android.util.Log.d("EnhancedMarkdownText", "Should merge content: $shouldMergeContent")
                
                if (shouldMergeContent) {
                    android.util.Log.d("EnhancedMarkdownText", "馃敡 妫€娴嬪埌鍐呭琚敊璇垎鍓诧紝鍚堝苟娓叉煋")
                    // 鐩存帴浣跨敤娓呮礂鍚庣殑鏂囨湰杩涜瀹屾暣娓叉煋锛屼娇鐢ㄦ櫤鑳芥覆鏌撳櫒
                    SmartTextRenderer(
                        text = cleanedText,
                        textColor = textColor,
                        style = baseStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    android.util.Log.d("EnhancedMarkdownText", "馃幆 Using part-by-part rendering with ${message.parts.size} parts")
                    // 馃幆 婵€杩涗紭鍖栵細灏嗚繛缁殑鏂囨湰鍜岃鍐呮暟瀛﹀叕寮忓悎骞舵垚涓€涓祦寮忓竷灞€
                    val consolidatedContent = consolidateInlineContent(message.parts)
                    android.util.Log.d("EnhancedMarkdownText", "Consolidated content count: ${consolidatedContent.size}")
                    
                    consolidatedContent.forEach { content ->
                        when (content) {
                            is ConsolidatedContent.FlowContent -> {
                                android.util.Log.d("EnhancedMarkdownText", "馃幆 Rendering FlowContent with ${content.parts.size} parts")
                                // 浣跨敤鑷畾涔夌殑琛屽唴娓叉煋鍣紝瀹屽叏娑堥櫎鎹㈣
                                InlineContentRenderer(
                                    parts = content.parts,
                                    textColor = textColor,
                                    style = style
                                )
                            }
                            is ConsolidatedContent.BlockContent -> {
                                val part = content.part
                                android.util.Log.d("EnhancedMarkdownText", "馃幆 Rendering BlockContent: ${part::class.simpleName}")
                                when (part) {
                                    is MarkdownPart.CodeBlock -> {
                                        android.util.Log.d("EnhancedMarkdownText", "馃幆 Rendering CodeBlock")
                                        CodePreview(
                                            code = part.content,
                                            language = part.language
                                        )
                                    }
                                    is MarkdownPart.MathBlock -> {
                                        android.util.Log.d("EnhancedMarkdownText", "馃幆 Rendering MathBlock: '${part.latex}' (displayMode=${part.displayMode})")
                                        LatexMath(
                                            latex = part.latex,
                                            inline = false,
                                            color = textColor,
                                            style = style,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            messageId = message.id
                                        )
                                    }
                                    is MarkdownPart.Table -> {
                                        android.util.Log.d("EnhancedMarkdownText", "馃幆 Rendering Table block (native)")
                                        SafeMarkdownText(
                                            markdown = part.content,
                                            style = style,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    else -> {
                                        android.util.Log.d("EnhancedMarkdownText", "馃幆 Other block content type: ${part::class.simpleName}")
                                        // 澶勭悊鍏朵粬鍧楃骇鍐呭
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LatexMath(
    latex: String,
    inline: Boolean,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    messageId: String = "",
    mathId: String = "${messageId}_${latex.hashCode()}"
) {
    android.util.Log.d("LatexMath", "馃幆 寮€濮嬪師鐢熸覆鏌揕aTeX: '$latex' (inline=$inline, mathId=$mathId)")
    
    // 鐩存帴浣跨敤鏂扮殑鍘熺敓娓叉煋鍣紝鏃犻渶澶嶆潅鐨勭姸鎬佺鐞?
    NativeMathText(
        latex = latex,
        isInline = inline,
        modifier = modifier.then(
            if (inline) Modifier.wrapContentHeight().padding(vertical = 0.dp)
            else Modifier.fillMaxWidth().padding(vertical = 2.dp)
        )
    )
}

private fun splitTextIntoBlocks(text: String): List<MarkdownPart.Text> {
    if (text.isBlank()) return listOf(MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = ""))
    val paragraphs = text.split("\n\n").filter { it.isNotBlank() }
    return if (paragraphs.isEmpty()) {
        listOf(MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = text))
    } else {
        paragraphs.map { MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = it.trim()) }
    }
}

// 杞婚噺琛ㄦ牸妫€娴嬶紙鍓嶇鍏滃簳鐢級锛氬瓨鍦ㄥ甫绔栫嚎鐨勫琛岋紝涓旂浜岃/浠讳竴琛屽寘鍚?--- 鍒嗛殧
private fun detectMarkdownTable(content: String): Boolean {
    val lines = content.trim().lines().filter { it.isNotBlank() }
    if (lines.size < 2) return false
    // 鍏佽鍏ㄨ/妗嗙嚎绔栫嚎
    fun normPipes(s: String): String {
        val barLikes = setOf('｜','│','┃','∣','︱','ǀ','❘','❙','❚','⎪','￨')
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(if (ch in barLikes) '|' else ch)
        }
        return sb.toString()
    }
    val hasPipes = lines.count { normPipes(it).contains("|") } >= 2
    if (!hasPipes) return false
    // 鏀惧鍖归厤锛氬厑璁稿垎闅旇鍚庣揣璺熼琛屾暟鎹紙鍚屼竴琛岋級
    val separatorRegexLoose = Regex("^\\s*\\|?\\s*:?[-]{2,}:?\\s*(\\|\\s*:?[-]{2,}:?\\s*)+\\|?\\s*")
    val detectedIndex = lines.indexOfFirst { line ->
        val t = normPipes(line).trim()
        separatorRegexLoose.containsMatchIn(t)
    }
    return detectedIndex != -1
}

// 浠庢暣娈垫枃鏈腑鎻愬彇绗竴寮?Markdown 琛ㄦ牸锛岃繑鍥?(琛ㄦ牸鍓嶆枃鏈? 琛ㄦ牸鏂囨湰, 琛ㄦ牸鍚庢枃鏈?
private fun splitByFirstMarkdownTable(content: String): Triple<String, String, String> {
    val rawLines = content.lines()
    fun normPipes(s: String): String {
        val barLikes = setOf('｜','│','┃','∣','︱','ǀ','❘','❙','❚','⎪','￨')
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(if (ch in barLikes) '|' else ch)
        }
        return sb.toString()
    }
    // 鏀惧锛氬垎闅旀ā寮忔棤闇€閿氬畾鍒拌灏撅紝渚夸簬浠庡悓琛屼腑鍒囧嚭灏鹃儴鏁版嵁
    val separatorRegexLoose = Regex("^\\s*\\|?\\s*:?[-]{2,}:?\\s*(\\|\\s*:?[-]{2,}:?\\s*)+\\|?\\s*")

    // 鎵惧埌绗竴鏉″垎闅旇锛堝鏉惧尮閰嶏級
    var sepIdx = -1
    var sepMatch: MatchResult? = null
    for (i in rawLines.indices) {
        val t = normPipes(rawLines[i]).trim()
        val mr = separatorRegexLoose.find(t)
        if (mr != null) {
            sepIdx = i
            sepMatch = mr
            break
        }
    }
    if (sepIdx <= 0 || sepMatch == null) return Triple(content, "", "")

    // 琛ㄥご琛岋細鍚戜笂鎵剧涓€鏉♀€滃惈绠￠亾涓旈潪绌衡€濈殑琛?
    var headerIdx = sepIdx - 1
    while (headerIdx >= 0) {
        val ht = normPipes(rawLines[headerIdx])
        if (ht.isNotBlank() && ht.contains("|")) break
        headerIdx--
    }
    if (headerIdx < 0) return Triple(content, "", "")

    // 璁＄畻 start/end锛屽苟澶勭悊鈥滃垎闅旇鍚庢嫾鎺ヤ簡棣栬鏁版嵁鈥濈殑灏惧反
    val start = headerIdx
    val lines = rawLines.toMutableList()

    // 鍙栧垎闅旂墖娈典笌灏鹃儴鏁版嵁
    val sepLineNorm = normPipes(lines[sepIdx])
    val matchedSep = sepMatch!!.value.trim()
    val tail = sepLineNorm.substring(sepMatch!!.range.last + 1).trim()

    // 鐢ㄧ函鍒嗛殧琛屾浛鎹㈠師 sepIdx 琛?
    lines[sepIdx] = matchedSep

    // 濡傛灉 tail 瀛樺湪锛屼綔涓虹涓€鏉℃暟鎹鎻掑叆鍒?sepIdx+1
    var end = sepIdx + 1
    if (tail.isNotEmpty()) {
        val firstData = if (tail.startsWith("|")) tail else "| $tail |"
        lines.add(end, firstData)
        end++ // 鎸囧悜涓嬩竴琛?
    }

    // 缁х画鍚戜笅鍚炲苟鏁版嵁琛?
    while (end < lines.size) {
        val dt = normPipes(lines[end])
        if (dt.isBlank()) {
            // 绌鸿绠椾綔琛ㄦ牸鍧楃殑缁堟锛屼笌鍚庢枃鍒嗛殧
            break
        }
        if (!dt.contains("|")) break
        end++
    }


    val before = lines.take(start).joinToString("\n").trimEnd()
    val tableBlock = lines.subList(start, end).joinToString("\n").trim()
    val after = if (end < lines.size) lines.drop(end).joinToString("\n").trimStart() else ""


    return Triple(before, tableBlock, after)
}

@Composable
fun StableMarkdownText(
    markdown: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    SafeMarkdownText(markdown = markdown, style = style, modifier = modifier)
}

// 灏嗗唴鑱斾唬鐮佽浆鎹负鍥存爮浠ｇ爜鍧楁牸寮忥紝浠ヤ究浣跨敤CodePreview缁勪欢
private fun convertInlineToFencedCode(text: String): String {
    if (!text.contains('`')) return text
    val segments = splitInlineCodeSegments(text)
    val result = StringBuilder()
    
    segments.forEach { segment ->
        if (segment.isCode) {
            // 杞崲涓哄洿鏍忎唬鐮佸潡鏍煎紡
            result.append("\n```\n")
            result.append(segment.text)
            result.append("\n```\n")
        } else {
            result.append(segment.text)
        }
    }
    
    return result.toString()
}

private data class InlineSegment(val text: String, val isCode: Boolean)

private fun splitInlineCodeSegments(text: String): List<InlineSegment> {
    if (text.isEmpty()) return listOf(InlineSegment("", false))
    val res = mutableListOf<InlineSegment>()
    val sb = StringBuilder()
    var inCode = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '`') {
            val escaped = i > 0 && text[i - 1] == '\\'
            if (!escaped) {
                if (sb.isNotEmpty()) {
                    res += InlineSegment(sb.toString(), inCode)
                    sb.clear()
                }
                inCode = !inCode
            } else {
                sb.append('`')
            }
        } else {
            sb.append(c)
        }
        i++
    }
    if (sb.isNotEmpty()) res += InlineSegment(sb.toString(), inCode)
    if (res.isNotEmpty() && res.last().isCode) {
        val merged = buildString {
            res.forEach { seg ->
                if (seg.isCode) append('`')
                append(seg.text)
            }
        }
        return listOf(InlineSegment(merged, false))
    }
    return res
}

// 璇嗗埆鈥滅缉杩涘紡浠ｇ爜鍧椻€濆伐鍏凤細杩炵画涓よ鍙婁互涓婁互4绌烘牸鎴朤ab寮€澶寸殑鏂囨湰
private data class IndentedBlock(val range: IntRange, val code: String)
private data class IndentedBlocks(val blocks: List<IndentedBlock>)

private fun extractIndentedCodeBlocks(text: String): IndentedBlocks? {
    if (text.isBlank()) return null
    val lines = text.split("\n")
    val blocks = mutableListOf<IndentedBlock>()
    var i = 0
    var offset = 0
    while (i < lines.size) {
        val line = lines[i]
        val isIndented = line.startsWith("    ") || line.startsWith("\t")
        if (!isIndented) {
            offset += line.length + if (i != lines.lastIndex) 1 else 0
            i++
            continue
        }
        val startOffset = offset
        val startLine = i
        val buf = StringBuilder()
        var count = 0
        while (i < lines.size) {
            val cur = lines[i]
            if (cur.startsWith("    ") || cur.startsWith("\t")) {
                val stripped = if (cur.startsWith("\t")) cur.removePrefix("\t") else cur.removePrefix("    ")
                buf.append(stripped)
                if (i != lines.lastIndex) buf.append("\n")
                offset += cur.length + if (i != lines.lastIndex) 1 else 0
                i++
                count++
            } else {
                break
            }
        }
        if (count >= 2) {
            // 浠呭皢鈥滆嚦灏戜袱琛屸€濈殑杩炵画缂╄繘琛岃涓轰唬鐮佸潡
            val endOffsetExclusive = offset
            blocks += IndentedBlock(IntRange(startOffset, endOffsetExclusive - 1), buf.toString().trimEnd('\n'))
        } else {
            // 鍗曡缂╄繘鍥為€€涓烘櫘閫氭枃鏈?
        }
    }
    return if (blocks.isEmpty()) null else IndentedBlocks(blocks)
}

private fun unwrapFileExtensionsInBackticks(text: String): String {
    val regex = Regex("`\\.(?:[a-zA-Z0-9]{2,10})`")
    if (!regex.containsMatchIn(text)) return text
    return text.replace(regex) { mr -> mr.value.removePrefix("`").removeSuffix("`") }
}

private fun normalizeHeadingsForSimplePath(text: String): String {
    if (text.isBlank()) return text
    val lines = text.lines().map { line ->
        var l = line
        if (l.startsWith("#")) {
            val count = l.takeWhile { it == '#' }.length
            l = "#".repeat(count) + l.drop(count)
        }
        l = l.replace(Regex("^(\\s*#{1,6})([^#\\s])")) { mr ->
            "${mr.groups[1]!!.value} ${mr.groups[2]!!.value}"
        }
        l
    }
    return lines.joinToString("\n")
}

/**
* 瀹芥澗鐨勫洿鏍忎唬鐮佹覆鏌撳櫒锛?
* - 浠呬緷鎹?鈥渀``鈥?鍒嗗壊濂囧伓娈碉紱鍋舵暟娈佃涓哄洿鏍忓ご锛堝彲鑳藉寘鍚瑷€涓庡悓涓€琛屼唬鐮侊級
* - 鍏佽缂哄皯缁撳熬 ```锛涙渶鍚庝竴涓唬鐮佹涔熶細鐢?CodePreview 娓叉煋
* - 灏芥渶澶у姫鍔涙彁鍙栬瑷€锛氬紑澶磋鐨勨€渀``lang鈥濅綔涓鸿瑷€锛岀揣闅忓叾鍚庣殑鍚屼竴琛屾畫浣欐嫾鍒颁唬鐮佹鏂?
*/
@Composable
private fun FallbackFencedRenderer(
   raw: String,
   textColor: Color,
   style: TextStyle
) {
   val parts = raw.split("```")
   // 娌℃湁瓒冲鐨勭墖娈碉紝鐩存帴鎸夋櫘閫氭枃鏈覆鏌?
   if (parts.size <= 1) {
       SmartTextRenderer(
           text = raw,
           textColor = textColor,
           style = style,
           modifier = Modifier.fillMaxWidth()
       )
       return
   }

   Column(modifier = Modifier.fillMaxWidth()) {
       var i = 0
       while (i < parts.size) {
           val segment = parts[i]
           if (i % 2 == 0) {
               // 闈炰唬鐮佹
               if (segment.isNotBlank()) {
                   SmartTextRenderer(
                       text = segment,
                       textColor = textColor,
                       style = style,
                       modifier = Modifier.fillMaxWidth()
                   )
               }
           } else {
               // 浠ｇ爜娈碉紙鍙兘鏄笉瑙勮寖鐨勫紑澶?缂哄皯闂悎锛?
               val lines = segment.lines()
               val header = lines.firstOrNull() ?: ""
               val lang = header.trim().takeWhile { !it.isWhitespace() }
               val restFirst = header.removePrefix(lang).trimStart()
               val body = buildString {
                   if (restFirst.isNotEmpty()) appendLine(restFirst)
                   if (lines.size > 1) {
                       append(lines.drop(1).joinToString("\n"))
                   }
               }.trimEnd()

               Spacer(modifier = Modifier.height(8.dp))
               CodePreview(
                   code = body,
                   language = if (lang.isBlank()) null else lang,
                   modifier = Modifier.fillMaxWidth()
               )
               Spacer(modifier = Modifier.height(8.dp))
           }
           i++
       }
   }
}

object RenderingMonitor {
    private const val TAG = "MarkdownRendering"
    
    fun logRenderingIssue(messageId: String, issue: String, content: String) {
        android.util.Log.w(TAG, "娑堟伅$messageId 娓叉煋闂: $issue")
        android.util.Log.v(TAG, "闂鍐呭鎽樿: ${content.take(100)}...")
    }
    
    fun trackRenderingPerformance(messageId: String, startTime: Long) {
        val duration = System.currentTimeMillis() - startTime
        if (duration > 1000) {
            android.util.Log.w(TAG, "娑堟伅$messageId 娓叉煋鑰楁椂: ${duration}ms")
        } else {
            android.util.Log.v(TAG, "娑堟伅$messageId 娓叉煋瀹屾垚: ${duration}ms")
        }
    }
    
    fun validateMarkdownOutput(content: String): Pair<Boolean, List<String>> {
        val issues = mutableListOf<String>()
        val fenceCount = Regex("```").findAll(content).count()
        if (fenceCount % 2 != 0) {
            issues.add("Unclosed code fence")
        }
        val tableLines = content.lines().map { it.trim() }.filter { it.isNotEmpty() && it.contains("|") }
        if (tableLines.isNotEmpty()) {
            val separatorRegex = Regex("^\\|?\\s*:?[-]{3,}:?\\s*(\\|\\s*:?[-]{3,}:?\\s*)+\\|?$")
            val hasSeparator = tableLines.any { separatorRegex.containsMatchIn(it) }
            if (!hasSeparator) {
                issues.add("Table missing separator row")
            }
        }
        return issues.isEmpty() to issues
    }
}
