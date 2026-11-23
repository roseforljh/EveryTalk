package com.android.everytalk.ui.screens.account

import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppOutlinedButton
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.ParticlesBackground
import com.android.everytalk.ui.theme.AppInputFieldShape

import com.android.everytalk.util.locale.localizeUiMessage
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
 * 待执行的登录动作：用户点击邮箱或第三方登录时先弹出条款确认框，同意后继续执行。
 */
private sealed interface PendingAuthAction {
    data class EmailCode(val email: String) : PendingAuthAction
    data class OAuth(val provider: AccountOAuthProvider) : PendingAuthAction
}

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
    showBackButton: Boolean = onContinueWithoutAccount == null,
    onSignedIn: (() -> Unit)? = null,
    onLogout: (() -> Unit)? = null,
    accountDataActions: (@Composable () -> Unit)? = null,
) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showLogout by remember { mutableStateOf(false) }
    var showGuestDialog by remember { mutableStateOf(false) }
    var hasAcceptedTerms by rememberSaveable { mutableStateOf(false) }
    var pendingAuthAction by remember { mutableStateOf<PendingAuthAction?>(null) }
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
    val socialButtonColors = ButtonDefaults.outlinedButtonColors(
        containerColor = colors.surfaceContainerLow,
        contentColor = colors.onBackground,
        disabledContainerColor = colors.surfaceContainerLow.copy(alpha = 0.5f),
        disabledContentColor = colors.onSurface.copy(alpha = 0.38f),
    )

    // 统一执行已确认条款后的登录动作，避免邮箱与第三方分支重复判断。
    fun executeAuthAction(action: PendingAuthAction) {
        when (action) {
            is PendingAuthAction.EmailCode -> manager.sendEmailCode(action.email)
            is PendingAuthAction.OAuth -> manager.startOAuthLogin(action.provider) { url ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }

    // 首次选择账号登录方式时先弹出隐私与数据同步条款，同意后再发起请求。
    fun requestAuthWithTerms(action: PendingAuthAction) {
        if (hasAcceptedTerms) {
            executeAuthAction(action)
        } else {
            pendingAuthAction = action
        }
    }
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
        ParticlesBackground(color = colors.onBackground)
        BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            if (isWholePageLoading) {
                // 账号管理首次恢复资料时用整页状态，避免在页面底部额外挤出一行“正在处理”。
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = colors.onBackground,
                    trackColor = Color.Transparent,
                )
            } else {
            val user = state.user
            val isLoginView = user == null
            Column(
                modifier = Modifier
                    .widthIn(max = if (isLoginView) 400.dp else 420.dp)
                    .fillMaxWidth()
                    .then(if (isLoginView) Modifier.heightIn(min = maxHeight) else Modifier)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 24.dp,
                        top = if (showBackButton) topChromeHeight + 12.dp else statusBarInset + 24.dp,
                        end = 24.dp,
                        bottom = bottomChromeHeight + 16.dp,
                    ),
                verticalArrangement = if (isLoginView) Arrangement.spacedBy(12.dp) else Arrangement.spacedBy(16.dp),
            ) {
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
                            AppOutlinedButton(onClick = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
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
                        singleLine = true, enabled = !state.busy, shape = AppInputFieldShape, colors = fieldColors,
                        modifier = Modifier.fillMaxWidth())
                    Button(onClick = { manager.updateDisplayName(displayName) },
                        enabled = !state.busy && displayName.isNotBlank() && displayName.trim() != user.displayName,
                        shape = CircleShape, colors = actionColors, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.account_save_profile))
                    }
                    Text(stringResource(R.string.account_local_sync_notice), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { manager.refreshAccount() }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_refresh)) }
                    Button(onClick = { showLogout = true }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_logout)) }
                    accountDataActions?.invoke()
                } else {
                    val remainingSeconds = ((state.resendAtMillis - nowMillis + 999) / 1000).coerceAtLeast(0).toInt()
                    val canStartOAuth = state.configured && !state.busy && !state.awaitingOAuth
                    val activeEmail = state.pendingEmail ?: email

                    // 上半区留白居中展示品牌，中部聚焦邮箱主流程与本地游客按钮，底部放置第三方图标入口。
                    Spacer(Modifier.weight(1f))
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.pixel_penguin_logo),
                            contentDescription = null,
                            tint = colors.onBackground,
                            modifier = Modifier.size(72.dp),
                        )
                        Text(
                            text = stringResource(R.string.account_welcome),
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                            textAlign = TextAlign.Center,
                            color = colors.onBackground,
                        )
                    }
                    Spacer(Modifier.weight(0.6f))

                    if (!state.configured) {
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.account_unavailable), modifier = Modifier.padding(16.dp))
                        }
                    }

                    OutlinedTextField(
                        value = activeEmail,
                        onValueChange = { email = it },
                        placeholder = { Text(stringResource(R.string.account_email)) },
                        leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) },
                        shape = AppInputFieldShape,
                        colors = fieldColors,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        singleLine = true,
                        enabled = state.configured && !state.busy && state.pendingEmail == null && !state.awaitingOAuth,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (state.configured && !state.busy && state.pendingEmail == null && !state.awaitingOAuth && email.isNotBlank()) {
                                requestAuthWithTerms(PendingAuthAction.EmailCode(email))
                            }
                        }),
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = context.getString(R.string.account_email)
                        },
                    )

                    if (state.pendingEmail == null) {
                        Button(
                            onClick = { requestAuthWithTerms(PendingAuthAction.EmailCode(email)) },
                            enabled = state.configured && !state.busy && !state.awaitingOAuth && remainingSeconds == 0 && email.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = CircleShape,
                            colors = actionColors,
                        ) {
                            Text(
                                text = if (remainingSeconds > 0) stringResource(R.string.account_resend_after, remainingSeconds)
                                else stringResource(R.string.account_send_code),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        if (onContinueWithoutAccount != null) {
                            OutlinedButton(
                                onClick = { showGuestDialog = true },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = CircleShape,
                                colors = socialButtonColors,
                                border = BorderStroke(
                                    1.dp,
                                    if (!state.busy) colors.outlineVariant else colors.outlineVariant.copy(alpha = 0.5f),
                                ),
                            ) {
                                Text(
                                    text = stringResource(R.string.account_continue_without_account),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter { char -> char in '0'..'9' }.take(6) },
                            placeholder = { Text(stringResource(R.string.account_code)) },
                            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                            shape = AppInputFieldShape,
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
                        Button(
                            onClick = { manager.verifyEmailCode(code) },
                            enabled = !state.busy && !state.awaitingOAuth && code.length == 6,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = CircleShape,
                            colors = actionColors,
                        ) {
                            Text(
                                text = stringResource(R.string.account_verify),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { manager.sendEmailCode(activeEmail) },
                                enabled = state.configured && !state.busy && !state.awaitingOAuth && remainingSeconds == 0,
                                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant),
                            ) {
                                Text(
                                    if (remainingSeconds > 0) stringResource(R.string.account_resend_after, remainingSeconds)
                                    else stringResource(R.string.account_resend_code),
                                )
                            }
                            TextButton(
                                onClick = { manager.changeEmail() },
                                enabled = !state.busy && !state.awaitingOAuth,
                                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant),
                            ) {
                                Text(stringResource(R.string.account_change_email))
                            }
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
                                AppOutlinedButton(onClick = { manager.cancelOAuthLogin() }, enabled = !state.busy,
                                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onBackground)) {
                                    Text(stringResource(R.string.account_cancel_oauth))
                                }
                            }
                        }
                    }

                    if (state.busy) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.account_processing), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // 只去掉与等待条重复的提示，校验失败等真实错误仍需显示。
                    if (!state.awaitingOAuth || state.message != "请在浏览器中完成第三方登录") {
                        state.message?.let {
                            Text(
                                text = context.localizeUiMessage(it),
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (state.configured && state.pendingEmail == null && !state.awaitingOAuth && state.message != null) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            AppOutlinedButton(onClick = { manager.refreshAccount() }, enabled = !state.busy) {
                                Text(stringResource(R.string.account_refresh))
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    if (state.pendingEmail == null) {
                        // 底部第三方快捷登录仅保留圆形图标按钮，点击前先弹出条款确认。
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            HorizontalDivider(Modifier.weight(1f), color = colors.outlineVariant)
                            Text(
                                text = stringResource(R.string.account_social_separator),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            HorizontalDivider(Modifier.weight(1f), color = colors.outlineVariant)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedIconButton(
                                onClick = { requestAuthWithTerms(PendingAuthAction.OAuth(AccountOAuthProvider.Google)) },
                                enabled = canStartOAuth,
                                modifier = Modifier.size(52.dp),
                                shape = CircleShape,
                                colors = IconButtonDefaults.outlinedIconButtonColors(
                                    containerColor = colors.surfaceContainerLow,
                                    disabledContainerColor = colors.surfaceContainerLow.copy(alpha = 0.5f),
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (canStartOAuth) colors.outlineVariant else colors.outlineVariant.copy(alpha = 0.5f),
                                ),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_google_sign_in),
                                    contentDescription = stringResource(R.string.account_google),
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            OutlinedIconButton(
                                onClick = { requestAuthWithTerms(PendingAuthAction.OAuth(AccountOAuthProvider.GitHub)) },
                                enabled = canStartOAuth,
                                modifier = Modifier.size(52.dp),
                                shape = CircleShape,
                                colors = IconButtonDefaults.outlinedIconButtonColors(
                                    containerColor = colors.surfaceContainerLow,
                                    disabledContainerColor = colors.surfaceContainerLow.copy(alpha = 0.5f),
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (canStartOAuth) colors.outlineVariant else colors.outlineVariant.copy(alpha = 0.5f),
                                ),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_github),
                                    contentDescription = stringResource(R.string.account_github),
                                    tint = if (canStartOAuth) colors.onBackground else colors.onSurface.copy(alpha = 0.38f),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
                if (user != null) {
                    if (state.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.account_processing))
                        }
                    }
                    state.message?.let { Text(context.localizeUiMessage(it), style = MaterialTheme.typography.bodyMedium) }
                    // 已登录态保留隐私政策入口，方便在账号管理页随时查阅。
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
        }
        // 设置中的账号页使用与隐私政策页相同的透明渐变上下层。
        if (showBackButton) {
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
    if (showGuestDialog && onContinueWithoutAccount != null) {
        val dialogBg = appDialogContainerColor()
        val dialogContent = appDialogContentColor()
        AlertDialog(
            onDismissRequest = { showGuestDialog = false },
            modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
            shape = AppDialogShape,
            containerColor = dialogBg,
            titleContentColor = dialogContent,
            textContentColor = dialogContent,
            title = { Text(stringResource(R.string.account_guest_dialog_title)) },
            text = {
                Text(
                    text = stringResource(R.string.account_guest_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showGuestDialog = false
                        onContinueWithoutAccount()
                    },
                    shape = CircleShape,
                    colors = actionColors,
                ) {
                    Text(stringResource(R.string.account_guest_dialog_confirm))
                }
            },
            dismissButton = {
                AppOutlinedButton(onClick = { showGuestDialog = false }) {
                    Text(stringResource(R.string.account_cancel))
                }
            },
        )
    }
    pendingAuthAction?.let { action ->
        val dialogBg = appDialogContainerColor()
        val dialogContent = appDialogContentColor()
        AlertDialog(
            onDismissRequest = { pendingAuthAction = null },
            modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
            shape = AppDialogShape,
            containerColor = dialogBg,
            titleContentColor = dialogContent,
            textContentColor = dialogContent,
            title = { Text(stringResource(R.string.account_terms_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.account_terms_dialog_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = {
                            pendingAuthAction = null
                            onPrivacy()
                        },
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.onBackground),
                    ) {
                        Text(
                            text = stringResource(R.string.account_terms_dialog_view_privacy),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        hasAcceptedTerms = true
                        pendingAuthAction = null
                        executeAuthAction(action)
                    },
                    shape = CircleShape,
                    colors = actionColors,
                ) {
                    Text(stringResource(R.string.account_terms_dialog_confirm))
                }
            },
            dismissButton = {
                AppOutlinedButton(onClick = { pendingAuthAction = null }) {
                    Text(stringResource(R.string.account_cancel))
                }
            },
        )
    }
    if (showLogout) AlertDialog(
        onDismissRequest = { showLogout = false },
        title = { Text(stringResource(R.string.account_logout)) },
        text = { Text(stringResource(R.string.account_logout_notice)) },
        confirmButton = { AppOutlinedButton(onClick = { showLogout = false; (onLogout ?: { manager.logout() })() }) {
            Text(stringResource(R.string.account_logout))
        } },
        dismissButton = { AppOutlinedButton(onClick = { showLogout = false }) { Text(stringResource(R.string.account_cancel)) } },
    )
}
