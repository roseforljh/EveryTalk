package com.android.everytalk.ui.screens.settings

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.android.everytalk.navigation.Screen

/** 三点菜单中的同级页面共用淡入淡出，避免路由切换与页签切换使用不同动效。 */
internal fun settingsMenuEnterTransition(): EnterTransition =
    fadeIn(tween(durationMillis = 220, easing = FastOutSlowInEasing))

internal fun settingsMenuExitTransition(): ExitTransition =
    fadeOut(tween(durationMillis = 220, easing = FastOutSlowInEasing))

/** 仅统一设置菜单内的切换；聊天、服务器详情等页面继续使用原有导航动画。 */
internal fun isSettingsMenuTransition(from: String?, to: String?): Boolean {
    val routes = setOf(Screen.SETTINGS_SCREEN, Screen.COMPUTER_SCREEN, Screen.SKILL_SCREEN, Screen.ACCOUNT_SCREEN)
    return from in routes && to in routes
}
