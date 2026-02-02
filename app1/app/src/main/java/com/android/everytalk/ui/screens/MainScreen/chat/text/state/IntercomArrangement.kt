package com.android.everytalk.ui.screens.MainScreen.chat.text.state

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 顶部对齐的 Arrangement：内容从顶部开始排列。
 * 配合 scrollToItem 实现用户消息置顶效果。
 */
class IntercomArrangement private constructor(
    private val itemSpacing: Dp
) : Arrangement.Vertical {

    override val spacing: Dp = itemSpacing

    override fun Density.arrange(
        totalSize: Int,
        sizes: IntArray,
        outPositions: IntArray
    ) {
        if (sizes.isEmpty()) return

        val spacingPx = itemSpacing.roundToPx()
        var currentY = 0
        
        for (i in sizes.indices) {
            outPositions[i] = currentY
            currentY += sizes[i]
            if (i < sizes.lastIndex) {
                currentY += spacingPx
            }
        }
    }

    companion object {
        fun bottomAligned(spacing: Dp = 4.dp): IntercomArrangement {
            return IntercomArrangement(itemSpacing = spacing)
        }
    }
}
