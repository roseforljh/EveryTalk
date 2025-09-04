package com.example.everytalk.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.net.URLEncoder
import android.webkit.JavascriptInterface
import android.view.ViewGroup

/**
 * 使用KaTeX的数学公式渲染组件
 * 支持完整的LaTeX数学公式渲染
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathView(
    latex: String,
    isDisplay: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 16.sp,
    delayMs: Long = 0L,
    onLongPress: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()
    val colorHex = String.format("#%06X", 0xFFFFFF and textColor.toArgb())
    
    // 设置完全透明的背景色，避免大白块问题
    val backgroundColor = "transparent"
    // 使用传入的textColor参数，而不是硬编码
    val mathTextColor = colorHex
    
    // 延迟渲染状态
    var shouldRender by remember { mutableStateOf(delayMs == 0L) }
    
    // 延迟渲染逻辑
    LaunchedEffect(latex, delayMs) {
        if (delayMs > 0L) {
            shouldRender = false
            delay(delayMs)
            shouldRender = true
        } else {
            // 当delayMs为0时，立即显示
            shouldRender = true
        }
    }
    
    // 预编码公式内容，骨架HTML与数据分离，避免每次全量重载
    val encodedLatex = remember(latex) { URLEncoder.encode(latex, "UTF-8").replace("+", "%20") }
    val htmlShell = remember(isDisplay, mathTextColor, backgroundColor, textSize.value) {
        createMathHtmlShell(isDisplay, mathTextColor, backgroundColor, textSize.value)
    }
    
    if (shouldRender) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            // 初次加载后仅通过 JS 渲染内容，避免整页重载造成卡顿
                            this@apply.evaluateJavascript("renderLatex('$encodedLatex');", null)
                            this@apply.alpha = 1f
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun setHeight(px: Float) {
                            this@apply.post {
                                val density = resources.displayMetrics.density
                                val h = (px * density).toInt()
                                val lp = layoutParams ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
                                lp.height = h
                                layoutParams = lp
                                requestLayout()
                            }
                        }
                    }, "AndroidResize")
                     isHorizontalScrollBarEnabled = true
                     isVerticalScrollBarEnabled = false
                    setOnLongClickListener {
                        onLongPress?.invoke()
                        true
                    }
                    isLongClickable = true
                    // 允许父级拦截，避免与外层Compose冲突
                    requestDisallowInterceptTouchEvent(false)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    alpha = 0f
                    
                    loadDataWithBaseURL("file:///android_asset/", htmlShell, "text/html", "UTF-8", null)
                }
            },
            update = { webView ->
                // 仅当公式内容变化时通过 JS 重新渲染，避免整页重载
                if (webView.tag != encodedLatex.hashCode()) {
                    webView.tag = encodedLatex.hashCode()
                    webView.evaluateJavascript("renderLatex('$encodedLatex');", null)
                }
            }
        )
    }
}

private fun createMathHtmlContent(
    latex: String,
    isDisplay: Boolean,
    mathTextColor: String,
    backgroundColor: String,
    fontSize: Float
): String {
    val displayMode = if (isDisplay) "true" else "false"
    // 安全地传递LaTeX：进行URL编码，在JS中decode，避免模板字符串/引号/反斜杠破坏
    val encodedLatex = URLEncoder.encode(latex, "UTF-8").replace("+", "%20")
    
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <link rel="stylesheet" href="file:///android_asset/katex.min.css">
            <script src="file:///android_asset/katex.min.js"></script>
            <script src="file:///android_asset/auto-render.min.js"></script>
            <style>
                :root { color-scheme: light dark; }
                body {
                    margin: 0;
                    padding: 0; /* 去掉默认内边距，防止外部容器与公式重复留白 */
                    font-size: ${fontSize}px;
                    color: $mathTextColor;
                    background: transparent;
                    /* 使用系统衬线字体作为后备，避免缺少 KaTeX 字体时的错位 */
                    font-family: 'KaTeX_Main', 'Times New Roman', 'Noto Serif', 'DejaVu Serif', serif;
                    width: 100%;
                    max-width: 100%;
                    box-sizing: border-box;
                    overflow-x: hidden;
                }
                /* KaTeX 容器 */
                #math-outer {
                    display: block;
                    width: 100%;
                    max-width: 100%;
                    overflow-x: auto; /* 复杂公式可横向滚动，避免溢出 */
                    -webkit-overflow-scrolling: touch;
                    padding: 10px 0; /* 适度增大垂直留白 */
                }
                .katex {
                    color: $mathTextColor !important;
                    background: transparent !important;
                    display: inline-block;
                    max-width: 100%;
                    line-height: 1.26;
                }
                .katex * { background: transparent !important; color: inherit !important; }
                .katex-display {
                    margin: 1.2em 0;
                    text-align: left;
                    background: transparent;
                    padding: 0;
                    border-radius: 0;
                    display: block;
                    max-width: 100%;
                    overflow-x: auto; /* 显示模式下也允许横向滚动 */
                    white-space: normal;
                    word-wrap: normal;
                    overflow-wrap: normal;
                }
                .katex .base { color: $mathTextColor !important; }
                html, body { width: 100%; max-width: 100%; overflow-x: hidden; }
                /* 优化滚动条样式 */
                ::-webkit-scrollbar { height: 4px; }
                ::-webkit-scrollbar-track { background: transparent; }
                ::-webkit-scrollbar-thumb { background: rgba(128, 128, 128, 0.3); border-radius: 2px; }
                ::-webkit-scrollbar-thumb:hover { background: rgba(128, 128, 128, 0.5); }
                /* Improve vertical breathing for fractions and display math */
                .katex .mfrac .frac-line { border-top-width: 0.09em; }
                .katex .mfrac .numerator { padding-bottom: 0.18em; }
                .katex .mfrac .denominator { padding-top: 0.18em; }
            </style>
        </head>
        <body>
            <div id="math-outer"><div id="math-content"></div></div>
            <script>
                try {
                    const encoded = "$encodedLatex";
                    const latex = decodeURIComponent(encoded);
                    const mathContent = document.getElementById('math-content');
                    katex.render(latex, mathContent, {
                        displayMode: $displayMode,
                        throwOnError: false,
                        errorColor: '$mathTextColor',
                        output: 'htmlAndMathml',
                        strict: 'ignore',
                        minRuleThickness: 0.09,
                        macros: {
                            "\\RR": "\\mathbb{R}",
                            "\\NN": "\\mathbb{N}",
                            "\\ZZ": "\\mathbb{Z}",
                            "\\QQ": "\\mathbb{Q}",
                            "\\CC": "\\mathbb{C}"
                        }
                    });
                    try {
                        const outer = document.getElementById('math-outer');
                        const h = outer ? outer.scrollHeight : document.body.scrollHeight;
                        if (window.AndroidResize && AndroidResize.setHeight) { AndroidResize.setHeight(h); }
                    } catch (e) {}
                } catch (error) {
                    const el = document.getElementById('math-content');
                    if (el) {
                        el.innerHTML = '<span style="color: red;">Math rendering error: ' + (error && error.message ? error.message : String(error)) + '</span>';
                    }
                }
            </script>
        </body>
        </html>
    """.trimIndent()
}

private fun createMathHtmlShell(
    isDisplay: Boolean,
    mathTextColor: String,
    backgroundColor: String,
    fontSize: Float
): String {
    val displayMode = if (isDisplay) "true" else "false"
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <link rel="stylesheet" href="file:///android_asset/katex.min.css">
            <script src="file:///android_asset/katex.min.js"></script>
            <script src="file:///android_asset/auto-render.min.js"></script>
            <style>
                :root { color-scheme: light dark; }
                body {
                    margin: 0;
                    padding: 0;
                    font-size: ${fontSize}px;
                    color: $mathTextColor;
                    background: transparent;
                    font-family: 'KaTeX_Main', 'Times New Roman', 'Noto Serif', 'DejaVu Serif', serif;
                    width: 100%;
                    max-width: 100%;
                    box-sizing: border-box;
                    overflow-x: hidden;
                }
                #math-outer { display:block; width:100%; max-width:100%; overflow-x:auto; -webkit-overflow-scrolling:touch; padding:10px 0; }
                .katex { color:$mathTextColor !important; background:transparent !important; display:inline-block; max-width:100%; line-height: 1.28; }
                .katex * { background: transparent !important; color: inherit !important; }
                .katex-display { margin:1.2em 0; text-align:left; background:transparent; padding:0; border-radius:0; display:block; max-width:100%; overflow-x:auto; white-space:normal; word-wrap:normal; overflow-wrap:normal; }
                html, body { width: 100%; max-width: 100%; overflow-x: hidden; }
                ::-webkit-scrollbar { height: 4px; }
                ::-webkit-scrollbar-track { background: transparent; }
                ::-webkit-scrollbar-thumb { background: rgba(128, 128, 128, 0.3); border-radius: 2px; }
                ::-webkit-scrollbar-thumb:hover { background: rgba(128, 128, 128, 0.5); }
                /* Improve vertical breathing for fractions and display math */
                .katex .mfrac .frac-line { border-top-width: 0.09em; }
                .katex .mfrac .numerator { padding-bottom: 0.18em; }
                .katex .mfrac .denominator { padding-top: 0.18em; }
            </style>
            <script>
                window.renderLatex = function(encoded) {
                    try {
                        var target = document.getElementById('math-content');
                        if (!target) return;
                        var latex = decodeURIComponent(encoded);
                        katex.render(latex, target, {
                            displayMode: $displayMode,
                            throwOnError: false,
                            errorColor: '$mathTextColor',
                            output: 'htmlAndMathml',
                            strict: 'ignore',
                            minRuleThickness: 0.09,
                            macros: {"\\RR":"\\mathbb{R}","\\NN":"\\mathbb{N}","\\ZZ":"\\mathbb{Z}","\\QQ":"\\mathbb{Q}","\\CC":"\\mathbb{C}"}
                        });
                        var allMathElements = target.querySelectorAll('*');
                        for (var i = 0; i < allMathElements.length; i++) { allMathElements[i].style.color = '$mathTextColor'; }
                        try { var outer = document.getElementById('math-outer'); var h = outer ? outer.scrollHeight : document.body.scrollHeight; if (window.AndroidResize && AndroidResize.setHeight) { AndroidResize.setHeight(h); } } catch(e){}
                    } catch (e) {}
                }
            </script>
        </head>
        <body>
            <div id="math-outer"><div id="math-content"></div></div>
        </body>
        </html>
    """.trimIndent()
}

/**
 * 简化版数学公式组件，用于简单的数学表达式
 * 当KaTeX不可用时的后备方案
 */
@Composable
fun SimpleMathView(
    expression: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    textSize: TextUnit = 14.sp
) {
    Text(
        text = formatMathExpression(expression),
        modifier = modifier,
        color = textColor,
        fontSize = textSize
    )
}

/**
 * 智能数学公式组件 - 根据表达式复杂度自动选择渲染方式
 * 策略更新：
 * 1) 只要检测到任何 LaTeX 控制序列、分隔符、反斜杠命令或数学结构，优先使用 KaTeX，避免 SimpleMathView 破坏语法；
 * 2) SimpleMathView 仅在表达式是纯文本（不含反斜杠命令、花括号、美元符）时作为后备；
 */
@Composable
fun SmartMathView(
    expression: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    textSize: TextUnit = 16.sp,
    isDisplay: Boolean = false,
    delayMs: Long = 0L
) {
    val trimmed = expression.trim()

    // 明确的 LaTeX 指示：包含 $ 或 $$ 或 \ 开头的命令，或常见环境/命令
    val looksLikeLatex = remember(trimmed) {
        // 支持 $, $$, \\(...\\), \\[...\\] 分隔符；以及常见命令/花括号判断
        val hasDollarDelimiters = Regex("(?<!\\\\)\\$\\$|(?<!\\\\)\\$").containsMatchIn(trimmed)
        val hasBracketDelimiters = Regex("\\\\\\[|\\\\\\]").containsMatchIn(trimmed)
        val hasParenDelimiters = Regex("\\\\\\(|\\\\\\)").containsMatchIn(trimmed)
        val hasCommands = Regex("\\\\(frac|sqrt|sum|int|lim|prod|binom|begin|end|over|underline|overline|text|mathbb|mathrm|mathbf|vec|hat|bar|dot|ddot|left|right|pm|times|div|leq|geq|neq|approx|to|rightarrow|leftarrow)").containsMatchIn(trimmed)
        val hasBraces = trimmed.contains('{') && trimmed.contains('}')
        hasDollarDelimiters || hasBracketDelimiters || hasParenDelimiters || hasCommands || hasBraces
    }

    if (looksLikeLatex) {
        MathView(
            latex = trimmed,
            isDisplay = isDisplay,
            textColor = textColor,
            modifier = modifier,
            textSize = textSize,
            delayMs = delayMs
        )
    } else {
        SimpleMathView(
            expression = trimmed,
            modifier = modifier,
            textColor = textColor,
            textSize = textSize
        )
    }
}

/**
 * 向后兼容的旧版本API别名
 */
@Composable
fun WebMathView(
    latex: String,
    isDisplay: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
    delayMs: Long = 0L
) {
    MathView(latex, isDisplay, textColor, modifier, delayMs = delayMs)
}

/**
 * 非破坏性的符号友好替换，仅用于纯文本展示。
 * 不再删除花括号/美元符号，避免破坏合法的 LaTeX。
 */
private fun formatMathExpression(latex: String): String {
    // 若包含任何可能的 LaTeX 控制符或分隔符，则直接返回原文，让 SmartMathView 选择 KaTeX
    if (latex.contains('\\') || latex.contains('{') || latex.contains('}') || latex.contains('$')) {
        return latex
    }
    return latex
        .replace("\\u03B1", "α") // 容错：万一传入的是转义形式
        .replace("alpha", "α")
        .replace("beta", "β")
        .replace("gamma", "γ")
        .replace("delta", "δ")
        .replace("epsilon", "ε")
        .replace("theta", "θ")
        .replace("lambda", "λ")
        .replace("mu", "μ")
        .replace("pi", "π")
        .replace("sigma", "σ")
        .replace("phi", "φ")
        .replace("omega", "ω")
        .replace("infty", "∞")
        .replace("pm", "±")
        .replace("times", "×")
        .replace("div", "÷")
        .replace("leq", "≤")
        .replace("geq", "≥")
        .replace("neq", "≠")
        .replace("approx", "≈")
        .replace("->", "→")
        .replace("<-", "←")
        .replace("<->", "↔")
}


@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichMathTextView(
    textWithLatex: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 16.sp,
    delayMs: Long = 0L,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    onLongPress: (() -> Unit)? = null
) {
    val colorHex = String.format("#%06X", 0xFFFFFF and textColor.toArgb())
    val backgroundColorHex = String.format("#%06X", 0xFFFFFF and backgroundColor.toArgb())

    var shouldRender by remember { mutableStateOf(delayMs == 0L) }
    LaunchedEffect(textWithLatex, delayMs) {
        if (delayMs > 0L) {
            shouldRender = false
            delay(delayMs)
            shouldRender = true
        } else {
            shouldRender = true
        }
    }

    val htmlContent = remember(textWithLatex, colorHex, backgroundColorHex, textSize.value) {
        createRichMathHtmlContent(textWithLatex, colorHex, backgroundColorHex, textSize.value)
    }

    if (shouldRender) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            this@apply.alpha = 1f
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun setHeight(px: Float) {
                            this@apply.post {
                                val density = resources.displayMetrics.density
                                val h = (px * density).toInt()
                                val lp = layoutParams ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
                                lp.height = h
                                layoutParams = lp
                                requestLayout()
                            }
                        }
                    }, "AndroidResize")
                     isHorizontalScrollBarEnabled = true
                     isVerticalScrollBarEnabled = false
                    setOnLongClickListener {
                        onLongPress?.invoke()
                        true
                    }
                    isLongClickable = true
                    requestDisallowInterceptTouchEvent(false)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    alpha = 0f
                    loadDataWithBaseURL("file:///android_asset/", htmlContent, "text/html", "UTF-8", null)
                }
            },
            update = { webView ->
                if (webView.tag != htmlContent.hashCode()) {
                    webView.tag = htmlContent.hashCode()
                    webView.alpha = 0f
                    webView.loadDataWithBaseURL("file:///android_asset/", htmlContent, "text/html", "UTF-8", null)
                }
            }
        )
    }
}

private fun createRichMathHtmlContent(
    textWithLatex: String,
    textColor: String,
    backgroundColor: String,
    fontSize: Float
): String {
    val encoded = URLEncoder.encode(textWithLatex, "UTF-8").replace("+", "%20")
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8"/>
            <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
            <link rel="stylesheet" href="file:///android_asset/katex.min.css"/>
            <script src="file:///android_asset/katex.min.js"></script>
            <script src="file:///android_asset/auto-render.min.js"></script>
            <style>
                :root { color-scheme: light dark; }
                html, body { margin:0; padding:0; background: transparent; }
                body { color: $textColor; font-size: ${fontSize}px; line-height: 1.5; }
                #content {
                    color: $textColor; background: transparent; white-space: pre-wrap;
                    word-break: break-word; overflow-wrap: anywhere; width: 100%; line-height: 1.55;
                }
                #content, .katex-display { overflow-x: auto; -webkit-overflow-scrolling: touch; }
                .katex, .katex .base { color: $textColor !important; }
                .katex-display { margin: 1.2em 0; }
                /* 行内 code 与正文统一外观 */
                code { background: transparent !important; color: $textColor !important; font-weight: 400; padding: 0; border-radius: 0; }
                a { color: inherit; text-decoration: underline; }
                ul { margin: .25em 0; padding-left: 1.25em; }
                ol { margin: .25em 0; padding-left: 1.25em; }
                blockquote { border-left: 3px solid rgba(127,127,127,.35); margin: .5em 0; padding: .25em .75em; opacity: .95; }
                h1,h2,h3,h4,h5,h6 { margin: .8em 0 .4em; font-weight: 700; }
                h1{ font-size: 1.6em; } h2{ font-size: 1.4em; } h3{ font-size: 1.25em; }
                h4{ font-size: 1.15em; } h5{ font-size: 1.05em; } h6{ font-size: 1em; opacity:.95; }
                /* Fine-tune KaTeX internals to reduce vertical crowding inside formulas */
                .katex { line-height: 1.28; }
                .katex .mfrac .frac-line { border-top-width: 0.09em; }
                .katex .mfrac .numerator { padding-bottom: 0.18em; }
                .katex .mfrac .denominator { padding-top: 0.18em; }
            </style>
        </head>
        <body>
            <div id="content"></div>
            <script>
                function escapeHtml(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
                function simpleMarkdownToHtml(src){
                    function protectMathSegments(s){
                        const segs = [];
                        let out = '';
                        let i = 0;
                        function pushToken(seg){ const token = '§MATH' + segs.length + '§'; segs.push(seg); out += token; }
                        while(i < s.length){
                            if (s[i] === '\\'){
                                const two = s.substr(i,2);
                                if (two === '\\('){ const end = s.indexOf('\\)', i+2); if (end !== -1){ pushToken(s.substring(i, end+2)); i = end+2; continue; } }
                                else if (two === '\\['){ const end = s.indexOf('\\]', i+2); if (end !== -1){ pushToken(s.substring(i, end+2)); i = end+2; continue; } }
                                out += s[i++]; continue;
                            }
                            if (s[i] === '$'){
                                const isDouble = (i+1 < s.length && s[i+1] === '$');
                                if (isDouble){ const end = s.indexOf('$$', i+2); if (end !== -1){ pushToken(s.substring(i, end+2)); i = end+2; continue; } }
                                else {
                                    let j = i+1, found = -1;
                                    while(j < s.length){
                                        if (s[j] === '$' && s[j-1] !== '\\'){ found = j; break; }
                                        j++;
                                    }
                                    if (found !== -1){ pushToken(s.substring(i, found+1)); i = found+1; continue; }
                                }
                            }
                            out += s[i++];
                        }
                        return { text: out, segs };
                    }
                    // 纠正常见 LaTeX 小错误：将 \\text中文 自动补为 \\text{中文}
                    function fixCommonLatexTypos(seg){
                        try {
                            return seg.replace(/\\text(?!\\s*\\{)\\s*([\u4e00-\u9fa5A-Za-z0-9_]+)/g, '\\\\text{$1}');
                        } catch (e) { return seg; }
                    }
                    function restoreMath(text, segs){
                        for (let k=0;k<segs.length;k++){ text = text.replace('§MATH'+k+'§', segs[k]); }
                        return text;
                    }

                    let protectedObj = protectMathSegments(src);
                    protectedObj.segs = protectedObj.segs.map(fixCommonLatexTypos);
                    let s = escapeHtml(protectedObj.text);

                    s = s.replace(/`([^`]+?)`/g,'<code>$1</code>');
                    s = s.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g,'<a href=\"$2\" target=\"_blank\" rel=\"noopener noreferrer\">$1</a>');
                    // 支持 ASCII 与全角星号的加粗
                    s = s.replace(/(?:\*{2}|＊{2})([\s\S]+?)(?:\*{2}|＊{2})/g,'<strong>$1</strong>');
                    // 支持 ASCII 与全角星号的斜体（避免与加粗冲突）
                    s = s.replace(/(^|[^*＊])(?:\*|＊)([^*＊\n]+)(?:\*|＊)(?![*＊])/g,'$1<em>$2</em>');

                    const lines = s.split(/\n/);
                    let out = '';
                    let inUl = false, inOl = false, inQuote = false;
                    function closeAll(){ if(inUl){ out += '</ul>'; inUl=false; } if(inOl){ out += '</ol>'; inOl=false; } if(inQuote){ out += '</blockquote>'; inQuote=false; } }
                    for (let i=0;i<lines.length;i++){
                        const line = lines[i];
                        if (/^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/.test(line)) { closeAll(); out += '<hr/>'; continue; }
                        let hm = line.match(/^\s{0,3}(#{1,6})\s+(.+)$/);
                        if (hm){
                            closeAll();
                            const level = hm[1].length; const text = hm[2];
                            out += `<h${'$'}{level}>${'$'}{text}</h${'$'}{level}>`;
                            continue;
                        }
                        let qm = line.match(/^\s{0,3}>\s?(.*)$/);
                        if (qm){
                            if(!inQuote){ closeAll(); out += '<blockquote>'; inQuote = true; }
                            out += (qm[1] || '') + (i<lines.length-1?'\n':'');
                            continue;
                        } else if (inQuote && line.trim() === '') { out += '\n'; continue; }
                        else if (inQuote) { out += '</blockquote>'; inQuote = false; }
                        let om = line.match(/^\s{0,3}(\d+)\.\s+(.+)$/);
                        if (om){
                            if(!inOl){ closeAll(); out += '<ol>'; inOl = true; }
                            out += `<li>${'$'}{om[2]}</li>`; continue;
                        }
                        let um = line.match(/^\s{0,3}(?:[-*+])\s+(.+)$/);
                        if (um){
                            if(!inUl){ closeAll(); out += '<ul>'; inUl = true; }
                            out += `<li>${'$'}{um[1]}</li>`; continue;
                        }
                        closeAll();
                        out += line + (i<lines.length-1?'\n':'');
                    }
                    closeAll();

                    out = restoreMath(out, protectedObj.segs);

                    return out;
                }
                try {
                    const encoded = "$encoded";
                    const raw = decodeURIComponent(encoded);
                    const container = document.getElementById('content');
                    container.innerHTML = simpleMarkdownToHtml(raw);
                    renderMathInElement(container, {
                        delimiters: [
                            {left: '$$', right: '$$', display: true},
                            {left: '$', right: '$', display: false},
                            {left: '\\(', right: '\\)', display: false},
                            {left: '\\[', right: '\\]', display: true}
                        ],
                        throwOnError: false,
                        minRuleThickness: 0.09
                    });
                } catch (error) {
                    const el = document.getElementById('content');
                    if (el) {
                        el.innerHTML = '<span style="color: red;">Math rendering error: ' + (error && error.message ? error.message : String(error)) + '</span>';
                    }
                }
            </script>
        </body>
        </html>
    """.trimIndent()
}