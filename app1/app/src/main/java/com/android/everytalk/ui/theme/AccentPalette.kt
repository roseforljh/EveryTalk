package com.android.everytalk.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * ChatGPT 的强调色板。
 *
 * 出处：APK classes.dex 的 `z7`（枚举与色值表）和 `y7`（ordinal → switch 分支映射）。
 * `z7.a()` 是每个强调色的主色，返回 x641.K(...) 打包的 ARGB。
 *
 * 显示名按 ChatGPT 中文界面的实际文案（截图可见）：
 *   蓝色 / 青色 / 绿色 / 青柠绿 / 黄色 / 橙色 / 粉色 / 洋红色 / 紫色 / 黑色
 * 枚举共 11 项，其中 Default 与 Blue 共用同一色值，实际是 10 种颜色，
 * 所以 Default 不单独出现在列表里（showInPicker = false）。
 */
enum class AccentColor(
    val displayName: String,
    val color: Color,
    val showInPicker: Boolean = true,
    /** 用户气泡底色（GPT z7.e）；0 表示沿用默认灰色气泡。顺序为 亮色、暗色。文字色不随强调色变化。 */
    private val bubble: LongArray = longArrayOf(0, 0),
) {
    Default("默认", Color(0xFF3566F0), showInPicker = false),
    Blue("蓝色", Color(0xFF3566F0), bubble = longArrayOf(0xFFEDF3FE, 0xFF020562)),
    Cyan("青色", Color(0xFF00B1FF), bubble = longArrayOf(0xFFEBF7FE, 0xFF032837)),
    Green("绿色", Color(0xFF04BB9F), bubble = longArrayOf(0xFFD7FFF5, 0xFF032A23)),
    Lime("青柠绿", Color(0xFFB6D80B), bubble = longArrayOf(0xFFEFFED0, 0xFF232D02)),
    Yellow("黄色", Color(0xFFFFCC38), bubble = longArrayOf(0xFFFFF7E2, 0xFFC27B13)),
    Orange("橙色", Color(0xFFFF8066), bubble = longArrayOf(0xFFFFF2F0, 0xFF550208)),
    Pink("粉色", Color(0xFFFA70AB), bubble = longArrayOf(0xFFFFF1F5, 0xFF470126)),
    Magenta("洋红色", Color(0xFFDD6ADC), bubble = longArrayOf(0xFFFEF0FC, 0xFF3F023C)),
    Purple("紫色", Color(0xFFA25BFF), bubble = longArrayOf(0xFFF6F1FE, 0xFF2E0350)),
    Black("黑色", Color(0xFF000000));

    /** 是否改变界面配色；默认与黑色沿用黑白主题（GPT y7 中二者同走默认分支）。 */
    val isTinted: Boolean get() = bubble[0] != 0L

    fun userBubble(dark: Boolean): Color? =
        if (isTinted) Color(bubble[if (dark) 1 else 0]) else null

    companion object {
        /** 弹窗里要展示的项，顺序与 ChatGPT 截图一致。 */
        val pickerEntries: List<AccentColor> = entries.filter { it.showInPicker }
    }
}

/** 当前强调色，由 [App1Theme] 在根部提供。 */
val LocalAccentColor = staticCompositionLocalOf { AccentColor.Default }