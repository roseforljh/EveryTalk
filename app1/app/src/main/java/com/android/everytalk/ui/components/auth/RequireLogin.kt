package com.android.everytalk.ui.components.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.data.auth.Feature
import com.android.everytalk.data.auth.FeatureGate
import com.android.everytalk.data.auth.FeatureGateCoordinator
import kotlinx.coroutines.flow.StateFlow

/**
 * 商业入口的统一 Compose 封装。
 * 页面只提供 action 和内容，登录判断、锁标识和待执行操作保存都由 FeatureGate 负责。
 */
@Composable
internal fun RequireLogin(
    feature: Feature,
    gate: FeatureGate,
    coordinator: FeatureGateCoordinator,
    action: () -> Unit,
    content: @Composable (onClick: () -> Unit, locked: Boolean) -> Unit,
) {
    content({ coordinator.requireLogin(feature, action) }, gate.isLocked(feature))
}

/** 应用根页面统一挂载，避免每个商业入口各自实现登录底部弹窗。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeatureGatePromptHost(
    prompt: StateFlow<Feature?>,
    coordinator: FeatureGateCoordinator,
    onOpenLogin: () -> Unit,
) {
    val feature by prompt.collectAsState()
    if (feature == null) return

    ModalBottomSheet(onDismissRequest = coordinator::cancel) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Lock, contentDescription = null)
                Text(stringResource(R.string.feature_login_required))
            }
            Text(stringResource(R.string.feature_login_required_description))
            Button(
                onClick = {
                    coordinator.beginLogin()
                    onOpenLogin()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.account_login_action))
            }
            OutlinedButton(
                onClick = coordinator::cancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.account_cancel))
            }
        }
    }
}
