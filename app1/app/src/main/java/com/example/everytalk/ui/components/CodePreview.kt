package com.example.everytalk.ui.components

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex

/**
 * 代码预览组件
 * 支持HTML、SVG、CSS等可视化代码的预览
 */
@Composable
fun CodePreviewButton(
    code: String,
    language: String?,
    modifier: Modifier = Modifier
) {
    var showPreview by remember { mutableStateOf(false) }
    
    // 判断是否支持预览
    val isPreviewable = isCodePreviewable(language, code)
    
    if (isPreviewable) {
        Surface(
            onClick = { showPreview = true },
            modifier = modifier,
            shape = RoundedCornerShape(16.dp), // 更大的圆角
            color = MaterialTheme.colorScheme.surfaceVariant, // 使用主题颜色
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Visibility,
                    contentDescription = "预览代码",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "预览代码",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                )
            }
        }
        
        if (showPreview) {
            CodePreviewDialog(
                code = code,
                language = language,
                onDismiss = { showPreview = false }
            )
        }
    }
}

/**
 * 代码预览对话框
 */
@Composable
private fun CodePreviewDialog(
    code: String,
    language: String?,
    onDismiss: () -> Unit
) {
    // 动画状态
    var visible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        visible = true
    }
    
    Dialog(
        onDismissRequest = {
            visible = false
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(
                animationSpec = tween(300, easing = EaseOutCubic)
            ) + scaleIn(
                initialScale = 0.8f,
                animationSpec = tween(300, easing = EaseOutCubic)
            ),
            exit = fadeOut(
                animationSpec = tween(200, easing = EaseInCubic)
            ) + scaleOut(
                targetScale = 0.8f,
                animationSpec = tween(200, easing = EaseInCubic)
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp), // 极窄的边距
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp)), // 更大的外圆角
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface, // 使用主题表面色
                    tonalElevation = 16.dp
                ) {
                    // 预览内容
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp) // 极窄的内边距
                            .clip(RoundedCornerShape(20.dp)), // 内容区域圆角
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.background, // 使用主题背景色
                        tonalElevation = 0.dp
                    ) {
                        CodePreviewWebView(
                            code = code,
                            language = language,
                            isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                        )
                    }
                }
            }
        }
    }
}

/**
 * WebView组件用于渲染代码预览
 */
@Composable
private fun CodePreviewWebView(
    code: String,
    language: String?,
    isDarkTheme: Boolean
) {
    val context = LocalContext.current
    
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                }
            }
        },
        update = { webView ->
            val htmlContent = generatePreviewHtml(code, language, isDarkTheme)
            webView.loadDataWithBaseURL(
                null,
                htmlContent,
                "text/html",
                "UTF-8",
                null
            )
        }
    )
}

/**
 * 生成预览用的HTML内容
 */
private fun generatePreviewHtml(code: String, language: String?, isDarkTheme: Boolean): String {
    // 预处理代码，支持Markdown和数学公式渲染
    val processedCode = preprocessCodeForRendering(code, language)
    
    // 根据主题选择颜色
    val backgroundColor = if (isDarkTheme) "#0D1117" else "#FFFFFF"
    val textColor = if (isDarkTheme) "#E6EDF3" else "#24292F"
    val surfaceColor = if (isDarkTheme) "#161B22" else "#F6F8FA"
    val borderColor = if (isDarkTheme) "#30363D" else "#D0D7DE"
    val codeBackgroundColor = if (isDarkTheme) "#0D1117" else "#F6F8FA"
    
    return when (language?.lowercase()) {
        "html" -> {
            // 如果是完整的HTML文档，直接使用
            if (code.contains("<html", ignoreCase = true) || code.contains("<!doctype", ignoreCase = true)) {
                processedCode
            } else {
                // 如果只是HTML片段，包装在完整的HTML文档中
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>HTML Preview</title>
                    <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
                    <script src="https://polyfill.io/v3/polyfill.min.js?features=es6"></script>
                    <script id="MathJax-script" async src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js"></script>
                    <script>
                        window.MathJax = {
                            tex: {
                                inlineMath: [['$', '$'], ['\\(', '\\)']],
                                displayMath: [['$$', '$$'], ['\\[', '\\]']]
                            }
                        };
                    </script>
                    <style>
                        body {
                            margin: 16px;
                            font-family: system-ui, -apple-system, sans-serif;
                            background-color: $backgroundColor;
                            color: $textColor;
                        }
                    </style>
                </head>
                <body>
                    $processedCode
                </body>
                </html>
                """.trimIndent()
            }
        }
        "svg", "xml" -> {
            // 对于XML，检查是否是SVG内容
            if (language?.lowercase() == "xml" && !code.contains("<svg", ignoreCase = true)) {
                // 如果是XML但不是SVG，显示为代码文本（支持Markdown和数学公式）
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Code Preview</title>
                    <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
                    <script src="https://polyfill.io/v3/polyfill.min.js?features=es6"></script>
                    <script id="MathJax-script" async src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js"></script>
                    <script>
                        window.MathJax = {
                            tex: {
                                inlineMath: [['$', '$'], ['\\(', '\\)']],
                                displayMath: [['$$', '$$'], ['\\[', '\\]']]
                            }
                        };
                    </script>
                    <style>
                        body {
                            margin: 16px;
                            font-family: monospace;
                            background: $backgroundColor;
                            color: $textColor;
                        }
                        pre {
                            background: $surfaceColor;
                            color: $textColor;
                            padding: 16px;
                            border-radius: 8px;
                            overflow: auto;
                            border: 1px solid $borderColor;
                        }
                        .rendered-content {
                            background: $surfaceColor;
                            color: $textColor;
                            padding: 16px;
                            border-radius: 8px;
                            margin-top: 10px;
                            border: 1px solid $borderColor;
                        }
                    </style>
                </head>
                <body>
                    <h3>代码内容 (${language ?: "未知语言"})</h3>
                    <pre><code>${code.replace("<", "&lt;").replace(">", "&gt;")}</code></pre>
                    <div class="rendered-content" id="rendered"></div>
                    <script>
                        // 渲染Markdown和数学公式
                        const processedContent = `${processedCode.replace("`", "\\`")}`;
                        document.getElementById('rendered').innerHTML = marked.parse(processedContent);
                        if (window.MathJax) {
                            MathJax.typesetPromise([document.getElementById('rendered')]);
                        }
                    </script>
                </body>
                </html>
                """.trimIndent()
            } else {
                // SVG内容（支持SVG中的文本Markdown渲染）
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>SVG Preview</title>
                    <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
                    <script src="https://polyfill.io/v3/polyfill.min.js?features=es6"></script>
                    <script id="MathJax-script" async src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js"></script>
                    <script>
                        window.MathJax = {
                            tex: {
                                inlineMath: [['$', '$'], ['\\(', '\\)']],
                                displayMath: [['$$', '$$'], ['\\[', '\\]']]
                            }
                        };
                    </script>
                    <style>
                        body {
                            margin: 0;
                            padding: 16px;
                            display: flex;
                            flex-direction: column;
                            justify-content: center;
                            align-items: center;
                            min-height: 100vh;
                            background: $backgroundColor;
                            color: $textColor;
                        }
                        svg {
                            max-width: 100%;
                            max-height: 80vh;
                            background: $surfaceColor;
                            border-radius: 8px;
                            border: 1px solid $borderColor;
                        }
                        .description {
                            background: $surfaceColor;
                            color: $textColor;
                            padding: 16px;
                            border-radius: 8px;
                            margin-top: 10px;
                            border: 1px solid $borderColor;
                            max-width: 600px;
                        }
                    </style>
                </head>
                <body>
                    $processedCode
                    <div class="description" id="description"></div>
                    <script>
                        // 提取SVG中的描述文本并渲染Markdown
                        const svgElement = document.querySelector('svg');
                        if (svgElement) {
                            const titleElement = svgElement.querySelector('title');
                            const descElement = svgElement.querySelector('desc');
                            let description = '';
                            if (titleElement) description += '**' + titleElement.textContent + '**\\n\\n';
                            if (descElement) description += descElement.textContent;
                            
                            if (description.trim()) {
                                document.getElementById('description').innerHTML = marked.parse(description);
                                if (window.MathJax) {
                                    MathJax.typesetPromise([document.getElementById('description')]);
                                }
                            } else {
                                document.getElementById('description').style.display = 'none';
                            }
                        }
                    </script>
                </body>
                </html>
                """.trimIndent()
            }
        }
        "markdown", "md" -> {
            // Markdown渲染
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Markdown Preview</title>
                <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
                <style>
                    body {
                        margin: 0;
                        padding: 20px;
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        line-height: 1.6;
                        background: $backgroundColor;
                        color: $textColor;
                    }
                    .content {
                        max-width: 800px;
                        margin: 0 auto;
                        background: $surfaceColor;
                        color: $textColor;
                        padding: 30px;
                        border-radius: 8px;
                        border: 1px solid $borderColor;
                    }
                    h1, h2, h3 { color: $textColor; }
                    code { background: $codeBackgroundColor; color: $textColor; padding: 2px 4px; border-radius: 3px; }
                    pre { background: $codeBackgroundColor; color: $textColor; padding: 15px; border-radius: 5px; overflow-x: auto; border: 1px solid $borderColor; }
                    blockquote { border-left: 4px solid $borderColor; margin: 0; padding-left: 20px; color: $textColor; opacity: 0.8; }
                </style>
            </head>
            <body>
                <div class="content" id="content"></div>
                <script>
                    const markdown = `${code.replace("`", "\\`")}`;
                    document.getElementById('content').innerHTML = marked.parse(markdown);
                </script>
            </body>
            </html>
            """.trimIndent()
        }
        "mermaid" -> {
            // Mermaid图表
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Mermaid Diagram</title>
                <script src="https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js"></script>
                <style>
                    body {
                        margin: 0;
                        padding: 20px;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        min-height: 100vh;
                        background: $backgroundColor;
                        color: $textColor;
                        font-family: Arial, sans-serif;
                    }
                    .mermaid {
                        background: $surfaceColor;
                        padding: 20px;
                        border-radius: 8px;
                        border: 1px solid $borderColor;
                    }
                </style>
            </head>
            <body>
                <div class="mermaid">
                    $code
                </div>
                <script>
                    mermaid.initialize({ startOnLoad: true, theme: ${if (isDarkTheme) "'dark'" else "'default'"} });
                </script>
            </body>
            </html>
            """.trimIndent()
        }
        "plantuml", "puml", "uml" -> {
            // PlantUML图表
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>PlantUML Diagram</title>
                <style>
                    body { 
                        margin: 0; 
                        padding: 20px; 
                        display: flex; 
                        flex-direction: column;
                        justify-content: center; 
                        align-items: center; 
                        min-height: 100vh;
                        background: #f5f5f5;
                        font-family: Arial, sans-serif;
                    }
                    .diagram {
                        background: white;
                        padding: 20px;
                        border-radius: 8px;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
                        text-align: center;
                    }
                    .note {
                        margin-top: 20px;
                        padding: 10px;
                        background: #fff3cd;
                        border: 1px solid #ffeaa7;
                        border-radius: 4px;
                        color: #856404;
                    }
                </style>
            </head>
            <body>
                <div class="diagram">
                    <h3>PlantUML 图表</h3>
                    <p>PlantUML代码预览：</p>
                    <pre style="text-align: left; background: #f8f9fa; padding: 15px; border-radius: 5px;">${code.replace("<", "&lt;").replace(">", "&gt;")}</pre>
                </div>
                <div class="note">
                    <strong>提示：</strong> PlantUML需要服务器端渲染。这里显示的是代码内容。
                </div>
            </body>
            </html>
            """.trimIndent()
        }
        "d3", "d3js" -> {
            // D3.js可视化
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>D3.js Visualization</title>
                <script src="https://d3js.org/d3.v7.min.js"></script>
                <style>
                    body { 
                        margin: 0; 
                        padding: 20px; 
                        font-family: Arial, sans-serif;
                        background: #f5f5f5;
                    }
                    #visualization {
                        background: white;
                        border-radius: 8px;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
                        padding: 20px;
                        text-align: center;
                    }
                </style>
            </head>
            <body>
                <div id="visualization">
                    <h3>D3.js 可视化</h3>
                </div>
                <script>
                    try {
                        $code
                    } catch (error) {
                        document.getElementById('visualization').innerHTML += '<div style="color: red; margin-top: 20px;">错误: ' + error.message + '</div>';
                    }
                </script>
            </body>
            </html>
            """.trimIndent()
        }
        "p5", "p5js" -> {
            // P5.js创意编程
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>P5.js Sketch</title>
                <script src="https://cdnjs.cloudflare.com/ajax/libs/p5.js/1.7.0/p5.min.js"></script>
                <style>
                    body { 
                        margin: 0; 
                        padding: 20px; 
                        display: flex; 
                        justify-content: center; 
                        align-items: center; 
                        min-height: 100vh;
                        background: #f5f5f5;
                        font-family: Arial, sans-serif;
                    }
                    main {
                        background: white;
                        border-radius: 8px;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
                        padding: 20px;
                    }
                </style>
            </head>
            <body>
                <main>
                    <h3 style="text-align: center; margin-top: 0;">P5.js 动画</h3>
                </main>
                <script>
                    try {
                        $code
                    } catch (error) {
                        document.body.innerHTML += '<div style="color: red; text-align: center; margin-top: 20px;">错误: ' + error.message + '</div>';
                    }
                </script>
            </body>
            </html>
            """.trimIndent()
        }
        "three", "threejs" -> {
            // Three.js 3D图形
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Three.js 3D Scene</title>
                <script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
                <style>
                    body { 
                        margin: 0; 
                        padding: 20px; 
                        background: #f5f5f5;
                        font-family: Arial, sans-serif;
                    }
                    #container {
                        background: white;
                        border-radius: 8px;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
                        padding: 20px;
                        text-align: center;
                    }
                    canvas {
                        border-radius: 4px;
                    }
                </style>
            </head>
            <body>
                <div id="container">
                    <h3>Three.js 3D 场景</h3>
                </div>
                <script>
                    try {
                        $code
                    } catch (error) {
                        document.getElementById('container').innerHTML += '<div style="color: red; margin-top: 20px;">错误: ' + error.message + '</div>';
                    }
                </script>
            </body>
            </html>
            """.trimIndent()
        }
        "chartjs", "chart" -> {
            // Chart.js图表
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Chart.js Visualization</title>
                <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                <style>
                    body { 
                        margin: 0; 
                        padding: 20px; 
                        background: #f5f5f5;
                        font-family: Arial, sans-serif;
                    }
                    .chart-container {
                        background: white;
                        border-radius: 8px;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
                        padding: 20px;
                        max-width: 800px;
                        margin: 0 auto;
                    }
                    canvas {
                        max-width: 100%;
                    }
                </style>
            </head>
            <body>
                <div class="chart-container">
                    <h3 style="text-align: center; margin-top: 0;">Chart.js 图表</h3>
                    <canvas id="myChart"></canvas>
                </div>
                <script>
                    try {
                        $code
                    } catch (error) {
                        document.querySelector('.chart-container').innerHTML += '<div style="color: red; margin-top: 20px;">错误: ' + error.message + '</div>';
                    }
                </script>
            </body>
            </html>
            """.trimIndent()
        }
        "canvas" -> {
            // HTML5 Canvas
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Canvas Drawing</title>
                <style>
                    body { 
                        margin: 0; 
                        padding: 20px; 
                        display: flex; 
                        justify-content: center; 
                        align-items: center; 
                        min-height: 100vh;
                        background: #f5f5f5;
                        font-family: Arial, sans-serif;
                    }
                    .canvas-container {
                        background: white;
                        border-radius: 8px;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
                        padding: 20px;
                        text-align: center;
                    }
                    canvas {
                        border: 1px solid #ddd;
                        border-radius: 4px;
                    }
                </style>
            </head>
            <body>
                <div class="canvas-container">
                    <h3>Canvas 绘图</h3>
                    <canvas id="canvas" width="400" height="300"></canvas>
                </div>
                <script>
                    try {
                        const canvas = document.getElementById('canvas');
                        const ctx = canvas.getContext('2d');
                        $code
                    } catch (error) {
                        document.querySelector('.canvas-container').innerHTML += '<div style="color: red; margin-top: 20px;">错误: ' + error.message + '</div>';
                    }
                </script>
            </body>
            </html>
            """.trimIndent()
        }
        "latex", "tex" -> {
            // LaTeX数学公式
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>LaTeX Math</title>
                <script src="https://polyfill.io/v3/polyfill.min.js?features=es6"></script>
                <script id="MathJax-script" async src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js"></script>
                <script>
                    window.MathJax = {
                        tex: {
                            inlineMath: [['$', '$'], ['\\(', '\\)']],
                            displayMath: [['$$', '$$'], ['\\[', '\\]']]
                        }
                    };
                </script>
                <style>
                    body { 
                        margin: 0; 
                        padding: 20px; 
                        font-family: 'Times New Roman', serif;
                        background: #f5f5f5;
                        line-height: 1.6;
                    }
                    .math-container {
                        background: white;
                        border-radius: 8px;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
                        padding: 30px;
                        max-width: 800px;
                        margin: 0 auto;
                    }
                </style>
            </head>
            <body>
                <div class="math-container">
                    <h3>LaTeX 数学公式</h3>
                    <div id="math-content">
                        $code
                    </div>
                </div>
            </body>
            </html>
            """.trimIndent()
        }
        "json" -> {
            // JSON数据可视化
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>JSON Data Visualization</title>
                <style>
                    body { 
                        margin: 0; 
                        padding: 20px; 
                        font-family: 'Monaco', 'Menlo', monospace;
                        background: #f5f5f5;
                    }
                    .json-container {
                        background: white;
                        border-radius: 8px;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
                        padding: 20px;
                        max-width: 800px;
                        margin: 0 auto;
                    }
                    pre {
                        background: #f8f9fa;
                        padding: 15px;
                        border-radius: 5px;
                        overflow-x: auto;
                        border-left: 4px solid #007bff;
                    }
                    .json-key { color: #d73a49; }
                    .json-string { color: #032f62; }
                    .json-number { color: #005cc5; }
                    .json-boolean { color: #e36209; }
                </style>
            </head>
            <body>
                <div class="json-container">
                    <h3>JSON 数据预览</h3>
                    <pre id="json-display"></pre>
                </div>
                <script>
                    try {
                        const jsonData = $code;
                        const formatted = JSON.stringify(jsonData, null, 2);
                        document.getElementById('json-display').textContent = formatted;
                    } catch (error) {
                        document.getElementById('json-display').innerHTML = '<span style="color: red;">JSON格式错误: ' + error.message + '</span>';
                    }
                </script>
            </body>
            </html>
            """.trimIndent()
        }
        "css" -> {
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>CSS Preview</title>
                <style>
                    $code
                </style>
            </head>
            <body>
                <div style="padding: 16px;">
                    <h1>CSS样式预览</h1>
                    <p>这是一个段落文本，用于展示CSS样式效果。</p>
                    <div class="demo-box" style="width: 200px; height: 100px; background: #e3f2fd; border: 1px solid #2196f3; margin: 16px 0; padding: 16px;">
                        演示容器
                    </div>
                    <button style="padding: 8px 16px; margin: 4px;">按钮示例</button>
                    <ul>
                        <li>列表项 1</li>
                        <li>列表项 2</li>
                        <li>列表项 3</li>
                    </ul>
                </div>
            </body>
            </html>
            """.trimIndent()
        }
        "javascript", "js" -> {
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>JavaScript Preview</title>
                <style>
                    body {
                        margin: 0;
                        padding: 16px;
                        font-family: system-ui, -apple-system, sans-serif;
                        background: $backgroundColor;
                        color: $textColor;
                    }
                    #output {
                        background: $surfaceColor;
                        color: $textColor;
                        border-radius: 8px;
                        padding: 16px;
                        margin-top: 16px;
                        border: 1px solid $borderColor;
                    }
                </style>
            </head>
            <body>
                <h2>JavaScript 代码执行结果</h2>
                <div id="output"></div>
                <script>
                    try {
                        // 重定向console.log到页面显示
                        const output = document.getElementById('output');
                        const originalLog = console.log;
                        console.log = function(...args) {
                            const div = document.createElement('div');
                            div.textContent = args.join(' ');
                            div.style.marginBottom = '8px';
                            output.appendChild(div);
                            originalLog.apply(console, args);
                        };
                        
                        // 执行用户代码
                        $code
                    } catch (error) {
                        document.getElementById('output').innerHTML = '<div style="color: red;">错误: ' + error.message + '</div>';
                    }
                </script>
            </body>
            </html>
            """.trimIndent()
        }
        else -> {
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Code Preview</title>
                <style>
                    body {
                        margin: 16px;
                        font-family: monospace;
                        background: $backgroundColor;
                        color: $textColor;
                    }
                    pre {
                        background: $surfaceColor;
                        color: $textColor;
                        padding: 16px;
                        border-radius: 8px;
                        overflow: auto;
                        border: 1px solid $borderColor;
                    }
                </style>
            </head>
            <body>
                <h3>代码内容 (${language ?: "未知语言"})</h3>
                <pre><code>${code.replace("<", "&lt;").replace(">", "&gt;")}</code></pre>
            </body>
            </html>
            """.trimIndent()
        }
    }
}

/**
 * 判断代码是否支持预览
 */
private fun isCodePreviewable(language: String?, code: String): Boolean {
    if (language == null && code.isBlank()) return false
    
    val lang = language?.lowercase()
    
    // 直接支持的语言
    when (lang) {
        // Web技术
        "html", "svg", "css", "javascript", "js" -> return true
        
        // 标记语言
        "markdown", "md" -> return true
        
        // 图表和可视化
        "mermaid" -> return true
        "plantuml", "puml", "uml" -> return true
        "d3", "d3js" -> return true
        "p5", "p5js" -> return true
        "three", "threejs" -> return true
        "chartjs", "chart" -> return true
        
        // 图形和动画
        "canvas" -> return true
        "webgl", "glsl" -> return true
        "processing" -> return true
        
        // 数学和科学
        "latex", "tex" -> return true
        "mathml" -> return true
        
        // 数据格式
        "json" -> {
            // 检查是否是可视化的JSON数据
            if (code.contains("\"type\"", ignoreCase = true) || 
                code.contains("\"data\"", ignoreCase = true) ||
                code.contains("\"chart\"", ignoreCase = true)) {
                return true
            }
        }
        
        "xml" -> {
            // 对于XML，检查是否是SVG或其他可视化格式
            if (code.contains("<svg", ignoreCase = true) ||
                code.contains("<mathml", ignoreCase = true)) {
                return true
            }
        }
    }
    
    // 基于内容的检测
    return code.contains("<html", ignoreCase = true) ||
           code.contains("<svg", ignoreCase = true) ||
           code.contains("<!doctype", ignoreCase = true) ||
           code.contains("graph", ignoreCase = true) && code.contains("-->", ignoreCase = true) || // Mermaid
           code.contains("@startuml", ignoreCase = true) || // PlantUML
           code.contains("d3.select", ignoreCase = true) || // D3.js
           code.contains("createCanvas", ignoreCase = true) || // P5.js
           code.contains("THREE.", ignoreCase = true) || // Three.js
           code.contains("Chart(", ignoreCase = true) || // Chart.js
           code.contains("getContext", ignoreCase = true) || // Canvas
           code.contains("\\begin{", ignoreCase = true) // LaTeX
}

/**
 * 预处理代码内容，支持Markdown和数学公式渲染
 */
private fun preprocessCodeForRendering(code: String, language: String?): String {
    // 对于某些语言，直接返回原始代码
    val directRenderLanguages = setOf("html", "svg", "css", "javascript", "js")
    if (language?.lowercase() in directRenderLanguages) {
        return code
    }
    
    // 对于其他语言，处理其中的Markdown和数学公式
    return processMarkdownAndMath(code)
}

/**
 * 处理文本中的Markdown和数学公式
 */
private fun processMarkdownAndMath(text: String): String {
    var processed = text
    
    // 处理数学公式 - 保持LaTeX格式以便MathJax渲染
    // 这里不需要转换，只需要确保格式正确
    processed = processed.replace(Regex("\\\\\\(([^)]+)\\\\\\)")) { "\\(${it.groupValues[1]}\\)" }
    processed = processed.replace(Regex("\\\\\\[([^\\]]+)\\\\\\]")) { "\\[${it.groupValues[1]}\\]" }
    
    // 处理常见的Markdown语法
    // 粗体
    processed = processed.replace(Regex("\\*\\*([^*]+)\\*\\*")) { "**${it.groupValues[1]}**" }
    // 斜体
    processed = processed.replace(Regex("\\*([^*]+)\\*")) { "*${it.groupValues[1]}*" }
    // 代码
    processed = processed.replace(Regex("`([^`]+)`")) { "`${it.groupValues[1]}`" }
    // 链接
    processed = processed.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")) { "[${it.groupValues[1]}](${it.groupValues[2]})" }
    
    return processed
}