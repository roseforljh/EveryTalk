package com.example.everytalk.ui.screens.BubbleMain

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import java.util.Arrays

import com.example.everytalk.util.markdown.TextSegment
import com.example.everytalk.util.markdown.parseMarkdownSegments
import com.example.everytalk.ui.components.PooledKatexWebView
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.ui.screens.BubbleMain.Main.MyCodeBlockComposable
import com.example.everytalk.ui.screens.BubbleMain.Main.toHexCss

private fun convertMarkdownToHtml(markdown: String): String {
    val extensions = Arrays.asList(TablesExtension.create(), StrikethroughExtension.create())
    val parser = Parser.builder().extensions(extensions).build()
    val document = parser.parse(markdown)
    val renderer = HtmlRenderer.builder().extensions(extensions).build()
    return renderer.render(document)
}

private const val CONTEXT_MENU_ANIMATION_DURATION_MS = 150
private val CONTEXT_MENU_CORNER_RADIUS = 16.dp
private val CONTEXT_MENU_ITEM_ICON_SIZE = 20.dp
private val CONTEXT_MENU_FINE_TUNE_OFFSET_X = (-120).dp
private val CONTEXT_MENU_FINE_TUNE_OFFSET_Y = (-8).dp
private val CONTEXT_MENU_FIXED_WIDTH = 160.dp

@Composable
internal fun AnimatedDropdownMenuItem(
    visibleState: MutableTransitionState<Boolean>, delay: Int = 0,
    text: @Composable () -> Unit, onClick: () -> Unit, leadingIcon: @Composable (() -> Unit)? = null
) {
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(
            animationSpec = tween(
                CONTEXT_MENU_ANIMATION_DURATION_MS,
                delayMillis = delay,
                easing = LinearOutSlowInEasing
            )
        ) +
                scaleIn(
                    animationSpec = tween(
                        CONTEXT_MENU_ANIMATION_DURATION_MS,
                        delayMillis = delay,
                        easing = LinearOutSlowInEasing
                    ), transformOrigin = TransformOrigin(0f, 0f)
                ),
        exit = fadeOut(
            animationSpec = tween(
                CONTEXT_MENU_ANIMATION_DURATION_MS,
                easing = FastOutLinearInEasing
            )
        ) +
                scaleOut(
                    animationSpec = tween(
                        CONTEXT_MENU_ANIMATION_DURATION_MS,
                        easing = FastOutLinearInEasing
                    ), transformOrigin = TransformOrigin(0f, 0f)
                )
    ) {
        DropdownMenuItem(
            text = text, onClick = onClick, leadingIcon = leadingIcon,
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.onSurface,
                leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
fun rememberKatexBaseHtmlTemplate(
    backgroundColor: String, textColor: String, errorColor: String, throwOnError: Boolean
): String {
    return remember(backgroundColor, textColor, errorColor, throwOnError) {
        Log.d(
            "HTMLTemplate",
            "Regenerating HTML template for AiMessageContent. BG: $backgroundColor, TC: $textColor, ErrC: $errorColor, ThrErr: $throwOnError"
        )
        """
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
                    min-height: calc(100vh - 16px); 
                    overflow-y: auto; 
                    overflow-x: hidden; 
                    font-family: sans-serif; 
                    line-height: 1.5; /* Ensure reasonable line height for text around KaTeX */
                }
                #latex_container { 
                    width: 100%; 
                    overflow-x: hidden !important; 
                    overflow-y: hidden; 
                }
                /* CSS MODIFICATION: Style .katex for inline behavior, .katex-display as fallback */
                .katex { /* Primary style for all math, as we force $...$ input to KaTeX */
                    display: inline-block; 
                    margin: 0 0.1em;      
                    padding: 0;
                    text-align: left;
                    vertical-align: baseline; 
                    font-size: 1em;       
                    line-height: normal;
                }
                .katex-display { /* Fallback style if $$ somehow still produces .katex-display */
                    display: inline !important; 
                    margin: 0 0.1em !important; 
                    padding: 0 !important;
                    text-align: left;
                    vertical-align: baseline;
                }
                #latex_container::-webkit-scrollbar { height: 6px; background-color: #f0f0f0; }
                #latex_container::-webkit-scrollbar-thumb { background-color: #cccccc; border-radius: 3px; }
                .error-message { color: $errorColor; font-weight: bold; padding: 10px; border: 1px solid $errorColor; background-color: #fff0f0; margin-bottom: 5px;}
            </style>
        </head>
        <body>
            <div id="latex_container">
                <div id="latex_content_target">Loading KaTeX content...</div>
            </div>
            <script type="text/javascript">
                console.log("Base HTML script block executed. Waiting for KaTeX libraries...");
                var katexRenderQueue = [];
                var isKaTeXReady = false;

                function checkKaTeXReady() {
                    return (typeof renderMathInElement === 'function') &&
                         (typeof katex === 'object' && katex.render); 
                }

                function processRenderQueue() {
                    if (!isKaTeXReady) return;
                    while(katexRenderQueue.length > 0) {
                        var item = katexRenderQueue.shift();
                        renderMixedContentWithLatex(item.rawLatexString, true);
                    }
                }

                function renderMixedContentWithLatex(rawLatexString, isRetryAttempt) {
                    console.log("renderMixedContentWithLatex called:", rawLatexString.substring(0,100));
                    var target = document.getElementById('latex_content_target');
                    if (!target) return;
                    target.innerHTML = rawLatexString; 
                    if (!isKaTeXReady) {
                        console.log("KaTeX not ready, queueing render.");
                        if (!katexRenderQueue.find(x=>x.rawLatexString===rawLatexString)) {
                            katexRenderQueue.push({rawLatexString, isRetry: true});
                        }
                        setTimeout(function() {
                            if (!isKaTeXReady && target && !target.querySelector('.error-message')) {
                                target.innerHTML = "<div class='error-message'>KaTeX libraries did not load/initialize in time. Raw content shown below.</div><hr>" + rawLatexString;
                            }
                        }, 3000);
                        return;
                    }
                    try {
                        renderMathInElement(target, {
                            delimiters: [
                                {left: "${'$'}", right: "${'$'}", display: false},    // KaTeX will use .katex
                                {left: "\\(", right: "\\)", display: false},      // KaTeX will use .katex
                                {left: "\\[", right: "\\]", display: true}       // KaTeX will use .katex-display
                                        ],
                            throwOnError: $throwOnError,
                            errorColor: "$errorColor",
                            macros: { "\\RR": "\\mathbb{R}" }
                        });
                    } catch (e) {
                        var msg = "Failed to render math: " + (e && e.message ? e.message : e);
                        if (target && !target.querySelector('.error-message')) {
                            target.innerHTML = "<div class='error-message'>" + msg + "</div>";
                        }
                    }
                }

                function waitKaTeXAndProcess() {
                    var attempts = 0, maxAttempts = 30; 
                    function poll() {
                        if (checkKaTeXReady()) {
                            isKaTeXReady = true;
                            console.log("KaTeX READY. Processing render queue.");
                            processRenderQueue();
                        } else if (attempts < maxAttempts) {
                            attempts++; setTimeout(poll,100);
                        } else {
                            console.error("KaTeX libraries not loaded after polling.");
                            var target = document.getElementById('latex_content_target');
                            if (target && !target.querySelector('.error-message')) {
                                target.innerHTML = "<div class='error-message'>KaTeX Libraries failed to load.</div>";
                            }
                            processRenderQueue();
                        }
                    }
                    poll();
                }
                document.addEventListener("DOMContentLoaded", function() {
                    waitKaTeXAndProcess();
                });

                window.renderLatexContent = function(content) {
                    renderMixedContentWithLatex(content, false);
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }
}

@Composable
internal fun AiMessageContent(
    message: Message,
    appViewModel: AppViewModel,
    fullMessageTextToCopy: String,
    displayedText: String,
    isStreaming: Boolean,
    showLoadingDots: Boolean,
    contentColor: Color,
    codeBlockBackgroundColor: Color,
    codeBlockContentColor: Color,
    codeBlockCornerRadius: Dp,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val density = LocalDensity.current

    var isAiContextMenuVisible by remember(fullMessageTextToCopy) { mutableStateOf(false) }
    var pressOffset by remember(fullMessageTextToCopy) { mutableStateOf(Offset.Zero) }
    var showSelectableTextDialog by remember(fullMessageTextToCopy) { mutableStateOf(false) }

    Column(
        modifier = modifier.pointerInput(fullMessageTextToCopy) {
            detectTapGestures(onLongPress = { offsetValue ->
                onUserInteraction(); pressOffset = offsetValue; isAiContextMenuVisible = true
            })
        }
    ) {
        if (showLoadingDots && displayedText.isBlank()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 28.dp)
            ) {
                ThreeDotsLoadingAnimation(
                    dotColor = contentColor,
                    modifier = Modifier.offset(y = (-6).dp)
                )
            }
        } else if (displayedText.isNotBlank()) {
            val segments = remember(displayedText) {
                Log.d("AICONTENT", "分段解析流式文本: ${displayedText.takeLast(20)}")
                parseMarkdownSegments(displayedText.trim()) // Parser now sets isBlock=false for $$
            }

            val textColorHex = remember(contentColor) { contentColor.toHexCss() }
            val baseHtmlTemplate = rememberKatexBaseHtmlTemplate(
                backgroundColor = "transparent",
                textColor = textColorHex,
                errorColor = "#CD5C5C",
                throwOnError = false
            )

            if (segments.isEmpty() && displayedText.trim().isNotBlank()) {
                val htmlContent =
                    remember(displayedText) { convertMarkdownToHtml(displayedText.trim()) }
                val stableKey = "${message.id}_katex_fallback_${htmlContent.hashCode()}"
                PooledKatexWebView(
                    appViewModel = appViewModel,
                    contentId = stableKey,
                    latexInput = htmlContent,
                    htmlTemplate = baseHtmlTemplate,
                    modifier = Modifier
                        .heightIn(min = 1.dp)
                )
            } else {
                // To achieve inline flow of text and math, Column is not ideal.
                // For simplicity, we'll keep Column, meaning each segment is still a new "line"
                // in the Column, but the math itself won't cause extra line breaks within its WebView.
                Column(modifier = Modifier.fillMaxWidth()) {
                    segments.forEachIndexed { index, segment ->
                        when (segment) {
                            is TextSegment.Normal -> {
                                if (segment.text.isNotBlank()) {
                                    val htmlContent =
                                        remember(segment.text) { convertMarkdownToHtml(segment.text) }
                                    val stableKey =
                                        "${message.id}_katex_segment_normal_${index}"
                                    PooledKatexWebView(
                                        appViewModel = appViewModel,
                                        contentId = stableKey,
                                        latexInput = htmlContent,
                                        htmlTemplate = baseHtmlTemplate,
                                        modifier = Modifier
                                            .heightIn(min = 1.dp)
                                    )
                                }
                            }

                            is TextSegment.CodeBlock -> {
                                if (segment.language != null && segment.language.isNotBlank()) {
                                    Text(
                                        text = segment.language,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = contentColor.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(
                                            top = if (index > 0 && segments[index - 1] !is TextSegment.CodeBlock) 8.dp else 2.dp,
                                            bottom = 2.dp
                                        )
                                    )
                                }
                                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                    MyCodeBlockComposable(
                                        language = segment.language,
                                        code = segment.code,
                                        backgroundColor = codeBlockBackgroundColor,
                                        contentColor = codeBlockContentColor,
                                        cornerRadius = codeBlockCornerRadius,
                                        fixedWidth = this.maxWidth,
                                        showTopBar = true,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }

                            is TextSegment.MathFormula -> {
                                val latexRaw = segment.latex
                                // segment.isBlock is now always false from the parser
                                val stableKey =
                                    "${message.id}_katex_math_${index}"
                                PooledKatexWebView(
                                    appViewModel = appViewModel,
                                    contentId = stableKey,
                                    // MODIFICATION: Always use $...$ to ensure KaTeX uses .katex class
                                    // This aligns with our CSS strategy of primarily styling .katex for inline.
                                    latexInput = "\$${latexRaw}\$",
                                    htmlTemplate = baseHtmlTemplate,
                                    modifier = Modifier
                                        // .fillMaxWidth() // Removed: allow inline flow if WebView width is not constrained by parent
                                        .heightIn(min = 1.dp) // Consistent minimal height
                                        .padding(vertical = 0.dp) // Consistent minimal padding
                                )
                            }
                        }
                        if (index < segments.size - 1) {
                            val nextIsCode = segments.getOrNull(index + 1) is TextSegment.CodeBlock
                            val currentIsCode = segment is TextSegment.CodeBlock
                            if (currentIsCode || nextIsCode) { // Larger spacer around code blocks
                                Spacer(modifier = Modifier.height(8.dp))
                            } else {
                                // No spacer or very small spacer between normal text and inline math
                                // to allow them to flow more naturally if they were in a FlowRow.
                                // Since they are in a Column, this won't make them same-line,
                                // but reduces vertical gap.
                                // Spacer(modifier = Modifier.height(1.dp))
                            }
                        }
                    }
                }
            }
        }

        if (isAiContextMenuVisible) {
            val localContextForToast = LocalContext.current
            val aiMenuVisibility = remember { MutableTransitionState(false) }.apply {
                targetState = isAiContextMenuVisible
            }
            val dropdownMenuOffsetX =
                with(density) { pressOffset.x.toDp() } + CONTEXT_MENU_FINE_TUNE_OFFSET_X
            val dropdownMenuOffsetY =
                with(density) { pressOffset.y.toDp() } + CONTEXT_MENU_FINE_TUNE_OFFSET_Y
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(
                    x = with(density) { dropdownMenuOffsetX.roundToPx() },
                    y = with(density) { dropdownMenuOffsetY.roundToPx() }),
                onDismissRequest = { isAiContextMenuVisible = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = false
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS),
                    color = Color.White,
                    tonalElevation = 3.dp,
                    modifier = Modifier
                        .width(CONTEXT_MENU_FIXED_WIDTH)
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS)
                        )
                        .padding(1.dp)
                ) {
                    Column {
                        AnimatedDropdownMenuItem(
                            visibleState = aiMenuVisibility,
                            delay = 0,
                            text = { Text("复制") },
                            onClick = {
                                clipboardManager.setText(AnnotatedString(fullMessageTextToCopy))
                                Toast.makeText(
                                    localContextForToast,
                                    "AI回复已复制",
                                    Toast.LENGTH_SHORT
                                ).show()
                                isAiContextMenuVisible = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    "复制AI回复",
                                    Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                                )
                            })
                        AnimatedDropdownMenuItem(
                            visibleState = aiMenuVisibility,
                            delay = 30,
                            text = { Text("选择文本") },
                            onClick = {
                                showSelectableTextDialog = true; isAiContextMenuVisible = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.SelectAll,
                                    "选择文本",
                                    Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                                )
                            })
                    }
                }
            }
        }

        if (showSelectableTextDialog) {
            SelectableTextDialog(
                textToDisplay = fullMessageTextToCopy,
                onDismissRequest = { showSelectableTextDialog = false })
        }
    }
}

@Composable
internal fun SelectableTextDialog(textToDisplay: String, onDismissRequest: () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp)
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.75f),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            SelectionContainer(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = textToDisplay,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
fun ThreeDotsLoadingAnimation(
    modifier: Modifier = Modifier,
    dotColor: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        (0..2).forEach { index ->
            val infiniteTransition =
                rememberInfiniteTransition(label = "dot_loading_transition_$index")
            val animatedAlpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(animation = keyframes {
                    durationMillis =
                        1200; 0.3f at 0 with LinearEasing; 1.0f at 200 with LinearEasing; 0.3f at 400 with LinearEasing; 0.3f at 1200 with LinearEasing
                }, repeatMode = RepeatMode.Restart, initialStartOffset = StartOffset(index * 150)),
                label = "dot_alpha_$index"
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor.copy(alpha = animatedAlpha), CircleShape)
            )
        }
    }
}