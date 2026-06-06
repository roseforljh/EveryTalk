package com.android.everytalk.ui.components.markdown

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.util.TypedValue
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.CoreProps
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.movement.MovementMethodPlugin
import org.commonmark.node.Code
import org.commonmark.node.StrongEmphasis

internal fun chatGptHeadingRelativeSizeMultiplier(level: Int): Float {
    return ChatMarkdownTextStyle.headingRelativeSizeMultiplier(level)
}

internal fun chatInlineCodeTextColorArgb(isDark: Boolean): Int {
    return android.graphics.Color.parseColor(
        if (isDark) {
            ChatMarkdownTextStyle.INLINE_CODE_TEXT_COLOR_DARK_HEX
        } else {
            ChatMarkdownTextStyle.INLINE_CODE_TEXT_COLOR_LIGHT_HEX
        }
    )
}

internal fun chatHeadingTextWeight(level: Int): Int {
    return if (level.coerceIn(1, 6) <= 3) 600 else 400
}

internal class ChatTextWeightSpan(
    val weight: Int
) : MetricAffectingSpan() {
    override fun updateDrawState(textPaint: TextPaint) {
        applyWeight(textPaint)
    }

    override fun updateMeasureState(textPaint: TextPaint) {
        applyWeight(textPaint)
    }

    private fun applyWeight(textPaint: TextPaint) {
        val base = textPaint.typeface ?: Typeface.DEFAULT
        textPaint.typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight.coerceIn(1, 1000), base.isItalic)
        } else {
            Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
        textPaint.isFakeBoldText = false
    }
}

object MarkwonCache {

    private val cacheMap = mutableMapOf<String, Markwon>()
    private val lock = Any()

    fun getOrCreate(
        context: Context,
        isDark: Boolean,
        textSize: Float,
        imageClickListener: ((String) -> Unit)? = null
    ): Markwon {
        val roundedSize = textSize.toInt()
        val cacheKey = "v39_dark=${isDark}_size=${roundedSize}"

        synchronized(lock) {
            cacheMap[cacheKey]?.let { return it }

            val density = context.resources.displayMetrics.density
            val mathTextSize = textSize * density

            val markwon = Markwon.builder(context)
                .usePlugin(MovementMethodPlugin.none())
                .usePlugin(ImagesPlugin.create { plugin ->
                    plugin.addSchemeHandler(io.noties.markwon.image.data.DataUriSchemeHandler.create())
                })
                .usePlugin(TablePlugin.create(context))
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(JLatexMathPlugin.create(mathTextSize) { builder ->
                    builder.inlinesEnabled(true)
                    builder.theme().blockFitCanvas(false)
                })
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        builder.codeBlockMargin(0)

                        val smallBulletPx =
                            (ChatMarkdownTextStyle.LIST_BULLET_SIZE_DP * density).toInt()
                                .coerceAtLeast(2)
                        val horizontalRuleHeightPx =
                            (ChatMarkdownTextStyle.HORIZONTAL_RULE_THICKNESS_DP * density).toInt()
                                .coerceAtLeast(1)
                        builder.bulletWidth(smallBulletPx)
                        builder.headingBreakHeight((8f * density).toInt())
                        builder.thematicBreakHeight(horizontalRuleHeightPx)
                    }

                    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                        builder.setFactory(org.commonmark.node.Heading::class.java) { _, props ->
                            val level = CoreProps.HEADING_LEVEL.get(props) ?: 1
                            arrayOf<Any>(
                                ChatTextWeightSpan(chatHeadingTextWeight(level)),
                                RelativeSizeSpan(chatGptHeadingRelativeSizeMultiplier(level))
                            )
                        }

                        builder.setFactory(StrongEmphasis::class.java) { _, _ ->
                            arrayOf<Any>(ChatTextWeightSpan(600))
                        }

                        builder.setFactory(Code::class.java) { _, _ ->
                            val codeTextColor = chatInlineCodeTextColorArgb(isDark)
                            arrayOf<Any>(
                                TypefaceSpan("monospace"),
                                StyleSpan(Typeface.BOLD),
                                RelativeSizeSpan(ChatMarkdownTextStyle.INLINE_CODE_RELATIVE_SIZE),
                                ForegroundColorSpan(codeTextColor)
                            )
                        }

                        val customBlockMargin =
                            (ChatMarkdownTextStyle.LIST_MARKER_WIDTH_DP * density).toInt()
                        val topLevelItemSpacing =
                            (ChatMarkdownTextStyle.LIST_TOP_LEVEL_ITEM_SPACING_DP * density).toInt()
                        val nestedTopSpacing =
                            (ChatMarkdownTextStyle.LIST_NESTED_TOP_SPACING_DP * density).toInt()
                        val listItemLineHeight =
                            TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_SP,
                                ChatMarkdownTextStyle.LIST_ITEM_LINE_HEIGHT_SP,
                                context.resources.displayMetrics
                            ).toInt().coerceAtLeast(1)
                        builder.setFactory(org.commonmark.node.ListItem::class.java) { configuration, props ->
                            val isOrdered =
                                CoreProps.LIST_ITEM_TYPE.get(props) === CoreProps.ListItemType.ORDERED
                            if (isOrdered) {
                                val numberStr =
                                    CoreProps.ORDERED_LIST_ITEM_NUMBER.get(props)?.toString() ?: "1"
                                val level = ((CoreProps.BULLET_LIST_ITEM_LEVEL.get(props) ?: 0) - 1)
                                    .coerceAtLeast(0)
                                CustomOrderedListItemSpan(
                                    configuration.theme(),
                                    "$numberStr.\u00a0",
                                    customBlockMargin,
                                    level,
                                    topLevelItemSpacing,
                                    nestedTopSpacing,
                                    listItemLineHeight
                                )
                            } else {
                                val level = CoreProps.BULLET_LIST_ITEM_LEVEL.get(props) ?: 0
                                val bulletWidth =
                                    (ChatMarkdownTextStyle.listBulletSizeDp(level) * density).toInt()
                                        .coerceAtLeast(2)
                                CustomBulletListItemSpan(
                                    configuration.theme(),
                                    level,
                                    customBlockMargin,
                                    bulletWidth,
                                    topLevelItemSpacing,
                                    nestedTopSpacing,
                                    listItemLineHeight
                                )
                            }
                        }
                    }
                })
                .build()

            if (cacheMap.size > 4) {
                cacheMap.remove(cacheMap.keys.first())
            }
            cacheMap[cacheKey] = markwon
            return markwon
        }
    }

    fun clear() {
        synchronized(lock) { cacheMap.clear() }
    }

    fun getStats(): String {
        synchronized(lock) {
            return "Cached instances: ${cacheMap.size}, Keys: ${cacheMap.keys.joinToString()}"
        }
    }
}
