package com.android.everytalk.ui.screens.account

import com.android.everytalk.util.locale.localizeUiMessage
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.android.everytalk.R
import com.android.everytalk.data.account.AccountManager
import com.android.everytalk.data.account.AccountOAuthProvider
import com.android.everytalk.ui.components.floatingEdgeGradient
import kotlinx.coroutines.delay

/**
 * 复用 Material 3 标准组件，提供同一条注册/登录流程。
 * 页面只显示账户资料与操作状态，验证码仅存在当前页面内存，不进入保存状态或导出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountScreen(
    manager: AccountManager,
    onBack: () -> Unit,
    onPrivacy: () -> Unit,
    onContinueWithoutAccount: (() -> Unit)? = null,
    onSignedIn: (() -> Unit)? = null,
    onLogout: (() -> Unit)? = null,
    accountDataActions: (@Composable () -> Unit)? = null,
) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showLogout by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val colors = MaterialTheme.colorScheme
    var displayName by remember(state.user?.id, state.user?.displayName) {
        mutableStateOf(state.user?.displayName.orEmpty())
    }
    // 复用系统照片选择器，不申请整个相册的读取权限，也不保存用户原始照片。
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) manager.updateAvatar(context, uri)
    }
    // 复用主题的灰阶与文字颜色，输入和操作控件在浅色、深色下保持同一层级。
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.onBackground,
        unfocusedBorderColor = colors.outlineVariant,
        focusedContainerColor = colors.surfaceContainer,
        unfocusedContainerColor = colors.surfaceContainer,
        disabledContainerColor = colors.surfaceContainer,
        cursorColor = colors.onBackground,
    )
    val actionColors = ButtonDefaults.buttonColors(
        containerColor = colors.onBackground,
        contentColor = colors.background,
        disabledContainerColor = colors.surfaceContainerHigh,
        disabledContentColor = colors.onSurfaceVariant,
    )
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topChromeHeight = statusBarInset + 68.dp
    val bottomChromeHeight = navigationBarInset + 48.dp
    val isWholePageLoading = state.busy && state.user != null &&
        state.pendingEmail == null && !state.awaitingOAuth && state.message == null

    LaunchedEffect(state.pendingEmail) { code = "" }
    LaunchedEffect(state.user?.id) {
        if (state.user != null) onSignedIn?.invoke()
    }
    LaunchedEffect(state.resendAtMillis) {
        nowMillis = System.currentTimeMillis()
        while (nowMillis < state.resendAtMillis) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
        }
    }
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            if (isWholePageLoading) {
                // 账号管理首次恢复资料时用整页状态，避免在页面底部额外挤出一行“正在处理”。
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = colors.onBackground,
                    trackColor = Color.Transparent,
                )
            } else {
            Column(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 28.dp,
                        top = if (onContinueWithoutAccount == null) topChromeHeight + 12.dp else 24.dp,
                        end = 28.dp,
                        bottom = bottomChromeHeight + 20.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val user = state.user
                if (user != null) {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconButton(onClick = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                enabled = !state.busy, modifier = Modifier.size(88.dp)) {
                                AccountAvatar(user.avatarUrl, user.displayName, stringResource(R.string.account_change_avatar),
                                    modifier = Modifier.fillMaxSize())
                            }
                            TextButton(onClick = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                enabled = !state.busy) { Text(stringResource(R.string.account_change_avatar)) }
                            Text(user.displayName, style = MaterialTheme.typography.titleLarge)
                            Text(user.email.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            user.createdAt?.let {
                                Text(stringResource(R.string.account_created_at, it.substringBefore('T')),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedTextField(value = displayName, onValueChange = { displayName = it.take(50) },
                        label = { Text(stringResource(R.string.account_display_name)) },
                        singleLine = true, enabled = !state.busy, shape = CircleShape, colors = fieldColors,
                        modifier = Modifier.fillMaxWidth())
                    Button(onClick = { manager.updateDisplayName(displayName) },
                        enabled = !state.busy && displayName.isNotBlank() && displayName.trim() != user.displayName,
                        shape = CircleShape, colors = actionColors, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.account_save_profile))
                    }
                    Text("登录后自动同步账号数据；退出登录后仅保留本地数据，API Key 等本地机密不会自动上传。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { manager.refreshAccount() }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_refresh)) }
                    Button(onClick = { showLogout = true }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_logout)) }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(painterResource(R.drawable.pixel_penguin_logo), contentDescription = null,
                            tint = colors.onBackground, modifier = Modifier.size(64.dp))
                        Text(stringResource(R.string.account_welcome),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center, color = colors.onBackground)
                        Text(stringResource(R.string.account_login_subtitle),
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                            color = colors.onSurfaceVariant)
                    }
                    if (!state.configured) {
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.account_unavailable), modifier = Modifier.padding(16.dp))
                        }
                    }
                    OutlinedTextField(
                        value = state.pendingEmail ?: email,
                        onValueChange = { email = it },
                        placeholder = { Text(stringResource(R.string.account_email)) },
                        leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) },
                        shape = CircleShape,
                        colors = fieldColors,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        singleLine = true,
                        enabled = state.configured && !state.busy && state.pendingEmail == null && !state.awaitingOAuth,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (state.configured && !state.busy && state.pendingEmail == null && !state.awaitingOAuth)
                                manager.sendEmailCode(email)
                        }),
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = context.getString(R.string.account_email)
                        },
                    )
                    val remainingSeconds = ((state.resendAtMillis - nowMillis + 999) / 1000).coerceAtLeast(0).toInt()
                    Button(
                        onClick = { manager.sendEmailCode(state.pendingEmail ?: email) },
                        enabled = state.configured && !state.busy && !state.awaitingOAuth && remainingSeconds == 0 &&
                            (state.pendingEmail ?: email).isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = CircleShape,
                        colors = actionColors,
                    ) {
                        Text(if (remainingSeconds > 0) stringResource(R.string.account_resend_after, remainingSeconds)
                            else stringResource(if (state.pendingEmail == null) R.string.account_send_code else R.string.account_resend_code))
                    }
                    if (state.pendingEmail != null) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter { char -> char in '0'..'9' }.take(6) },
                            placeholder = { Text(stringResource(R.string.account_code)) },
                            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                            shape = CircleShape,
                            colors = fieldColors,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            singleLine = true,
                            enabled = !state.busy && !state.awaitingOAuth,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (!state.busy && !state.awaitingOAuth && code.length == 6) manager.verifyEmailCode(code)
                            }),
                            modifier = Modifier.fillMaxWidth().semantics {
                                contentDescription = context.getString(R.string.account_code)
                            },
                        )
                        Button(onClick = { manager.verifyEmailCode(code) }, enabled = !state.busy && !state.awaitingOAuth && code.length == 6,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = CircleShape,
                            colors = actionColors) { Text(stringResource(R.string.account_verify)) }
                        TextButton(onClick = { manager.changeEmail() }, enabled = !state.busy && !state.awaitingOAuth) {
                            Text(stringResource(R.string.account_change_email))
                        }
                    }
                    Text(stringResource(R.string.account_signup_notice),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                    // 登录前直接说明账号模式的数据合并范围；游客模式仍只在本机保存。
                    Text(stringResource(R.string.account_local_data_notice),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        HorizontalDivider(Modifier.weight(1f), color = colors.outlineVariant)
                        Text(stringResource(R.string.account_social_separator),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        HorizontalDivider(Modifier.weight(1f), color = colors.outlineVariant)
                    }
                    // 邮箱是主入口；第三方仅显示 Logo，但保留 48dp 触摸区域和读屏名称。
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)) {
                        IconButton(
                            onClick = { manager.startOAuthLogin(AccountOAuthProvider.Google) { url ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } },
                            enabled = state.configured && !state.busy && !state.awaitingOAuth,
                            modifier = Modifier.size(48.dp),
                        ) {
                            // Google 四彩保持原色；GitHub 使用主题颜色，适配浅色与深色背景。
                            Icon(painterResource(R.drawable.ic_google_sign_in),
                                contentDescription = stringResource(R.string.account_google),
                                tint = Color.Unspecified, modifier = Modifier.size(24.dp))
                        }
                        IconButton(
                            onClick = { manager.startOAuthLogin(AccountOAuthProvider.GitHub) { url ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } },
                            enabled = state.configured && !state.busy && !state.awaitingOAuth,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_github),
                                contentDescription = stringResource(R.string.account_github),
                                tint = if (state.configured && !state.busy && !state.awaitingOAuth)
                                    colors.onBackground else colors.onSurface.copy(alpha = 0.38f),
                                modifier = Modifier.size(24.dp))
                        }
                    }
                    if (state.awaitingOAuth) {
                        // 授权等待是临时状态，收进一行紧凑提示，避免撑出一整块孤立区域。
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = colors.surfaceContainerLow,
                            border = BorderStroke(1.dp, colors.outlineVariant),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                                    color = colors.onSurfaceVariant)
                                Text(stringResource(R.string.account_oauth_pending_short),
                                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant)
                                TextButton(onClick = { manager.cancelOAuthLogin() }, enabled = !state.busy,
                                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onBackground)) {
                                    Text(stringResource(R.string.account_cancel_oauth))
                                }
                            }
                        }
                    }
                }
                accountDataActions?.invoke()
                if (state.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.account_processing))
                    }
                }
                // 只去掉与等待条重复的提示，校验失败等真实错误仍需显示。
                if (!state.awaitingOAuth || state.message != "请在浏览器中完成第三方登录") {
                    state.message?.let { Text(context.localizeUiMessage(it), style = MaterialTheme.typography.bodyMedium) }
                }
                if (state.configured && state.user == null && state.pendingEmail == null && !state.awaitingOAuth && state.message != null) {
                    TextButton(onClick = { manager.refreshAccount() }, enabled = !state.busy) {
                        Text(stringResource(R.string.account_refresh))
                    }
                }
                if (onContinueWithoutAccount != null) {
                    OutlinedButton(
                        onClick = onContinueWithoutAccount,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = CircleShape,
                    ) {
                        Text(stringResource(R.string.account_continue_without_account))
                    }
                }
                // 隐私入口属于页面内容，不再使用独立 bottomBar 造成视觉断层。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = onPrivacy,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                        Text(stringResource(R.string.account_privacy), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        }
        // 设置中的账号页使用与隐私政策页相同的透明渐变上下层。
        if (onContinueWithoutAccount == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topChromeHeight)
                    .floatingEdgeGradient(colors.background, fromTop = true)
                    .align(Alignment.TopCenter)
                    .zIndex(1f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .align(Alignment.TopCenter)
                    .zIndex(2f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.account_back))
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.account_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bottomChromeHeight)
                .floatingEdgeGradient(colors.background, fromTop = false)
                .align(Alignment.BottomCenter)
                .zIndex(1f),
        )
    }
    if (showLogout) AlertDialog(
        onDismissRequest = { showLogout = false },
        title = { Text(stringResource(R.string.account_logout)) },
        text = { Text(stringResource(R.string.account_logout_notice)) },
        confirmButton = { TextButton(onClick = { showLogout = false; (onLogout ?: { manager.logout() })() }) {
            Text(stringResource(R.string.account_logout))
        } },
        dismissButton = { TextButton(onClick = { showLogout = false }) { Text(stringResource(R.string.account_cancel)) } },
    )
}
