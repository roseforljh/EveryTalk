package com.android.everytalk.ui.components.auth

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.android.everytalk.R
import com.android.everytalk.data.auth.AccountSwitchChoice
import com.android.everytalk.data.auth.AccountSwitchProtection

/** 换账号时阻止隐式上传，必须由用户明确选择本地数据处理方式。 */
@Composable
internal fun AccountSwitchProtectionDialog(
    protection: AccountSwitchProtection,
) {
    val request by protection.request.collectAsState()
    if (request == null) return

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(stringResource(R.string.account_switch_title)) },
        text = { Text(stringResource(R.string.account_switch_description)) },
        confirmButton = {
            TextButton(onClick = { protection.choose(AccountSwitchChoice.UPLOAD_TO_CURRENT) }) {
                Text(stringResource(R.string.account_switch_upload))
            }
        },
        dismissButton = {
            TextButton(onClick = { protection.choose(AccountSwitchChoice.KEEP_LOCAL) }) {
                Text(stringResource(R.string.account_switch_keep_local))
            }
            TextButton(onClick = { protection.choose(AccountSwitchChoice.CLEAR_LOCAL) }) {
                Text(stringResource(R.string.account_switch_clear_local))
            }
        },
    )
}
