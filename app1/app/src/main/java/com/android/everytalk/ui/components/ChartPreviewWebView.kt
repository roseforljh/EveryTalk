package com.android.everytalk.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 轻量图表预览 WebView（内联展开，不使用全屏/BottomSheet）
 *
 * 当前支持的语言类型：
 * - **svg**: 内联 SVG 代码（直接渲染，无需外部依赖）
 * - **mermaid**: 流程图/时序图等（优先使用本地 assets，CDN 作为备用）
 * - **echarts, chart, chartjs, vega, vega-lite, plantuml, flowchart**: 占位提示（可扩展）
 *
 * 性能优化：
 * - SVG 无需加载外部资源，速度最快
 * - Mermaid 优先从 assets/mermaid/ 加载本地文件（需手动放置 mermaid.min.js）
 * - 若本地文件不存在，自动降级到 CDN（需网络）
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChartPreviewWebView(
    code: String,
    language: String?,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val lang = language?.lowercase()?.trim()
    val html = remember(code, lang) { ChartPreviewHtml.buildHtml(code, lang) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // 基础安全与能力设置
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                // ✅ 允许从 assets 加载（仅限本地文件，不允许跨域）
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                // 提升渲染质量
                setBackgroundColor(AColor.TRANSPARENT)

                // 禁止外部跳转与 scheme 导航
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        url: String?
                    ): Boolean {
                        // 统一拦截，禁止导航
                        return true
                    }
                }

                // 禁用长按系统菜单（避免复制/保存等）
                setOnLongClickListener { true }
                isLongClickable = false
            }
        },
        update = { webView ->
            // 使用 file:///android_asset/ 作为 baseUrl，允许加载本地 assets 资源
            webView.loadDataWithBaseURL(
                "file:///android_asset/",
                html,
                "text/html",
                "utf-8",
                null
            )
        }
    )
}

/**
 * HTML 模板拼装器
 *
 * 支持的语言类型：
 * - svg: 内联 SVG（无需外部依赖）
 * - mermaid: 流程图/时序图（优先本地 assets，降级 CDN）
 * - echarts: ECharts 图表库（优先本地，降级 CDN）
 * - chart/chartjs: Chart.js 图表库（优先本地，降级 CDN）
 * - vega/vega-lite: Vega 可视化语法（优先本地，降级 CDN）
 * - flowchart: 通用流程图（使用 flowchart.js，优先本地，降级 CDN）
 */
private object ChartPreviewHtml {

    // 本地 assets 路径
    private const val MERMAID_LOCAL = "mermaid/mermaid.min.js"
    private const val ECHARTS_LOCAL = "echarts/echarts.min.js"
    private const val CHARTJS_LOCAL = "chartjs/chart.min.js"
    private const val VEGA_LOCAL = "vega/vega.min.js"
    private const val VEGA_LITE_LOCAL = "vega/vega-lite.min.js"
    private const val VEGA_EMBED_LOCAL = "vega/vega-embed.min.js"
    private const val FLOWCHART_RAPHAEL_LOCAL = "flowchart/raphael.min.js"
    private const val FLOWCHART_LOCAL = "flowchart/flowchart.min.js"
    
    // CDN 备用路径
    private const val MERMAID_CDN = "https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"
    private const val ECHARTS_CDN = "https://cdn.jsdelivr.net/npm/echarts@5/dist/echarts.min.js"
    private const val CHARTJS_CDN = "https://cdn.jsdelivr.net/npm/chart.js@4/dist/chart.umd.min.js"
    private const val VEGA_CDN = "https://cdn.jsdelivr.net/npm/vega@5"
    private const val VEGA_LITE_CDN = "https://cdn.jsdelivr.net/npm/vega-lite@5"
    private const val VEGA_EMBED_CDN = "https://cdn.jsdelivr.net/npm/vega-embed@6"
    private const val FLOWCHART_RAPHAEL_CDN = "https://cdnjs.cloudflare.com/ajax/libs/raphael/2.3.0/raphael.min.js"
    private const val FLOWCHART_CDN = "https://cdnjs.cloudflare.com/ajax/libs/flowchart/1.17.1/flowchart.min.js"

    fun buildHtml(code: String, language: String?): String {
        val lang = language?.lowercase()?.trim()

        return when {
            // 1) HTML：直接渲染 HTML 代码
            isHtml(lang) -> buildHtmlPreview(code)

            // 2) 内联 SVG：直接放入 body
            isSvg(lang, code) -> buildSvgHtml(code)

            // 3) Mermaid：流程图/时序图等
            isMermaid(lang, code) -> buildMermaidHtml(code)

            // 4) ECharts：数据可视化图表
            isECharts(lang) -> buildEChartsHtml(code)

            // 5) Chart.js：简单图表库
            isChartJs(lang) -> buildChartJsHtml(code)

            // 6) Vega/Vega-Lite：声明式可视化
            isVega(lang) -> buildVegaHtml(code, lang)

            // 7) Flowchart：通用流程图
            isFlowchart(lang) -> buildFlowchartHtml(code)

            else -> buildNotApplicableHtml()
        }
    }

    private fun isHtml(lang: String?): Boolean = lang == "html"
    
    private fun isSvg(lang: String?, code: String): Boolean {
        if (lang == "svg") return true
        return Regex("(?is)\\Q<svg\\E").containsMatchIn(code)
    }

    private fun isMermaid(lang: String?, code: String): Boolean {
        if (lang == "mermaid") return true
        // 通过典型关键字启发判断
        val looksLikeMermaid = Regex("(?is)\\b(graph\\s+\\w+|sequenceDiagram|classDiagram|stateDiagram|erDiagram|journey)\\b")
            .containsMatchIn(code)
        return looksLikeMermaid
    }

    private fun isECharts(lang: String?): Boolean = lang == "echarts"
    
    private fun isChartJs(lang: String?): Boolean = lang in listOf("chart", "chartjs")
    
    private fun isVega(lang: String?): Boolean = lang in listOf("vega", "vega-lite")
    
    private fun isFlowchart(lang: String?): Boolean = lang == "flowchart"

    private fun buildHtmlPreview(htmlCode: String): String {
        // 直接渲染用户提供的 HTML 代码
        // 注意：为安全起见，在沙盒环境中渲染（已通过 WebView 设置限制）
        return htmlCode.trim()
    }

    private fun buildSvgHtml(rawSvg: String): String {
        // 允许用户直接粘贴完整 <svg> 代码：不转义，原样内联
        val bodyContent = rawSvg.trim()

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
              <style>
                html, body {
                  margin: 0;
                  padding: 0;
                  background: transparent;
                  overflow: auto;
                }
                .container {
                  box-sizing: border-box;
                  padding: 6px;
                  width: 100%;
                  height: 100%;
                  overflow: auto;
                }
                svg {
                  max-width: 100%;
                  height: auto;
                  display: block;
                }
              </style>
            </head>
            <body>
              <div class="container">
                $bodyContent
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildMermaidHtml(source: String): String {
        val escaped = escapeHtml(source.trim())

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
              <style>
                html, body {
                  margin: 0;
                  padding: 0;
                  background: transparent;
                  overflow: auto;
                }
                .container {
                  box-sizing: border-box;
                  padding: 8px;
                  width: 100%;
                  height: 100%;
                  overflow: auto;
                }
                /* 让生成的 SVG 自适应容器 */
                .mermaid svg {
                  max-width: 100%;
                  height: auto;
                  display: block;
                }
                .loading {
                  color: #888;
                  font-size: 12px;
                  padding: 12px;
                  text-align: center;
                }
              </style>
            </head>
            <body>
              <div class="container">
                <pre class="mermaid">$escaped</pre>
              </div>
              <script>
                (function() {
                  // 优先尝试加载本地 assets 文件，失败时降级到 CDN
                  var localScript = document.createElement('script');
                  localScript.src = '$MERMAID_LOCAL';
                  localScript.onload = function() {
                    // 本地加载成功
                    if (window.mermaid) {
                      window.mermaid.initialize({ startOnLoad: true, securityLevel: "strict" });
                    }
                  };
                  localScript.onerror = function() {
                    // 本地加载失败，降级到 CDN
                    var cdnScript = document.createElement('script');
                    cdnScript.src = '$MERMAID_CDN';
                    cdnScript.onload = function() {
                      if (window.mermaid) {
                        window.mermaid.initialize({ startOnLoad: true, securityLevel: "strict" });
                      }
                    };
                    cdnScript.onerror = function() {
                      // CDN 也失败，显示错误提示
                      document.querySelector('.container').innerHTML =
                        '<div class="loading">无法加载 Mermaid 库（本地与 CDN 均失败）</div>';
                    };
                    document.head.appendChild(cdnScript);
                  };
                  document.head.appendChild(localScript);
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildEChartsHtml(source: String): String {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
              <style>
                html, body { margin: 0; padding: 0; background: transparent; overflow: auto; height: auto; }
                #chart { width: 100%; height: auto; min-height: 400px; }
              </style>
            </head>
            <body>
              <div id="chart"></div>
              <script>
                (function() {
                  var localScript = document.createElement('script');
                  localScript.src = '$ECHARTS_LOCAL';
                  localScript.onload = function() { initChart(); };
                  localScript.onerror = function() {
                    var cdnScript = document.createElement('script');
                    cdnScript.src = '$ECHARTS_CDN';
                    cdnScript.onload = function() { initChart(); };
                    cdnScript.onerror = function() {
                      document.getElementById('chart').innerHTML = '<div style="padding:12px;color:#888;text-align:center;">无法加载 ECharts 库</div>';
                    };
                    document.head.appendChild(cdnScript);
                  };
                  document.head.appendChild(localScript);
                  
                  function initChart() {
                    try {
                      var option = $source;
                      var chart = echarts.init(document.getElementById('chart'));
                      chart.setOption(option);
                      window.addEventListener('resize', function() { chart.resize(); });
                    } catch(e) {
                      document.getElementById('chart').innerHTML = '<div style="padding:12px;color:#888;text-align:center;">图表配置错误: ' + e.message + '</div>';
                    }
                  }
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildChartJsHtml(source: String): String {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
              <style>
                html, body { margin: 0; padding: 0; background: transparent; overflow: auto; height: auto; }
                .container { width: 100%; height: auto; min-height: 400px; padding: 8px; box-sizing: border-box; }
              </style>
            </head>
            <body>
              <div class="container">
                <canvas id="chart"></canvas>
              </div>
              <script>
                (function() {
                  var localScript = document.createElement('script');
                  localScript.src = '$CHARTJS_LOCAL';
                  localScript.onload = function() { initChart(); };
                  localScript.onerror = function() {
                    var cdnScript = document.createElement('script');
                    cdnScript.src = '$CHARTJS_CDN';
                    cdnScript.onload = function() { initChart(); };
                    cdnScript.onerror = function() {
                      document.querySelector('.container').innerHTML = '<div style="padding:12px;color:#888;text-align:center;">无法加载 Chart.js 库</div>';
                    };
                    document.head.appendChild(cdnScript);
                  };
                  document.head.appendChild(localScript);
                  
                  function initChart() {
                    try {
                      var config = $source;
                      new Chart(document.getElementById('chart'), config);
                    } catch(e) {
                      document.querySelector('.container').innerHTML = '<div style="padding:12px;color:#888;text-align:center;">图表配置错误: ' + e.message + '</div>';
                    }
                  }
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildVegaHtml(source: String, lang: String?): String {
        val isLite = lang == "vega-lite"
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
              <style>
                html, body { margin: 0; padding: 0; background: transparent; overflow: auto; }
                #vis { width: 100%; min-height: 300px; padding: 8px; box-sizing: border-box; }
              </style>
            </head>
            <body>
              <div id="vis"></div>
              <script>
                (function() {
                  var scriptsToLoad = [
                    { local: '$VEGA_LOCAL', cdn: '$VEGA_CDN' },
                    ${if (isLite) "{ local: '$VEGA_LITE_LOCAL', cdn: '$VEGA_LITE_CDN' }," else ""}
                    { local: '$VEGA_EMBED_LOCAL', cdn: '$VEGA_EMBED_CDN' }
                  ];
                  var loadedCount = 0;
                  
                  function loadScript(index) {
                    if (index >= scriptsToLoad.length) {
                      initVega();
                      return;
                    }
                    var item = scriptsToLoad[index];
                    var localScript = document.createElement('script');
                    localScript.src = item.local;
                    localScript.onload = function() { loadScript(index + 1); };
                    localScript.onerror = function() {
                      var cdnScript = document.createElement('script');
                      cdnScript.src = item.cdn;
                      cdnScript.onload = function() { loadScript(index + 1); };
                      cdnScript.onerror = function() {
                        document.getElementById('vis').innerHTML = '<div style="padding:12px;color:#888;text-align:center;">无法加载 Vega 库</div>';
                      };
                      document.head.appendChild(cdnScript);
                    };
                    document.head.appendChild(localScript);
                  }
                  
                  loadScript(0);
                  
                  function initVega() {
                    try {
                      var spec = $source;
                      vegaEmbed('#vis', spec);
                    } catch(e) {
                      document.getElementById('vis').innerHTML = '<div style="padding:12px;color:#888;text-align:center;">图表配置错误: ' + e.message + '</div>';
                    }
                  }
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildFlowchartHtml(source: String): String {
        val escaped = escapeHtml(source.trim())
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
              <style>
                html, body { margin: 0; padding: 0; background: transparent; overflow: auto; }
                #diagram { width: 100%; min-height: 300px; padding: 8px; box-sizing: border-box; }
              </style>
            </head>
            <body>
              <div id="diagram"></div>
              <script>
                (function() {
                  var raphaelScript = document.createElement('script');
                  raphaelScript.src = '$FLOWCHART_RAPHAEL_LOCAL';
                  raphaelScript.onload = function() { loadFlowchart(); };
                  raphaelScript.onerror = function() {
                    var cdnScript = document.createElement('script');
                    cdnScript.src = '$FLOWCHART_RAPHAEL_CDN';
                    cdnScript.onload = function() { loadFlowchart(); };
                    cdnScript.onerror = function() {
                      document.getElementById('diagram').innerHTML = '<div style="padding:12px;color:#888;text-align:center;">无法加载 Raphael 库</div>';
                    };
                    document.head.appendChild(cdnScript);
                  };
                  document.head.appendChild(raphaelScript);
                  
                  function loadFlowchart() {
                    var flowchartScript = document.createElement('script');
                    flowchartScript.src = '$FLOWCHART_LOCAL';
                    flowchartScript.onload = function() { initFlowchart(); };
                    flowchartScript.onerror = function() {
                      var cdnScript = document.createElement('script');
                      cdnScript.src = '$FLOWCHART_CDN';
                      cdnScript.onload = function() { initFlowchart(); };
                      cdnScript.onerror = function() {
                        document.getElementById('diagram').innerHTML = '<div style="padding:12px;color:#888;text-align:center;">无法加载 Flowchart 库</div>';
                      };
                      document.head.appendChild(cdnScript);
                    };
                    document.head.appendChild(flowchartScript);
                  }
                  
                  function initFlowchart() {
                    try {
                      var code = `$escaped`;
                      var diagram = flowchart.parse(code);
                      diagram.drawSVG('diagram');
                    } catch(e) {
                      document.getElementById('diagram').innerHTML = '<div style="padding:12px;color:#888;text-align:center;">流程图语法错误: ' + e.message + '</div>';
                    }
                  }
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildNotApplicableHtml(): String {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
              <style>
                html, body { margin: 0; padding: 0; background: transparent; }
                .placeholder {
                  box-sizing: border-box;
                  width: 100%;
                  height: 100%;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  color: #888;
                  font-family: system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif;
                  font-size: 14px;
                  padding: 12px;
                  text-align: center;
                }
              </style>
            </head>
            <body>
              <div class="placeholder">该代码块不支持预览</div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escapeHtml(s: String): String {
        return s
            .replace("&", "&")
            .replace("<", "<")
            .replace(">", ">")
    }
}