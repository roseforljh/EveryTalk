package com.android.everytalk.ui.components
import com.android.everytalk.statecontroller.*

import android.annotation.SuppressLint
import android.content.ClipData
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.android.everytalk.R
import com.android.everytalk.ui.components.syntax.SyntaxHighlightTheme
import com.android.everytalk.ui.components.syntax.HighlightCache
import com.android.everytalk.ui.components.content.isPreviewSupported
import com.android.everytalk.ui.theme.chatColors
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SelectableCodeTextView(
    code: String,
    language: String,
    isDarkTheme: Boolean,
    headerColor: Color,
    codeTextColor: Color,
    modifier: Modifier = Modifier
) {
    val syntaxTheme = if (isDarkTheme) SyntaxHighlightTheme.Dark else SyntaxHighlightTheme.Light
    val highlightedCode by produceState<CharSequence>(
        code,
        code,
        language,
        isDarkTheme,
        codeTextColor,
    ) {
        value = code
        if (HighlightCache.shouldHighlight(code, isStreaming = false)) {
            value = withContext(Dispatchers.Default) {
                HighlightCache.highlight(code, language, isDarkTheme, syntaxTheme)
                    .toSpannableString(codeTextColor)
            }
        }
    }
    val languageLabel = remember(language) { language.trim().ifBlank { "CODE" }.uppercase() }
    val headerArgb = headerColor.toArgb()
    val codeArgb = codeTextColor.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { context ->
            ScrollView(context).apply {
                isFillViewport = true
                overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                val density = resources.displayMetrics.density
                val topPadding = (8f * density).toInt()
                val bottomPadding = (64f * density).toInt()
                val content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, topPadding, 0, bottomPadding)
                }

                val labelView = TextView(context).apply {
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(headerArgb)
                    text = languageLabel
                    includeFontPadding = true
                    setPadding(0, 0, 0, (16f * density).toInt())
                }

                val codeView = TextView(context).apply {
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(codeArgb)
                    setLineSpacing(0f, 1.0f)
                    includeFontPadding = true
                    setTextIsSelectable(true)
                    setHorizontallyScrolling(false)
                    text = highlightedCode
                }

                content.addView(
                    labelView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                content.addView(
                    codeView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    content,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                tag = Pair(labelView, codeView)
            }
        },
        update = { scrollView ->
            @Suppress("UNCHECKED_CAST")
            val views = scrollView.tag as Pair<TextView, TextView>
            val labelView = views.first
            val codeView = views.second
            labelView.text = languageLabel
            labelView.setTextColor(headerArgb)
            codeView.setTextColor(codeArgb)
            codeView.text = highlightedCode
        }
    )
}

