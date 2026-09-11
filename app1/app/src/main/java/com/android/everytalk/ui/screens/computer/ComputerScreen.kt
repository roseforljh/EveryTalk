package com.android.everytalk.ui.screens.computer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.android.everytalk.R
import com.android.everytalk.BuildConfig
import com.android.everytalk.data.computer.ComputerDiagnostics
import com.android.everytalk.data.computer.ComputerFailureStage
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.data.computer.ComputerSetupStage
import com.android.everytalk.data.computer.ComputerStatus
import com.android.everytalk.data.computer.CloudflareOAuthConfig
import com.android.everytalk.data.computer.CloudflareSettingsOAuthFlow
import com.android.everytalk.data.computer.CloudflareSettingsOAuthStore
import com.android.everytalk.data.computer.CloudflareOAuthCallbackBus
import com.android.everytalk.data.computer.CloudflareTokenExchangeResult
import com.android.everytalk.navigation.Screen
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.addConfirmedComputer
import com.android.everytalk.statecontroller.probeComputerHostKey
import com.android.everytalk.statecontroller.provisionComputerContainer
import com.android.everytalk.statecontroller.refreshComputerFromList
import com.android.everytalk.statecontroller.showSnackbar
import com.android.everytalk.statecontroller.listCloudflareAccounts
import com.android.everytalk.statecontroller.cloudflareLoginIdentity
import com.android.everytalk.statecontroller.createCloudflareComputer
import com.android.everytalk.ui.components.floatingEdgeGradient
import com.android.everytalk.ui.screens.settings.SettingsTabMenu
import com.android.everytalk.util.locale.localizeUiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import io.ktor.client.HttpClient
import org.koin.java.KoinJavaComponent

@Composable
fun ComputerScreen(
    viewModel: AppViewModel,
    navController: NavController,
    onImportExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val computers by viewModel.computers.collectAsState()
    val scope = rememberCoroutineScope()
    var showAddCard by remember { mutableStateOf(false) }
    var showTabMenu by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(ComputerAddFormState()) }
    var isBusy by remember { mutableStateOf(false) }
    var setupStage by remember { mutableStateOf<ComputerSetupStage?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf<PreparedComputerAdd?>(null) }
    var hostKey by remember { mutableStateOf<com.android.everytalk.data.computer.HostKeyProbeResult?>(null) }
    var cloudflareToken by remember { mutableStateOf<CloudflareTokenExchangeResult?>(null) }
    var cloudflareAccounts by remember { mutableStateOf<List<com.android.everytalk.data.computer.CloudflareApiAccount>>(emptyList()) }
    val cloudflareOAuthFlow = remember(viewModel) {
        CloudflareSettingsOAuthFlow(
            config = CloudflareOAuthConfig(
                clientId = BuildConfig.CLOUDFLARE_OAUTH_CLIENT_ID,
                redirectUri = BuildConfig.CLOUDFLARE_OAUTH_REDIRECT_URI,
            ),
            store = viewModel.cloudflareOAuthStore,
            httpClient = KoinJavaComponent.getKoin().get<HttpClient>(),
        )
    }
    val latestPrepared by rememberUpdatedState(prepared)
    val latestCloudflareToken by rememberUpdatedState(cloudflareToken)
    LaunchedEffect(Unit) {
        // 这是一次性 StateFlow 回调。consume 会把值清为 null，不能用 collectLatest，
        // 否则清空状态会取消正在进行的 Token 交换。
        CloudflareOAuthCallbackBus.callbacks.collect { uri ->
            if (uri == null) return@collect
            if (!cloudflareOAuthFlow.ownsCallback(uri, form.id)) return@collect
            CloudflareOAuthCallbackBus.consume(uri)
            isBusy = true
            errorText = null
            runCatching {
                val result = cloudflareOAuthFlow.consume(uri, form.id)
                try {
                    val accounts = withContext(Dispatchers.IO) { viewModel.listCloudflareAccounts(result.accessToken) }
                    require(accounts.isNotEmpty()) { "Cloudflare 没有可用 Account" }
                    val identity = try { withContext(Dispatchers.IO) { viewModel.cloudflareLoginIdentity(result.accessToken) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    cloudflareAccounts = accounts
                    cloudflareToken = CloudflareTokenExchangeResult(
                        result.accessToken.copyOf(), result.refreshToken?.copyOf(), result.scopes, result.expiresInSeconds,
                    )
                    val selected = accounts.singleOrNull()
                    form = form.copy(
                        cloudflareAuthorized = true,
                        cloudflareIdentity = identity?.displayName ?: identity?.email,
                        cloudflareAccountId = selected?.id.orEmpty(),
                        cloudflareAccountName = selected?.name.orEmpty(),
                        cloudflareScopes = result.scopes,
                    )
                } finally {
                    result.accessToken.fill('\u0000')
                    result.refreshToken?.fill('\u0000')
                }
            }.onFailure { errorText = it.message ?: "Cloudflare 登录失败" }
            isBusy = false
        }
    }
    ComputerSecureWindowEffect(showAddCard)

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    DisposableEffect(Unit) {
        onDispose {
            latestPrepared?.clear()
            latestCloudflareToken?.accessToken?.fill('\u0000')
            latestCloudflareToken?.refreshToken?.fill('\u0000')
        }
    }

    fun closeAddCard() {
        if (isBusy) return
        viewModel.cloudflareOAuthStore.cancel(form.id)
        cloudflareToken?.accessToken?.fill('\u0000')
        cloudflareToken?.refreshToken?.fill('\u0000')
        cloudflareToken = null
        cloudflareAccounts = emptyList()
        prepared?.clear()
        prepared = null
        hostKey = null
        setupStage = null
        errorText = null
        form = ComputerAddFormState()
        showAddCard = false
    }

    /** 返回已有设置页，并把三点菜单选择的目标交给该页面处理。 */
    fun returnToSettings(tabIndex: Int) {
        val existingEntry = runCatching {
            navController.getBackStackEntry(Screen.SETTINGS_SCREEN)
        }.getOrNull()
        val targetEntry = existingEntry ?: run {
            navController.navigate(Screen.SETTINGS_SCREEN) { launchSingleTop = true }
            navController.currentBackStackEntry
        }
        // 从聊天直接进入本页时也要把目标页签交给新建的设置页。
        targetEntry?.savedStateHandle?.set(Screen.SETTINGS_TAB_REQUEST_KEY, tabIndex)
        showTabMenu = false
        if (existingEntry != null) {
            navController.popBackStack(Screen.SETTINGS_SCREEN, inclusive = false)
        }
    }

    /** 顶部返回键和系统返回手势都跳过配置页，直接回到聊天首页。 */
    fun returnToChatHome() {
        showTabMenu = false
        if (!navController.popBackStack(Screen.CHAT_SCREEN, inclusive = false)) {
            navController.navigate(Screen.CHAT_SCREEN) { launchSingleTop = true }
        }
    }

    BackHandler(onBack = ::returnToChatHome)

    fun validationMessage(error: ComputerAddFormError): String = context.getString(
        when (error) {
            ComputerAddFormError.HOST_REQUIRED -> R.string.computer_validation_host
            ComputerAddFormError.PORT_INVALID -> R.string.computer_validation_port
            ComputerAddFormError.USERNAME_REQUIRED -> R.string.computer_validation_username
            ComputerAddFormError.PASSWORD_REQUIRED -> R.string.computer_validation_password
            ComputerAddFormError.PRIVATE_KEY_REQUIRED -> R.string.computer_validation_private_key
        },
    )

    fun startHostKeyProbe() {
        if (form.provider == com.android.everytalk.data.computer.ComputerProvider.CLOUDFLARE) {
            errorText = cloudflareAddError(form)
            if (errorText != null) return
            val token = cloudflareToken ?: run { errorText = "请先登录 Cloudflare"; return }
            val account = cloudflareAccounts.firstOrNull { it.id == form.cloudflareAccountId }
                ?: run { errorText = "请选择 Cloudflare Account"; return }
            isBusy = true
            scope.launch {
                try {
                    // 管理器会销毁它接收的 Token。传入副本，保存失败后保留表单内
                    // 尚未过期的 OAuth 结果供重试，避免“界面已登录但 Token 已清零”。
                    withContext(Dispatchers.IO) { viewModel.createCloudflareComputer(form.displayName,
                        token.copy(accessToken = token.accessToken.copyOf(), refreshToken = token.refreshToken?.copyOf()), account) }
                    viewModel.showSnackbar("Cloudflare Computer 已添加")
                    token.accessToken.fill('\u0000'); token.refreshToken?.fill('\u0000')
                    cloudflareToken = null; showAddCard = false; form = ComputerAddFormState()
                } catch (error: Throwable) { errorText = error.message ?: "Cloudflare 保存失败" }
                finally { isBusy = false }
            }
            return
        }
        val validationError = form.validationError()
        if (validationError != null) {
            errorText = validationMessage(validationError)
            return
        }
        prepared?.clear()
        val current = form.prepare()
        prepared = current
        errorText = null
        setupStage = ComputerSetupStage.READING_HOST_KEY
        isBusy = true
        scope.launch {
            try {
                hostKey = withContext(Dispatchers.IO) {
                    viewModel.probeComputerHostKey(current.request)
                }
            } catch (error: Throwable) {
                ComputerDiagnostics.logFailure(ComputerFailureStage.HOST_KEY_PROBE, error)
                current.clear()
                if (prepared === current) prepared = null
                errorText = context.localizeUiMessage(
                    error.message ?: context.getString(R.string.unknown_error),
                )
            } finally {
                setupStage = null
                isBusy = false
            }
        }
    }

    fun confirmHostKey() {
        val current = prepared ?: return
        val confirmed = hostKey ?: return
        hostKey = null
        if (
            current.request.runMode == ComputerRunMode.CONTAINER &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setupStage = ComputerSetupStage.AUTHENTICATING
        errorText = null
        isBusy = true
        scope.launch {
            var addedComputer: com.android.everytalk.data.computer.Computer? = null
            var failureStage = ComputerFailureStage.ADD_SERVER
            try {
                val added = withContext(Dispatchers.IO) {
                    viewModel.addConfirmedComputer(
                        current.request,
                        confirmed,
                        current.sudoPassword?.copyOf(),
                        onProgress = { stage -> withContext(Dispatchers.Main) { setupStage = stage } },
                    )
                }
                addedComputer = added
                if (
                    added.runMode == ComputerRunMode.CONTAINER &&
                    added.status == ComputerStatus.CONFIGURATION_REQUIRED
                ) {
                    failureStage = ComputerFailureStage.CONTAINER_PROVISION
                    withContext(Dispatchers.IO) {
                        viewModel.provisionComputerContainer(
                            added.id,
                            onProgress = { stage -> withContext(Dispatchers.Main) { setupStage = stage } },
                        )
                    }
                }
                viewModel.showSnackbar(context.getString(R.string.computer_add_success))
                prepared = null
                setupStage = null
                errorText = null
                form = ComputerAddFormState()
                showAddCard = false
            } catch (error: Throwable) {
                ComputerDiagnostics.logFailure(failureStage, error)
                val localizedError = context.localizeUiMessage(
                    error.message ?: context.getString(R.string.unknown_error),
                )
                val savedComputer = addedComputer
                if (savedComputer != null) {
                    prepared = null
                    setupStage = null
                    errorText = null
                    form = ComputerAddFormState()
                    showAddCard = false
                    viewModel.showSnackbar(
                        context.getString(R.string.computer_add_saved_needs_repair, localizedError),
                    )
                    navController.navigate(Screen.computerDetail(savedComputer.id))
                } else {
                    errorText = localizedError
                }
            } finally {
                current.clear()
                if (prepared === current) prepared = null
                setupStage = null
                isBusy = false
            }
        }
    }

    val topButtonSize = 46.dp
    val isDarkTheme = isSystemInDarkTheme()
    val topButtonBackground = if (isDarkTheme) Color(0xFF303030) else Color.White
    val topButtonContentColor = if (isDarkTheme) Color.White else Color(0xFF0D0D0D)
    val settingsTabs = listOf(
        stringResource(R.string.settings_tab_platforms),
        stringResource(R.string.settings_tab_web_search),
        stringResource(R.string.settings_tab_mcp),
    )
    val topContentPadding =
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + topButtonSize + 24.dp
    val cardAccentColors = remember(computers.map { it.id }) {
        computerCardAccentColors(computers)
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { contentPadding ->
        Box(Modifier.fillMaxSize().padding(contentPadding)) {
            if (computers.isEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_gpt_terminal),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.computer_screen_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.computer_screen_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = topContentPadding,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(computers, key = { it.id }) { computer ->
                        ComputerCard(
                            computer = computer,
                            accentColor = cardAccentColors.getValue(computer.id),
                            onClick = { navController.navigate(Screen.computerDetail(computer.id)) },
                            onRefresh = { viewModel.refreshComputerFromList(computer.id) },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .floatingEdgeGradient(MaterialTheme.colorScheme.background, fromTop = true)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(12.dp),
            ) {
                TopCircleButton(
                    iconRes = R.drawable.ic_arrow_back,
                    contentDescription = stringResource(R.string.navigation_back),
                    modifier = Modifier.align(Alignment.CenterStart),
                    onClick = ::returnToChatHome,
                )
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    Row(
                        modifier = Modifier
                            .width(topButtonSize * 2)
                            .height(topButtonSize)
                            .shadow(3.dp, RoundedCornerShape(percent = 50), clip = false)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(topButtonBackground),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(topButtonSize)
                                .clip(CircleShape)
                                .clickable {
                                    showTabMenu = false
                                    errorText = null
                                    showAddCard = true
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_plus),
                                contentDescription = stringResource(R.string.action_add),
                                tint = topButtonContentColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(topButtonSize)
                                .clip(CircleShape)
                                .clickable { showTabMenu = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_dots_horizontal),
                                contentDescription = stringResource(R.string.action_more),
                                tint = topButtonContentColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    SettingsTabMenu(
                        expanded = showTabMenu,
                        tabs = settingsTabs,
                        currentTabIndex = -1,
                        onTabSelected = { index -> returnToSettings(tabIndex = index) },
                        onImportExport = onImportExport,
                        onOpenComputers = { showTabMenu = false },
                        onOpenSkills = { navController.navigate(Screen.SKILL_SCREEN) { launchSingleTop = true } },
                        isComputerSelected = true,
                        onDismiss = { showTabMenu = false },
                    )
                }
            }
        }
    }

    if (showAddCard) {
        ComputerAddCard(
            form = form,
            isBusy = isBusy,
            progressText = setupStage?.let { stage -> context.getString(stage.labelRes()) },
            progressDetailText = setupStage?.let { stage -> context.getString(stage.detailRes()) },
            errorText = errorText,
            onFormChange = { form = it; errorText = null },
            onSubmit = ::startHostKeyProbe,
            onCloudflareLogin = {
                cloudflareToken?.accessToken?.fill('\u0000')
                cloudflareToken?.refreshToken?.fill('\u0000')
                cloudflareToken = null
                cloudflareAccounts = emptyList()
                form = form.copy(cloudflareAuthorized = false, cloudflareIdentity = null, cloudflareAccountId = "", cloudflareAccountName = "", cloudflareScopes = emptySet())
                cloudflareOAuthFlow.let { flow ->
                    com.android.everytalk.data.computer.CloudflareOAuthLaunchCoordinator(context, flow)
                        .launch(form.id)
                        .onFailure { launchError -> errorText = launchError.message ?: "Cloudflare OAuth 配置错误" }
                }
            },
            onCloudflareAccountSelected = { id, name -> form = form.copy(cloudflareAccountId = id, cloudflareAccountName = name) },
            cloudflareAccounts = cloudflareAccounts,
            onDismiss = ::closeAddCard,
        )
    }

    ComputerHostKeyDialog(
        hostKey = hostKey,
        onConfirm = ::confirmHostKey,
        onDismiss = {
            hostKey = null
            prepared?.clear()
            prepared = null
        },
    )
}

/** 首次添加步骤与文案集中映射，后台只上报稳定的业务阶段。 */
internal fun ComputerSetupStage.labelRes(): Int = when (this) {
    ComputerSetupStage.READING_HOST_KEY -> R.string.computer_progress_reading_key
    ComputerSetupStage.AUTHENTICATING -> R.string.computer_progress_authenticating
    ComputerSetupStage.INSPECTING_VPS -> R.string.computer_progress_inspecting
    ComputerSetupStage.SECURING_CONNECTION -> R.string.computer_progress_securing_connection
    ComputerSetupStage.PREPARING_CONTAINER -> R.string.computer_progress_preparing_container
    ComputerSetupStage.PREPARING_DOCKER -> R.string.computer_progress_preparing_docker
    ComputerSetupStage.INSTALLING_HELPER -> R.string.computer_progress_installing_helper
    ComputerSetupStage.BUILDING_IMAGE -> R.string.computer_progress_building_image
    ComputerSetupStage.CONFIGURING_NETWORK -> R.string.computer_progress_configuring_network
    ComputerSetupStage.VERIFYING -> R.string.computer_progress_verifying
}

internal fun ComputerSetupStage.detailRes(): Int = when (this) {
    ComputerSetupStage.READING_HOST_KEY -> R.string.computer_progress_detail_host_key
    ComputerSetupStage.AUTHENTICATING -> R.string.computer_progress_detail_authenticating
    ComputerSetupStage.INSPECTING_VPS -> R.string.computer_progress_detail_inspecting
    ComputerSetupStage.SECURING_CONNECTION -> R.string.computer_progress_detail_securing_connection
    ComputerSetupStage.PREPARING_CONTAINER -> R.string.computer_progress_detail_preparing_container
    ComputerSetupStage.PREPARING_DOCKER -> R.string.computer_progress_detail_preparing_docker
    ComputerSetupStage.INSTALLING_HELPER -> R.string.computer_progress_detail_installing_helper
    ComputerSetupStage.BUILDING_IMAGE -> R.string.computer_progress_detail_building_image
    ComputerSetupStage.CONFIGURING_NETWORK -> R.string.computer_progress_detail_configuring_network
    ComputerSetupStage.VERIFYING -> R.string.computer_progress_detail_verifying
}

@Composable
internal fun TopCircleButton(
    iconRes: Int,
    contentDescription: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val isDarkTheme = isSystemInDarkTheme()
    val buttonBackground = if (isDarkTheme) Color(0xFF303030) else Color.White
    val contentColor = if (isDarkTheme) Color.White else Color(0xFF0D0D0D)
    Box(
        modifier = modifier
            .size(46.dp)
            .shadow(3.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(buttonBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = contentColor,
        )
    }
}
