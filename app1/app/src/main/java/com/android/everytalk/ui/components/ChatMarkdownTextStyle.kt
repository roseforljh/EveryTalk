package com.android.everytalk.ui.components

internal object ChatMarkdownTextStyle {
    const val BODY_FONT_SIZE_SP = 14f
    const val BODY_LINE_HEIGHT_SP = 22f

    const val INLINE_CODE_RELATIVE_SIZE = 0.92f
    const val INLINE_CODE_TEXT_COLOR_LIGHT_HEX = "#4F5661"
    const val INLINE_CODE_TEXT_COLOR_DARK_HEX = "#D1D5DB"
    const val INLINE_CODE_BACKGROUND_LIGHT_ALPHA = 0f
    const val INLINE_CODE_BACKGROUND_DARK_ALPHA = 0f
    const val INLINE_CODE_BACKGROUND_RADIUS_DP = 3f
    const val INLINE_CODE_BACKGROUND_HORIZONTAL_PADDING_DP = 2f

    const val LIST_MARKER_WIDTH_DP = 16f
    const val LIST_BULLET_SIZE_DP = 5f
    const val LIST_NESTED_BULLET_SIZE_DP = 4f
    const val LIST_BULLET_START_PADDING_DP = 2f
    const val LIST_BULLET_TOP_PADDING_DP = 9.5f
    const val LIST_NESTED_INDENT_DP = 24f
    const val LIST_ITEM_SPACING_DP = 12f
    const val LIST_TOP_LEVEL_ITEM_SPACING_DP = LIST_ITEM_SPACING_DP
    const val LIST_NESTED_TOP_SPACING_DP = 0f
    const val LIST_ITEM_LINE_HEIGHT_SP = BODY_LINE_HEIGHT_SP

    const val SPACING_PARAGRAPH_DP = 12f
    const val SPACING_BEFORE_HEADING_DP = 16f
    const val SPACING_AFTER_HEADING_DP = 4f
    const val HORIZONTAL_RULE_THICKNESS_DP = 0.5f
    const val SPACING_AFTER_DIVIDER_DP = 12f
    const val SPACING_AFTER_LIST_DP = 8f
    const val SPACING_AFTER_QUOTE_DP = 8f

    const val TABLE_CELL_LINE_HEIGHT_SP = 18f
    const val TABLE_ROW_VERTICAL_PADDING_DP = 4f
    const val TABLE_BLOCK_VERTICAL_PADDING_DP = 8f
    const val TABLE_BETWEEN_TABLES_VERTICAL_PADDING_DP = 14f

    fun headingFontSizeSp(level: Int): Float {
        return when (level.coerceIn(1, 6)) {
            1 -> 20f
            2 -> 18f
            3 -> 16f
            else -> 14f
        }
    }

    fun headingLineHeightSp(level: Int): Float {
        return when (level.coerceIn(1, 6)) {
            1 -> 28f
            2 -> 26f
            3 -> 24f
            else -> 22f
        }
    }

    fun headingRelativeSizeMultiplier(level: Int): Float {
        return headingFontSizeSp(level) / BODY_FONT_SIZE_SP
    }

    fun listBulletSizeDp(level: Int): Float {
        return if (level <= 0) LIST_BULLET_SIZE_DP else LIST_NESTED_BULLET_SIZE_DP
    }

    fun listBulletFilled(level: Int): Boolean = level <= 0
}
