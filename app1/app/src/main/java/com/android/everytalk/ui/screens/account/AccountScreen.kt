package com.android.everytalk.ui.screens.account

import com.android.everytalk.util.locale.localizeUiMessage
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.everytalk.R
import com.android.everytalk.data.account.AccountManager
import kotlinx.coroutines.delay

/**
 * 复用 Material 3 标准组件，提供同一条注册/登录流程。
 * 页面只显示账户资料与操作状态，验证码仅存在当前页面内存，不进入保存状态或导出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountScreen(manager: AccountManager, onBack: () -> Unit, onPrivacy: () -> Unit) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var email by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showLogout by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(state.pendingEmail) { code = "" }
    LaunchedEffect(state.resendAtMillis) {
        nowMillis = System.currentTimeMillis()
        while (nowMillis < state.resendAtMillis) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
        }
    }
    // 页面从后台恢复时重新核验身份；网络临时失败不会删除已加密保存的会话。
    DisposableEffect(manager, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && manager.state.value.configured && !manager.state.value.busy)
                manager.refreshAccount()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.account_title)) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.account_back))
            }
        })
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val user = state.user
            if (user != null) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(48.dp))
                        Text(user.email.orEmpty(), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.account_id, user.id), style = MaterialTheme.typography.bodySmall)
                        user.createdAt?.let {
                            Text(stringResource(R.string.account_created_at, it.substringBefore('T')),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(stringResource(R.string.account_local_data_notice), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { manager.refreshAccount() }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_refresh)) }
                Button(onClick = { showLogout = true }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_logout)) }
            } else {
                Text(stringResource(R.string.account_welcome), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.account_signup_notice), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.configured) {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.account_unavailable), modifier = Modifier.padding(16.dp))
                    }
                }
                OutlinedButton(
                    onClick = { manager.startGoogleLogin { url ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } },
                    enabled = state.configured && !state.busy && !state.awaitingGoogle,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.account_google)) }
                if (state.awaitingGoogle) {
                    Text(stringResource(R.string.account_google_pending), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { manager.cancelGoogleLogin() }, enabled = !state.busy) {
                        Text(stringResource(R.string.account_cancel_google))
                    }
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = state.pendingEmail ?: email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.account_email)) },
                    singleLine = true,
                    enabled = state.configured && !state.busy && state.pendingEmail == null && !state.awaitingGoogle,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (state.configured && !state.busy && state.pendingEmail == null && !state.awaitingGoogle)
                            manager.sendEmailCode(email)
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
                val remainingSeconds = ((state.resendAtMillis - nowMillis + 999) / 1000).coerceAtLeast(0).toInt()
                OutlinedButton(
                    onClick = { manager.sendEmailCode(state.pendingEmail ?: email) },
                    enabled = state.configured && !state.busy && !state.awaitingGoogle && remainingSeconds == 0 && email.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (remainingSeconds > 0) stringResource(R.string.account_resend_after, remainingSeconds)
                        else stringResource(if (state.pendingEmail == null) R.string.account_send_code else R.string.account_resend_code))
                }
                if (state.pendingEmail != null) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { char -> char in '0'..'9' }.take(6) },
                        label = { Text(stringResource(R.string.account_code)) },
                        singleLine = true,
                        enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (!state.busy && code.length == 6) manager.verifyEmailCode(code)
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { manager.verifyEmailCode(code) }, enabled = !state.busy && code.length == 6,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_verify)) }
                    TextButton(onClick = { manager.changeEmail() }, enabled = !state.busy) {
                        Text(stringResource(R.string.account_change_email))
                    }
                }
            }
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.account_processing))
                }
            }
            state.message?.let { Text(context.localizeUiMessage(it), style = MaterialTheme.typography.bodyMedium) }
            if (state.configured && state.user == null && state.pendingEmail == null && !state.awaitingGoogle && state.message != null) {
                TextButton(onClick = { manager.refreshAccount() }, enabled = !state.busy) {
                    Text(stringResource(R.string.account_refresh))
                }
            }
            TextButton(onClick = onPrivacy) { Text(stringResource(R.string.account_privacy)) }
        }
    }
    if (showLogout) AlertDialog(
        onDismissRequest = { showLogout = false },
        title = { Text(stringResource(R.string.account_logout)) },
        text = { Text(stringResource(R.string.account_logout_notice)) },
        confirmButton = { TextButton(onClick = { showLogout = false; manager.logout() }) {
            Text(stringResource(R.string.account_logout))
        } },
        dismissButton = { TextButton(onClick = { showLogout = false }) { Text(stringResource(R.string.account_cancel)) } },
    )
}
