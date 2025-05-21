// file: com/example/everytalk/util/HtmlTemplates.kt (或者你的实际路径)
package com.example.everytalk.util // 确保包名正确

import android.util.Log

fun generateKatexBaseHtmlTemplateString(
    backgroundColor: String,
    textColor: String,
    errorColor: String,
    throwOnError: Boolean
): String {
    Log.d(
        "HTMLTemplateUtil",
        "Generating KaTeX HTML template string. BG: $backgroundColor, TC: $textColor, ErrC: $errorColor, ThrErr: $throwOnError"
    )
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
        <link rel="stylesheet" href="file:///android_asset/katex/katex.min.css"/>
        <script src="file:///android_asset/katex/katex.min.js"></script>
        <script src="file:///android_asset/katex/contrib/auto-render.min.js"></script>
        <script src="file:///android_asset/katex/contrib/mhchem.min.js"></script>
        <style>
            body { 
                margin: 0; 
                padding: 8px; 
                background-color: $backgroundColor; 
                color: $textColor; 
                /* min-height: calc(100vh - 16px); Removed to allow content to define height */
                overflow-y: auto; /* Allow vertical scroll if content overflows */
                overflow-x: hidden; 
                font-family: sans-serif; 
                line-height: 1.5;
                word-wrap: break-word; /* Ensure long words break to next line */
                overflow-wrap: break-word; /* Alternative for word-wrap */
            }
            #latex_container { 
                width: 100%; 
                /* overflow-x: hidden !important; -- Let content scroll if needed, but usually not for chat */
                /* overflow-y: hidden; -- Let body handle scrolling */
            }
            /* Ensure KaTeX elements behave well with wrapping and streaming */
            .katex {
                display: inline-block; /* Allows margin/padding, but flows with text */
                margin: 0 0.1em;      
                padding: 0;
                text-align: left;     /* Default, but good to be explicit */
                vertical-align: baseline; 
                font-size: 1em;       /* Inherit font size */
                line-height: normal;  /* Avoid overriding external line height too much */
                white-space: normal;  /* Allow KaTeX to wrap if needed, though it tries to keep formulas intact */
            }
            .katex-display { /* For block math if $$ still produces it, force inline-like behavior */
                display: block; /* Block math should take its own line */
                margin: 0.5em 0.1em !important; /* Add some vertical margin for block math */
                padding: 0 !important;
                text-align: left; /* Or center if you prefer for block math */
                /* vertical-align: baseline; -- Not applicable for block */
                overflow-x: auto; /* Allow horizontal scroll for very wide block formulas */
                overflow-y: hidden;
            }
            #latex_container::-webkit-scrollbar { height: 6px; background-color: #f0f0f0; }
            #latex_container::-webkit-scrollbar-thumb { background-color: #cccccc; border-radius: 3px; }
            .error-message { color: $errorColor; font-weight: bold; padding: 10px; border: 1px solid $errorColor; background-color: #fff0f0; margin-bottom: 5px;}
            a:link, a:visited {
                color: #4A90E2; /* 淡蓝色 (例如: Google的蓝色链接颜色) */
                text-decoration: none; /* 取消下划线 */
            }
            a:hover {
                text-decoration: none; /* 鼠标悬停时显示下划线 (可选) */
                color: #357ABD; /* 可选：悬停时颜色变深一点 */
            }
            a:active {
                text-decoration: none; /* 点击时显示下划线 (可选) */
                color: #aa0000; /* 可选：点击时颜色更深 */
            }
        </style>
    </head>
    <body>
        <div id="latex_container">
            <div id="latex_content_target"></div> <!-- Start empty, content will be loaded or appended -->
        </div>
        <script type="text/javascript">
            var isKaTeXReady = false;
            var renderOptions = {
                delimiters: [
                    {left: "${'$'}", right: "${'$'}", display: false}, // For $...$ inline math
                    {left: "\\(", right: "\\)", display: false},   // For \(...\) inline math
                    {left: "\\[", right: "\\]", display: true}    // For \[...\] display math
                    // Removed $$...$$ from delimiters, as we decided to treat it as inline via CSS,
                    // or convert it to \[...\] on the Kotlin side if block display is desired.
                    // If you still use $$ for block math and want KaTeX to handle it with .katex-display:
                    // {left: "${'$'}${'$'}", right: "${'$'}${'$'}", display: true} 
                ],
                throwOnError: $throwOnError,
                errorColor: "$errorColor",
                macros: { "\\RR": "\\mathbb{R}" } // Example KaTeX macro
            };

            function checkKaTeXReadyState() {
                if (typeof renderMathInElement === 'function' && typeof katex === 'object' && katex.render) {
                    isKaTeXReady = true;
                    console.log("KaTeX is ready.");
                    // Process any queued initial render if applicable (though less likely with new append model)
                } else {
                    console.warn("KaTeX libraries not fully ready yet.");
                }
                return isKaTeXReady;
            }

            function renderMathInNode(node) {
                if (!isKaTeXReady || !node) {
                    console.warn("Cannot render math in node: KaTeX not ready or node is null.", node);
                    return;
                }
                try {
                    renderMathInElement(node, renderOptions);
                    console.log("KaTeX rendered in node:", node);
                } catch (e) {
                    var errorMsgText = "KaTeX Error: " + (e.message || e);
                    console.error(errorMsgText, e);
                    if (node && typeof node.appendChild === 'function') {
                         var errorDiv = document.createElement('div');
                         errorDiv.className = 'error-message';
                         errorDiv.textContent = errorMsgText;
                         node.appendChild(errorDiv); // Append error to the node itself
                    }
                }
            }

            // Function for initial full content load or complete replacement
            window.renderFullContent = function(fullHtmlString) {
                console.log("renderFullContent called with HTML length:", fullHtmlString.length);
                var target = document.getElementById('latex_content_target');
                if (!target) {
                    console.error("renderFullContent: Target 'latex_content_target' not found.");
                    return;
                }
                target.innerHTML = fullHtmlString; // Replace entire content
                if (checkKaTeXReadyState()) {
                    renderMathInNode(target); // Render KaTeX on the entire new content
                } else {
                    console.warn("renderFullContent: KaTeX not ready, full content set but not rendered by KaTeX yet.");
                    // Could implement a retry or queue here if absolutely necessary for full load
                }
            };

            // Function to append HTML snippet and render KaTeX on the new part
            window.appendHtmlChunk = function(htmlChunk) {
                console.log("appendHtmlChunk called with chunk:", htmlChunk.substring(0, 100));
                var target = document.getElementById('latex_content_target');
                if (!target) {
                    console.error("appendHtmlChunk: Target 'latex_content_target' not found.");
                    return;
                }

                // Create a temporary div to parse the HTML chunk into DOM nodes
                var tempDiv = document.createElement('div');
                tempDiv.innerHTML = htmlChunk;

                // Create a document fragment to batch append, which is more efficient
                var fragment = document.createDocumentFragment();
                var nodesToRender = []; // Keep track of top-level nodes appended from the chunk
                while (tempDiv.firstChild) {
                    let appendedNode = fragment.appendChild(tempDiv.firstChild);
                    nodesToRender.push(appendedNode); // This might not be the actual node in document yet
                }
                
                target.appendChild(fragment); // Append the parsed nodes to the target

                if (checkKaTeXReadyState()) {
                    // After appending, the nodes in nodesToRender might be copies or originals.
                    // It's safer to iterate through the *actual last children* of the target
                    // that correspond to what was just appended.
                    // However, for simplicity, if htmlChunk is usually small and self-contained elements,
                    // rendering on the original nodes *before* appending (if KaTeX works on detached nodes)
                    // or re-querying them after append might be needed.
                    // The most robust way for KaTeX on dynamic content is often to re-render a known container
                    // that now includes the new content, or ensure each chunk is a distinct element.

                    // Let's try to render based on the structure of nodesToRender
                    // This assumes KaTeX can work on nodes that were just moved to the document.
                    nodesToRender.forEach(function(node) {
                        // renderMathInElement works best on an Element node.
                        // If 'node' is a TextNode, it needs a parent Element to be passed.
                        if (node.nodeType === Node.ELEMENT_NODE) {
                            renderMathInNode(node);
                        } else if (node.nodeType === Node.TEXT_NODE && node.parentNode && node.parentNode === target) {
                            // If a text node was directly appended to target,
                            // we might need to re-render the whole target to catch it,
                            // or ensure our chunks are always wrapped in an element.
                            // For now, let's assume chunks are elements or KaTeX is robust.
                            // As a simple measure, if it's a text node, try re-rendering its direct parent if it's the target.
                            // This is not perfect.
                             console.warn("appendHtmlChunk: Appended a TextNode. Re-rendering entire target for KaTeX to catch it. Consider wrapping chunks in elements.");
                             renderMathInNode(target); // Less efficient, but safer for loose text nodes.
                        }
                    });
                } else {
                    console.warn("appendHtmlChunk: KaTeX not ready, chunk appended raw.");
                }
                
                // Scroll to bottom logic (optional, can be controlled from Kotlin too)
                // window.scrollTo(0, document.body.scrollHeight);
                // Or if #latex_container is the scrollable element:
                // var container = document.getElementById('latex_container');
                // if(container) container.scrollTop = container.scrollHeight;
            };

            // Initial check for KaTeX readiness
            var initialCheckAttempts = 0;
            function initialKaTeXCheck() {
                if (checkKaTeXReadyState()) {
                    // KaTeX is ready, no further polling needed from here for initial setup.
                } else if (initialCheckAttempts < 50) { // Try for 5 seconds
                    initialCheckAttempts++;
                    setTimeout(initialKaTeXCheck, 100);
                } else {
                    console.error("KaTeX did not become ready after 5 seconds.");
                    var target = document.getElementById('latex_content_target');
                    if (target && !target.querySelector('.error-message')) {
                         target.innerHTML = "<div class='error-message'>Error: KaTeX libraries failed to load/initialize. Math rendering disabled.</div>" + target.innerHTML;
                    }
                }
            }
            
            if (document.readyState === "loading") {
                 document.addEventListener("DOMContentLoaded", initialKaTeXCheck);
            } else { // DOMContentLoaded has already fired
                 initialKaTeXCheck();
            }

        </script>
    </body>
    </html>
    """.trimIndent()
}