package com.android.everytalk.ui.screens.settings

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavController
import com.android.everytalk.navigation.Screen

private const val PAGE_SLIDE_DURATION_MS = 300

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

/** 二级及子页面统一的横向推入动画，支持系统预测式侧滑返回手势实时预览。 */
internal fun hierarchicalEnterTransition(): EnterTransition =
    slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(durationMillis = PAGE_SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
    )

/** 当前页面被新子页面压入栈底时的微幅左移视差动画。 */
internal fun hierarchicalExitTransition(): ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { fullWidth -> -fullWidth / 4 },
        animationSpec = tween(durationMillis = PAGE_SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
    )

/** 栈顶子页面侧滑返回时，底层页面从左侧微幅滑回的预览动画。 */
internal fun hierarchicalPopEnterTransition(): EnterTransition =
    slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / 4 },
        animationSpec = tween(durationMillis = PAGE_SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
    )

/** 栈顶页面响应侧滑返回手势向右滑出的预览动画。 */
internal fun hierarchicalPopExitTransition(): ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(durationMillis = PAGE_SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
    )

/** 设置三点菜单同级页面（模型配置、服务器、Skill）之间的进入动画：同级切换用淡入，外部进入用侧滑。 */
internal fun settingsRouteEnterTransition(from: String?, to: String?): EnterTransition =
    if (isSettingsMenuTransition(from, to)) settingsMenuEnterTransition() else hierarchicalEnterTransition()

/** 设置三点菜单同级页面的退出动画：同级切换用淡出，压入详情页用左移视差。 */
internal fun settingsRouteExitTransition(from: String?, to: String?): ExitTransition =
    if (isSettingsMenuTransition(from, to)) settingsMenuExitTransition() else hierarchicalExitTransition()

/** 设置三点菜单同级页面从子页面返回时的恢复动画。 */
internal fun settingsRoutePopEnterTransition(from: String?, to: String?): EnterTransition =
    if (isSettingsMenuTransition(from, to)) settingsMenuEnterTransition() else hierarchicalPopEnterTransition()

/** 设置三点菜单同级页面返回聊天首页时的侧滑退出动画。 */
internal fun settingsRoutePopExitTransition(from: String?, to: String?): ExitTransition =
    if (isSettingsMenuTransition(from, to)) settingsMenuExitTransition() else hierarchicalPopExitTransition()

/**
 * 在设置三点菜单的同级页面（模型配置、服务器、Skill）之间切换时，
 * 始终以聊天首页为直接栈底，使任一同级页面侧滑返回都能直接预览并回到聊天首页。
 */
internal fun NavController.navigateSettingsMenuRoute(route: String) {
    navigate(route) {
        popUpTo(Screen.CHAT_SCREEN) { inclusive = false }
        launchSingleTop = true
    }
}
