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
        "Generating KaTeX+Prism HTML template string (Simplified JS, relying on AI prompt and auto-render). BG: $backgroundColor, TC: $textColor, ErrC: $errorColor, ThrErr: $throwOnError"
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
            html, body { height: auto; }
            body {
                margin:0; padding:0px; background-color:$backgroundColor; color:$textColor;
                overflow-y:auto; overflow-x:hidden;
                font-family: 'Noto Sans', 'Noto Sans CJK SC', Roboto, 'Droid Sans Fallback', sans-serif;
                font-size: 0.94em; line-height:1.5;
                word-wrap:break-word; overflow-wrap:break-word; font-weight: 520;
            }
            p {
                margin-top: 0 !important;
                margin-bottom: 0 !important; /* 也改为0测试 */
                padding-top: 0 !important;
                padding-bottom: 0 !important;
            }
            #latex_container { width:100%; }
            #latex_content_target { /* No specific height restrictions */ }

            .katex { display:inline-block; margin:0 0.1em; padding:0; text-align:left; vertical-align:baseline; font-size:1em; line-height:normal; white-space:normal; }
            .katex-display { display:block; margin:0.8em 0.1em !important; padding:0 !important; text-align:left; overflow-x:auto; overflow-y:auto; max-width: 100%; }
            .error-message { color:$errorColor; font-weight:bold; padding:10px; border:1px solid $errorColor; background-color:#fff0f0; margin-bottom:5px; }
            a, a:link, a:visited { color:#4A90E2; text-decoration:none; }
            a:hover, a:active { text-decoration:underline; }
            pre[class*="language-"] { padding:1em; margin:.5em 0; overflow:auto; border-radius:1em; font-size: 1em; background-color:#f4f4f4; }
            /* For ```math blocks, ensure they don't get Prism's pre/code styling if KaTeX replaces the pre */
            pre.language-math { /* KaTeX will replace this, so no specific style needed here usually */ }
            code.language-math { /* KaTeX will extract text from this, so no specific style needed here usually */ }

            code[class*="language-"]:not(.language-math), pre[class*="language-"]:not(.language-math) code {
                font-family: 'JetBrains Mono', 'Fira Code', 'Source Code Pro', 'Droid Sans Mono', 'Noto Sans Mono', 'Noto Sans Mono CJK SC', 'Ubuntu Mono', Consolas, Monaco, monospace;
                font-weight: 550; font-size: 0.9em; line-height:1.50; white-space: pre-wrap; word-break: break-all;
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
                    // Standard delimiters for auto-render.min.js
                    {left: "${'$'}", right: "${'$'}", display: false},
                    {left: "\\(", right: "\\)", display: false},
                    {left: "\\[", right: "\\]", display: true},
                    {left: "${'$'}${'$'}", right: "${'$'}${'$'}", display: true}
                    // We are NOT adding custom delimiters for ```math here because we handle it manually.
                ],
                throwOnError: $throwOnError,
                errorColor: "$errorColor",
                macros: {"\\RR":"\\mathbb{R}"}
                // trust: true, // Consider if facing issues with complex commands from ```math blocks,
                               // but be aware of security. For file:/// content, it's generally safer.
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
                if (!node) { console.warn("processNodeWithLibraries: Node is null."); return; }

                // 1. KaTeX Auto-Rendering for standard delimiters ( $...$, \(...\), $$...$$, \[...\] )
                // This will process the content of the node for these delimiters.
                if (isKaTeXReady) {
                    try {
                        // Important: renderMathInElement processes the *children* of the node if it's an element,
                        // or the node itself if it's a text node.
                        // If 'node' is the main container (like 'latex_content_target'), this is correct.
                        // If 'node' is a newly appended smaller fragment, this will scan that fragment.
                        renderMathInElement(node, renderOptions);
                        console.log("KaTeX auto-render executed on node:", node.nodeName);
                    } catch (e) {
                        var errorMsgText = "KaTeX Auto-Render Error: " + (e.message || e);
                        console.error(errorMsgText, e);
                        if (node && node.appendChild && typeof node.appendChild === 'function') {
                             var errorDiv = document.createElement('div'); errorDiv.className = 'error-message';
                             errorDiv.textContent = errorMsgText; node.appendChild(errorDiv);
                        }
                    }
                } else { console.warn("KaTeX not ready for auto-render on:", node); }

                // 2. PrismJS for syntax highlighting (for non-math code blocks)
                if (isPrismReady) {
                    try {
                        var codeElementsToHighlight = [];
                        // Selector for standard code blocks, excluding our manually handled math blocks
                        var selector = 'pre > code[class*="language-"]:not(.language-math), pre > code:not([class])';

                        if (node.nodeName === 'PRE' && node.firstChild && node.firstChild.nodeName === 'CODE' &&
                            (!node.firstChild.classList || !node.firstChild.classList.contains('language-math'))) {
                            codeElementsToHighlight.push(node.firstChild);
                        } else if (node.querySelectorAll) { // If node itself is a container
                            node.querySelectorAll(selector).forEach(function(el) { codeElementsToHighlight.push(el); });
                        }
                        // If node is a <code> element passed directly (less common for this function's typical use)
                        else if (node.nodeName === 'CODE' && node.parentElement && node.parentElement.nodeName === 'PRE' &&
                                 (!node.classList || !node.classList.contains('language-math'))) {
                             codeElementsToHighlight.push(node);
                        }

                        if (codeElementsToHighlight.length > 0) {
                            codeElementsToHighlight.forEach(function(el) { Prism.highlightElement(el); });
                            console.log("Prism highlighting executed on", codeElementsToHighlight.length, "elements.");
                        }
                    } catch (e) { console.error("Prism Error processing node:", e, node); }
                } else { console.warn("Prism not ready for highlighting on:", node); }

                // 3. Manual KaTeX Rendering for ```math blocks (pre > code.language-math)
                // This should run AFTER auto-render, or be structured so they don't interfere.
                // Since we replace the <pre> element, it's fine.
                if (isKaTeXReady) {
                    var mathCodeElements = [];
                    // Find 'code' elements with 'language-math' class, typically inside 'pre'
                    // If 'node' is the main container:
                    if (node.querySelectorAll) {
                        node.querySelectorAll('pre > code.language-math').forEach(function(el) { mathCodeElements.push(el); });
                    }
                    // If 'node' itself is a 'code.language-math' (less likely for typical call)
                    else if (node.nodeName === 'CODE' && node.classList && node.classList.contains('language-math') &&
                             node.parentNode && node.parentNode.nodeName === 'PRE') {
                        mathCodeElements.push(node);
                    }
                    // If 'node' is a 'pre' containing 'code.language-math'
                    else if (node.nodeName === 'PRE' && node.firstChild && node.firstChild.nodeName === 'CODE' &&
                             node.firstChild.classList && node.firstChild.classList.contains('language-math')) {
                        mathCodeElements.push(node.firstChild);
                    }


                    if (mathCodeElements.length > 0) console.log("Found", mathCodeElements.length, "language-math blocks to process manually.");
                    mathCodeElements.forEach(function(codeElement) {
                        var latexSource = codeElement.textContent || "";
                        // Basic script tag removal from LaTeX source, just in case.
                        var SCRIPT_REGEX_IN_LATEX = /<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi;
                        latexSource = latexSource.replace(SCRIPT_REGEX_IN_LATEX, "");

                        if (latexSource.trim() === "") {
                            console.warn("KaTeX Manual Render: Empty LaTeX source in language-math block.");
                            // Optionally remove the empty pre block or leave it.
                            // if (codeElement.parentNode) codeElement.parentNode.remove();
                            return;
                        }

                        var displayMode = true; // Always display mode for ```math blocks
                        var parentPre = codeElement.parentNode; // This should be the <pre> element

                        var katexOutputDiv = document.createElement('div');
                        katexOutputDiv.className = 'katex-display'; // Use KaTeX's standard class for display math

                        try {
                            katex.render(latexSource, katexOutputDiv, {
                                throwOnError: renderOptions.throwOnError,
                                errorColor: renderOptions.errorColor,
                                macros: renderOptions.macros,
                                displayMode: displayMode
                                // trust: true, // Consider if needed for complex commands
                            });

                            if (parentPre && parentPre.parentNode) {
                                parentPre.parentNode.replaceChild(katexOutputDiv, parentPre);
                                console.log("KaTeX: Manually rendered and replaced language-math block. Source:", latexSource.substring(0,30)+"...");
                            } else {
                                console.warn("KaTeX Manual Render: Could not find parent <pre> to replace for", codeElement);
                                // Fallback: replace content of code (less ideal as <pre> styling remains)
                                codeElement.innerHTML = '';
                                codeElement.appendChild(katexOutputDiv);
                            }
                        } catch (e) {
                            var errorMsgText = "KaTeX Manual Render Error (language-math): " + (e.message || e);
                            console.error(errorMsgText, e, "Problematic LaTeX Source:", latexSource);
                            var errorDiv = document.createElement('div');
                            errorDiv.className = 'error-message';
                            errorDiv.textContent = errorMsgText + "\nSource: " + latexSource.substring(0, 100) + (latexSource.length > 100 ? "..." : "");

                            if (parentPre && parentPre.parentNode) {
                                parentPre.parentNode.insertBefore(errorDiv, parentPre.nextSibling);
                            } else if (codeElement.parentNode) { // Should be parentPre
                                codeElement.parentNode.insertBefore(errorDiv, codeElement.nextSibling);
                            } else { // Should not happen
                                document.body.appendChild(errorDiv);
                            }
                        }
                    });
                } else { console.warn("KaTeX not ready for manual ```math render on:", node); }
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
                console.log("renderFullContent called. HTML length:", fullHtmlString.length);
                var target = document.getElementById('latex_content_target');
                if (!target) { console.error("Target 'latex_content_target' not found."); return; }
                target.innerHTML = sanitizeHtmlInput(fullHtmlString); // Set the full HTML
                if (checkLibraryStates()) {
                    processNodeWithLibraries(target); // Process the entire new content
                } else {
                    console.warn("renderFullContent: Libs not ready. Content set but not processed.");
                }
            };

            window.appendHtmlChunk = function(htmlChunk) {
                console.log("appendHtmlChunk. Preview:", htmlChunk.substring(0, 100).replace(/\n/g, "\\n"));
                var target = document.getElementById('latex_content_target');
                if (!target) { console.error("appendHtmlChunk: Target not found."); return; }
                var sanitizedChunk = sanitizeHtmlInput(htmlChunk);
                if (sanitizedChunk.trim() === "") { console.log("appendHtmlChunk: Empty after sanitization."); return; }

                // Create a temporary div to hold the new chunk's nodes
                var tempDiv = document.createElement('div');
                tempDiv.innerHTML = sanitizedChunk;

                // Append new nodes and collect them for processing
                var appendedNodes = [];
                while (tempDiv.firstChild) {
                    appendedNodes.push(target.appendChild(tempDiv.firstChild));
                }

                if (checkLibraryStates()) {
                    // Process each newly appended top-level node/fragment
                    // This is more efficient than re-processing the entire target on every chunk,
                    // but assumes KaTeX/Prism can work correctly on these fragments.
                    appendedNodes.forEach(function(appendedNode) {
                        // If appendedNode is a text node, renderMathInElement will handle it.
                        // If it's an element, renderMathInElement will scan its children.
                        // Our manual ```math handler also uses querySelectorAll on the node.
                        processNodeWithLibraries(appendedNode);
                    });
                } else {
                    console.warn("appendHtmlChunk: Libs not ready. Chunk appended raw.");
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
                    console.error("KaTeX/Prism.js did not become ready after " + (maxInitialCheckAttempts * 100 / 1000) + "s.");
                    var target = document.getElementById('latex_content_target');
                    if (target && !target.querySelector('.error-message')) {
                         var errorDiv = document.createElement('div'); errorDiv.className = 'error-message';
                         errorDiv.textContent = 'Error: Essential rendering libraries (KaTeX/Prism.js) failed to load. Content may not display correctly.';
                         if (target.firstChild) { target.insertBefore(errorDiv, target.firstChild); }
                         else { target.appendChild(errorDiv); }
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