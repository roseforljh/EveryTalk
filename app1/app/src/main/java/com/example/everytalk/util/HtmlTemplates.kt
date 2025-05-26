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
        "Generating KaTeX+Prism HTML template string. BG: $backgroundColor, TC: $textColor, ErrC: $errorColor, ThrErr: $throwOnError"
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
                margin-bottom: 0 !important;
                padding-top: 0 !important;
                padding-bottom: 0 !important;
            }
            #latex_container { width:100%; }
            #latex_content_target { /* No specific height restrictions */ }

            .katex { display:inline-block; margin:0 0.1em; padding:0; text-align:left; vertical-align:baseline; font-size:1em; line-height:normal; white-space:normal; }
            /* Ensure .katex-display from MathNodeRenderer has similar base margin to auto-render's default for $$ */
            .katex-display { display:block; margin:0.8em 0.1em !important; padding:0 !important; text-align:left; overflow-x:auto; overflow-y:auto; max-width: 100%; }
            .error-message { color:$errorColor; font-weight:bold; padding:10px; border:1px solid $errorColor; background-color:#fff0f0; margin-bottom:5px; }
            a, a:link, a:visited { color:#4A90E2; text-decoration:none; }
            a:hover, a:active { text-decoration:underline; }
            pre[class*="language-"] { padding:1em; margin:.5em 0; overflow:auto; border-radius:1em; font-size: 1em; background-color:#f4f4f4; }
            pre.language-math { /* KaTeX will replace this */ }
            code.language-math { /* KaTeX will extract text from this */ }

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
                    {left: "${'$'}", right: "${'$'}", display: false},
                    {left: "\\(", right: "\\)", display: false},
                    {left: "\\[", right: "\\]", display: true},
                    {left: "${'$'}${'$'}", right: "${'$'}${'$'}", display: true}
                ],
                throwOnError: $throwOnError,
                errorColor: "$errorColor",
                macros: {"\\RR":"\\mathbb{R}"}
                // trust: true, // Consider if true is needed, especially for mhchem or complex user macros
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
                if (!checkLibraryStates()) {
                    console.warn("processNodeWithLibraries: KaTeX or Prism not ready. Aborting for node:", node.nodeName);
                    return;
                }

                // 1. KaTeX Auto-Rendering (for LaTeX with delimiters directly in text nodes)
                if (isKaTeXReady && typeof renderMathInElement === 'function') {
                    try {
                        renderMathInElement(node, renderOptions);
                        console.log("KaTeX auto-render attempted on node:", node.nodeName);
                    } catch (e) {
                        var errorMsgText = "KaTeX Auto-Render Error: " + (e.message || e);
                        console.error(errorMsgText, e);
                        if (node && node.appendChild && typeof node.appendChild === 'function') {
                             var errorDiv = document.createElement('div'); errorDiv.className = 'error-message';
                             errorDiv.textContent = errorMsgText; node.appendChild(errorDiv);
                        }
                    }
                } else { console.warn("KaTeX auto-render not available or not ready for node:", node); }

                // 2. PrismJS for syntax highlighting (excluding our manually handled math blocks)
                if (isPrismReady) {
                    try {
                        var codeElementsToHighlight = [];
                        var selector = 'pre > code[class*="language-"]:not(.language-math), pre > code:not([class])';
                        if (node.nodeName === 'PRE' && node.firstChild && node.firstChild.nodeName === 'CODE' &&
                            (!node.firstChild.classList || !node.firstChild.classList.contains('language-math'))) {
                            codeElementsToHighlight.push(node.firstChild);
                        } else if (node.querySelectorAll) {
                            node.querySelectorAll(selector).forEach(function(el) { codeElementsToHighlight.push(el); });
                        } else if (node.nodeName === 'CODE' && node.parentElement && node.parentElement.nodeName === 'PRE' &&
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
                if (isKaTeXReady) {
                    var mathCodeElements = [];
                    if (node.nodeType === Node.ELEMENT_NODE && node.classList && node.classList.contains('language-math') && node.parentNode && node.parentNode.nodeName === 'PRE') {
                         mathCodeElements.push(node); // Node itself is code.language-math
                    } else if (node.nodeName === 'PRE' && node.firstChild && node.firstChild.nodeName === 'CODE' && node.firstChild.classList && node.firstChild.classList.contains('language-math')) {
                         mathCodeElements.push(node.firstChild); // Node is PRE containing code.language-math
                    } else if (node.querySelectorAll) { // Node is a container
                        node.querySelectorAll('pre > code.language-math').forEach(function(el) { mathCodeElements.push(el); });
                    }

                    if (mathCodeElements.length > 0) console.log("Found", mathCodeElements.length, "language-math blocks to process manually.");
                    mathCodeElements.forEach(function(codeElement) {
                        var latexSource = codeElement.textContent || "";
                        var SCRIPT_REGEX_IN_LATEX = /<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi;
                        latexSource = latexSource.replace(SCRIPT_REGEX_IN_LATEX, "");
                        if (latexSource.trim() === "") { console.warn("KaTeX Manual Render: Empty LaTeX source in language-math block."); return; }
                        var parentPre = codeElement.parentNode;
                        var katexOutputDiv = document.createElement('div');
                        // katexOutputDiv.className = 'katex-display'; // KaTeX.render in displayMode will add this itself or similar structure
                        try {
                            katex.render(latexSource, katexOutputDiv, {
                                throwOnError: renderOptions.throwOnError, errorColor: renderOptions.errorColor,
                                macros: renderOptions.macros, displayMode: true
                            });
                            if (parentPre && parentPre.parentNode) {
                                parentPre.parentNode.replaceChild(katexOutputDiv, parentPre);
                                console.log("KaTeX: Manually rendered and replaced language-math block. Source:", latexSource.substring(0,30)+"...");
                            }
                        } catch (e) { /* ... error handling ... */ }
                    });
                } else { console.warn("KaTeX not ready for manual ```math render on:", node); }

                // 4. NEW: Manual KaTeX Rendering for elements with 'katex-math-inline' and 'katex-math-display' classes
                if (isKaTeXReady) {
                    // Inline Math
                    var inlineMathElements = [];
                    if (node.nodeType === Node.ELEMENT_NODE && node.classList && node.classList.contains('katex-math-inline')) {
                        inlineMathElements.push(node);
                    } else if (node.querySelectorAll) {
                        node.querySelectorAll('span.katex-math-inline').forEach(function(el) { inlineMathElements.push(el); });
                    }

                    if (inlineMathElements.length > 0) console.log("Found", inlineMathElements.length, "custom 'katex-math-inline' elements to process.");
                    inlineMathElements.forEach(function(element) {
                        if (element.querySelector('.katex-html')) { // Check if already rendered
                            console.log("KaTeX Manual Render: katex-math-inline element already contains .katex-html, skipping.", element);
                            return;
                        }
                        var latexSource = element.textContent || "";
                        if (latexSource.trim() === "") { console.warn("KaTeX Manual Render: Empty LaTeX source in katex-math-inline span:", element); return; }
                        try {
                            katex.render(latexSource, element, {
                                throwOnError: renderOptions.throwOnError, errorColor: renderOptions.errorColor,
                                macros: renderOptions.macros, displayMode: false
                            });
                            console.log("KaTeX: Manually rendered katex-math-inline. Source:", latexSource.substring(0,30)+"...");
                        } catch (e) {
                            var errorMsgText = "KaTeX Manual Render Error (katex-math-inline): " + (e.message || e);
                            console.error(errorMsgText, e, "Problematic LaTeX Source:", latexSource, "Element:", element);
                            element.textContent = "[KaTeX Error]"; // Simplified error display
                            element.style.color = renderOptions.errorColor;
                        }
                    });

                    // Display Math
                    var displayMathElements = [];
                     if (node.nodeType === Node.ELEMENT_NODE && node.classList && node.classList.contains('katex-math-display')) {
                        displayMathElements.push(node);
                    } else if (node.querySelectorAll) {
                        node.querySelectorAll('div.katex-math-display').forEach(function(el) { displayMathElements.push(el); });
                    }

                    if (displayMathElements.length > 0) console.log("Found", displayMathElements.length, "custom 'katex-math-display' elements to process.");
                    displayMathElements.forEach(function(element) {
                         if (element.querySelector('.katex-html')) { // Check if already rendered
                            console.log("KaTeX Manual Render: katex-math-display element already contains .katex-html, skipping.", element);
                            return;
                        }
                        var latexSource = element.textContent || "";
                        if (latexSource.trim() === "") { console.warn("KaTeX Manual Render: Empty LaTeX source in katex-math-display div:", element); return; }
                        try {
                            katex.render(latexSource, element, {
                                throwOnError: renderOptions.throwOnError, errorColor: renderOptions.errorColor,
                                macros: renderOptions.macros, displayMode: true
                            });
                            console.log("KaTeX: Manually rendered katex-math-display. Source:", latexSource.substring(0,30)+"...");
                        } catch (e) {
                            var errorMsgText = "KaTeX Manual Render Error (katex-math-display): " + (e.message || e);
                            console.error(errorMsgText, e, "Problematic LaTeX Source:", latexSource, "Element:", element);
                            element.textContent = "[KaTeX Error]"; // Simplified error display
                            element.style.color = renderOptions.errorColor;
                        }
                    });
                } else { console.warn("KaTeX not ready for manual .katex-math-* class render on:", node); }
            } // end of processNodeWithLibraries

            function sanitizeHtmlInput(htmlString) {
                if (typeof htmlString !== 'string') return '';
                // Basic script tag removal. Consider a more robust sanitizer if input can be complex/untrusted.
                var SCRIPT_REGEX = /<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi;
                var sanitizedString = htmlString.replace(SCRIPT_REGEX, "");
                // Remove on<event> handlers
                sanitizedString = sanitizedString.replace(/ on\w+\s*=\s*['"][^'"]*['"]/gi, '');
                // Neutralize javascript: hrefs
                sanitizedString = sanitizedString.replace(/ href\s*=\s*['"]javascript:[^'"]*['"]/gi, ' href="#"');
                return sanitizedString;
            }

            window.renderFullContent = function(fullHtmlString) {
                console.log("renderFullContent called. HTML length:", fullHtmlString.length);
                var target = document.getElementById('latex_content_target');
                if (!target) { console.error("Target 'latex_content_target' not found."); return; }
                target.innerHTML = sanitizeHtmlInput(fullHtmlString);
                if (checkLibraryStates()) { // Ensure libs are ready before processing
                    processNodeWithLibraries(target);
                } else {
                    console.warn("renderFullContent: Libs not ready. Content set but not fully processed.");
                }
            };

            window.appendHtmlChunk = function(htmlChunk) {
                console.log("appendHtmlChunk. Preview:", htmlChunk.substring(0, 100).replace(/\n/g, "\\n"));
                var target = document.getElementById('latex_content_target');
                if (!target) { console.error("appendHtmlChunk: Target not found."); return; }
                var sanitizedChunk = sanitizeHtmlInput(htmlChunk);
                if (sanitizedChunk.trim() === "") { console.log("appendHtmlChunk: Empty after sanitization."); return; }

                var tempDiv = document.createElement('div');
                tempDiv.innerHTML = sanitizedChunk;
                var appendedNodes = [];
                while (tempDiv.firstChild) {
                    appendedNodes.push(target.appendChild(tempDiv.firstChild));
                }

                if (checkLibraryStates()) { // Ensure libs are ready
                    appendedNodes.forEach(function(appendedNode) {
                        processNodeWithLibraries(appendedNode);
                    });
                } else {
                    console.warn("appendHtmlChunk: Libs not ready. Chunk appended raw.");
                }
            };

            var initialCheckAttempts = 0;
            var maxInitialCheckAttempts = 60; // Wait for 6 seconds
            function initialLibsLoadCheck() {
                if (checkLibraryStates()) {
                    console.log("KaTeX and Prism are ready (initial check complete).");
                    // If content was set before libs were ready, try to process it now.
                    var target = document.getElementById('latex_content_target');
                    if (target && target.innerHTML.trim() !== "" && !target.querySelector('.katex-html')) { // Check if already processed
                         console.log("Libs became ready after content was set. Re-processing target.");
                         processNodeWithLibraries(target);
                    }
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

            // Ensure DOM is ready for library checks and initial processing
             if (document.readyState === "loading") {
                 document.addEventListener("DOMContentLoaded", initialLibsLoadCheck);
            } else {
                 initialLibsLoadCheck(); // Already loaded
            }
        </script>
    </body>
    </html>
    """.trimIndent()
}