// file: com/example/everytalk/util/HtmlTemplates.kt
package com.example.everytalk.util

import android.util.Log

fun generateKatexBaseHtmlTemplateString(
    backgroundColor: String,
    textColor: String,
    errorColor: String,
    throwOnError: Boolean
): String {
    Log.d(
        "HTMLTemplateUtil",
        "Generating KaTeX+Prism HTML template string (with sanitization). BG: $backgroundColor, TC: $textColor, ErrC: $errorColor, ThrErr: $throwOnError"
    )
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
        <link rel="stylesheet" href="file:///android_asset/katex/katex.min.css"/>
        <link rel="stylesheet" href="file:///android_asset/prism/prism.css"/> 
        <script src="file:///android_asset/katex/katex.min.js"></script>
        <script src="file:///android_asset/katex/contrib/auto-render.min.js"></script>
        <script src="file:///android_asset/katex/contrib/mhchem.min.js"></script>
        <style>
            html, body {
                height: auto; 
            }
            body { 
                margin:0; 
                padding:8px; 
                background-color:$backgroundColor; 
                color:$textColor; 
                overflow-y:auto; 
                overflow-x:hidden; 
                /* MODIFIED: Font stack for general text, aiming for rounder and unified feel */
                font-family: 'Noto Sans', 'Noto Sans CJK SC', Roboto, 'Droid Sans Fallback', sans-serif;
                /* MODIFIED: General text a little bit smaller */
                font-size: 0.94em; 
                line-height:1.5; 
                word-wrap:break-word; 
                overflow-wrap:break-word;
                font-weight: 520; /* Your previous setting for body text weight */
            }
            #latex_container { width:100%; }
            #latex_content_target { /* No specific height restrictions */ }
            
            /* Optional: Add margins for paragraphs if needed */
            /*
            p {
                margin-top: 0.8em;
                margin-bottom: 0.8em;
            }
            */

            .katex { 
                display:inline-block; 
                margin:0 0.1em; 
                padding:0; 
                text-align:left; 
                vertical-align:baseline; 
                font-size:1em; 
                line-height:normal; 
                white-space:normal; 
            }
            .katex-display { 
                display:block; 
                margin:0.8em 0.1em !important; /* Increased vertical margin for KaTeX blocks */
                padding:0 !important; 
                text-align:left; 
                overflow-x:auto; 
                overflow-y:auto; 
                max-width: 100%;
            }
            .error-message { 
                color:$errorColor; 
                font-weight:bold; 
                padding:10px; 
                border:1px solid $errorColor; 
                background-color:#fff0f0; 
                margin-bottom:5px; 
            }
            a, a:link, a:visited { 
                color:#4A90E2; 
                text-decoration:none; 
            }
            a:hover, a:active { 
                text-decoration:underline; 
            }
            pre[class*="language-"] { 
                padding:1em; 
                margin:.5em 0; 
                overflow:auto; 
                border-radius:1em; 
                font-size: 1em; /* This will be 1em of the body's new font-size */
                background-color:#f4f4f4
            }
            code[class*="language-"], pre[class*="language-"] code { 
                /* MODIFIED: Font stack for code, aiming for rounder feel */
                font-family: 'JetBrains Mono', 'Fira Code', 'Source Code Pro', 'Droid Sans Mono', 'Noto Sans Mono', 'Noto Sans Mono CJK SC', 'Ubuntu Mono', Consolas, Monaco, monospace;
                /* MODIFIED: Font weight for code */
                font-weight: 550;
                /* MODIFIED: Font size for code, relative to pre tag */
                font-size: 0.9em; 
                line-height:1.50; 
                white-space: pre-wrap; 
                word-break: break-all; 
            }
        </style>
    </head>
    <body>
        <div id="latex_container">
            <div id="latex_content_target"></div>
        </div>
        <script src="file:///android_asset/prism/prism.js"></script>
        <script type="text/javascript">
            var isKaTeXReady = false;
            var isPrismReady = false;
            var renderOptions = {
                delimiters: [ 
                    {left: "${'$'}", right: "${'$'}", display: false}, 
                    {left: "\\(", right: "\\)", display: false}, 
                    {left: "\\[", right: "\\]", display: true} 
                ],
                throwOnError: $throwOnError,
                errorColor: "$errorColor",
                macros: {"\\RR":"\\mathbb{R}"}
            };

            function checkLibraryStates() {
                var katexStatus = (typeof renderMathInElement === 'function' && typeof katex === 'object' && katex.render);
                var prismStatus = (typeof Prism === 'object' && typeof Prism.highlightElement === 'function'); 

                if (katexStatus && !isKaTeXReady) {
                    isKaTeXReady = true;
                    console.log("KaTeX is ready.");
                }
                if (prismStatus && !isPrismReady) {
                    isPrismReady = true;
                    console.log("Prism is ready.");
                }
                return isKaTeXReady && isPrismReady;
            }

            function processNodeWithLibraries(node) {
                if (!node) {
                    console.warn("processNodeWithLibraries: Node is null.");
                    return;
                }
                
                if (isKaTeXReady) {
                    try {
                        renderMathInElement(node, renderOptions);
                    } catch (e) { 
                        var errorMsgText = "KaTeX Error: " + (e.message || e);
                        console.error(errorMsgText, e);
                        if (node && node.appendChild && typeof node.appendChild === 'function') { 
                             var errorDiv = document.createElement('div');
                             errorDiv.className = 'error-message';
                             errorDiv.textContent = errorMsgText;
                             node.appendChild(errorDiv);
                        }
                    }
                } else {
                     console.warn("KaTeX not ready when processNodeWithLibraries called for:", node);
                }

                if (isPrismReady) {
                    try {
                        var codeElementsToHighlight = [];
                        if (node.nodeName === 'PRE' && node.firstChild && node.firstChild.nodeName === 'CODE') {
                            codeElementsToHighlight.push(node.firstChild);
                        } 
                        else if (node.querySelectorAll) { 
                            node.querySelectorAll('pre > code[class*="language-"], pre > code:not([class])').forEach(function(el) {
                                codeElementsToHighlight.push(el);
                            });
                        }
                        else if (node.nodeName === 'CODE' && node.parentElement && node.parentElement.nodeName === 'PRE') {
                             codeElementsToHighlight.push(node);
                        }
                        
                        if (codeElementsToHighlight.length > 0) {
                            codeElementsToHighlight.forEach(function(el) {
                                Prism.highlightElement(el);
                            });
                        }
                    } catch (e) { 
                        console.error("Prism Error processing node:", e, node); 
                    }
                } else {
                    console.warn("Prism not ready when processNodeWithLibraries called for:", node);
                }
            }
            
            function sanitizeHtmlInput(htmlString) {
                if (typeof htmlString !== 'string') return '';
                var SCRIPT_REGEX = /<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi;
                var sanitizedString = htmlString.replace(SCRIPT_REGEX, "");
                
                sanitizedString = sanitizedString.replace(/ on\w+\s*=\s*['"][^'"]*['"]/gi, '');
                sanitizedString = sanitizedString.replace(/ href\s*=\s*['"]javascript:[^'"]*['"]/gi, ' href="#"');

                return sanitizedString;
            }

            window.renderFullContent = function(fullHtmlString) {
                console.log("renderFullContent called. Original HTML length:", fullHtmlString.length);
                var target = document.getElementById('latex_content_target');
                if (!target) { console.error("renderFullContent: Target 'latex_content_target' not found."); return; }
                
                var sanitizedHtml = sanitizeHtmlInput(fullHtmlString);
                target.innerHTML = sanitizedHtml; 
                
                if (checkLibraryStates()) {
                    processNodeWithLibraries(target);
                } else { 
                    console.warn("renderFullContent: Libs not ready, content set but not fully processed.");
                }
            };

            window.appendHtmlChunk = function(htmlChunk) {
                console.log("appendHtmlChunk called. Original chunk preview:", htmlChunk.substring(0, 100).replace(/\n/g, "\\n"));
                var target = document.getElementById('latex_content_target');
                if (!target) { console.error("appendHtmlChunk: Target 'latex_content_target' not found."); return; }

                var sanitizedChunk = sanitizeHtmlInput(htmlChunk);
                if (sanitizedChunk.trim() === "") {
                    console.log("appendHtmlChunk: Chunk became empty after sanitization, not appending.");
                    return;
                }

                var tempDiv = document.createElement('div');
                tempDiv.innerHTML = sanitizedChunk; 
                
                var nodesToProcessAfterAppend = [];
                while (tempDiv.firstChild) {
                    nodesToProcessAfterAppend.push(target.appendChild(tempDiv.firstChild));
                }

                if (checkLibraryStates()) {
                    nodesToProcessAfterAppend.forEach(function(appendedNode) {
                        if (appendedNode.nodeType === Node.ELEMENT_NODE) {
                            processNodeWithLibraries(appendedNode);
                        } 
                        else if (appendedNode.nodeType === Node.TEXT_NODE && appendedNode.textContent.trim() !== "" && appendedNode.parentNode === target) {
                            if (isKaTeXReady) {
                                console.warn("appendHtmlChunk: Appended TextNode. Processing its parent (target) for KaTeX.");
                                processNodeWithLibraries(target); 
                            }
                        }
                    });
                } else { 
                    console.warn("appendHtmlChunk: Libs not ready, chunk appended raw (but sanitized).");
                }
            };

            var initialCheckAttempts = 0;
            var maxInitialCheckAttempts = 60; 
            function initialLibsLoadCheck() {
                if (checkLibraryStates()) {
                    console.log("KaTeX and Prism are ready (initial check complete).");
                } else if (initialCheckAttempts < maxInitialCheckAttempts) { 
                    initialCheckAttempts++;
                    setTimeout(initialLibsLoadCheck, 100);
                } else {
                    console.error("KaTeX or Prism.js did not become ready after " + (maxInitialCheckAttempts * 100 / 1000) + " seconds.");
                    var target = document.getElementById('latex_content_target');
                    if (target && !target.querySelector('.error-message')) {
                         var errorDiv = document.createElement('div');
                         errorDiv.className = 'error-message';
                         errorDiv.textContent = 'Error: Essential rendering libraries (KaTeX/Prism.js) failed to load. Content may not display correctly.';
                         if (target.firstChild) {
                             target.insertBefore(errorDiv, target.firstChild);
                         } else {
                             target.appendChild(errorDiv);
                         }
                    }
                }
            }
            
            if (document.readyState === "loading") {
                 document.addEventListener("DOMContentLoaded", initialLibsLoadCheck);
            } else { 
                 initialLibsLoadCheck();
            }
        </script>
    </body>
    </html>
    """.trimIndent()
}