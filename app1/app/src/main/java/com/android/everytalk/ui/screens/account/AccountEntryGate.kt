package com.android.everytalk.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.rememberHistoryLoadingShimmerBrush
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import com.android.everytalk.data.account.AccountManager
import com.android.everytalk.data.auth.AuthRepository
import com.android.everytalk.data.auth.AuthState
import com.android.everytalk.ui.screens.appinfo.PrivacyPolicyScreen

/**
 * 启动时先恢复账号：已验证的账号直接进入主界面，未登录则显示同一张注册/登录表单。
 * 游客选择仅保留在本次启动的页面状态中，旋转或浏览器返回不会丢失；新启动会再次提供登录入口。
 * 主界面在用户完成登录或明确选择游客后才挂载，避免后台聊天页抢占返回键和输入。
 */
@Composable
internal fun AccountEntryGate(manager: AccountManager, content: @Composable () -> Unit) {
    // 保留旧调用签名，供独立账号组件测试使用；应用入口使用下面的持久化认证版本。
    val state by manager.state.collectAsState()
    val processingDescription = stringResource(R.string.account_processing)
    var continueWithoutAccount by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }

    // 游客后来登录后重新启用启动检查，主动退出时回到登录页。
    LaunchedEffect(state.user) {
        if (state.user != null) {
            continueWithoutAccount = false
            showPrivacy = false
        }
    }
    when {
        // 恢复会话期间显示主界面骨架，底色与启动窗口一致，避免灰屏和闪烁。
        !state.initialized -> StartupSkeleton(processingDescription)
        state.user != null || continueWithoutAccount -> content()
        showPrivacy -> PrivacyPolicyScreen(onBack = { showPrivacy = false })
        else -> AccountScreen(
            manager = manager,
            onBack = {},
            onPrivacy = { showPrivacy = true },
            onContinueWithoutAccount = { continueWithoutAccount = true },
        )
    }
}

/**
 * 应用启动入口：本地模式和账号会话都持久化，只有首次安装且没有旧会话时显示欢迎页。
 * SessionExpired 仍然进入主界面，用户点击商业功能时再回到账号页重新登录。
 */
@Composable
internal fun AccountEntryGate(
    manager: AccountManager,
    authRepository: AuthRepository,
    content: @Composable () -> Unit,
) {
    val startup by authRepository.startup.collectAsState()
    val authState by authRepository.state.collectAsState()
    val processingDescription = stringResource(R.string.account_processing)
    var showPrivacy by rememberSaveable { mutableStateOf(false) }

    when {
        !startup.initialized -> StartupSkeleton(processingDescription)
        startup.onboardingCompleted || authState is AuthState.SignedIn || authState is AuthState.SessionExpired -> content()
        showPrivacy -> PrivacyPolicyScreen(onBack = { showPrivacy = false })
        else -> AccountScreen(
            manager = manager,
            onBack = {},
            onPrivacy = { showPrivacy = true },
            onContinueWithoutAccount = { authRepository.chooseLocalOnly() },
        )
    }
}

/** 启动骨架：顶栏三个控件 + 底部输入框的轮廓，和主界面空会话布局对齐。 */
@Composable
private fun StartupSkeleton(description: String) {
    val brush = rememberHistoryLoadingShimmerBrush()
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = description },
    ) {
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(brush))
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(128.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(brush))
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(CircleShape).background(brush))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(26.dp))
                .background(brush),
        )
    }
}
