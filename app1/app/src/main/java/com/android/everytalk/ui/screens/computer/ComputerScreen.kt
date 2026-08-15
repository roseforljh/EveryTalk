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
import com.android.everytalk.data.computer.ComputerDiagnostics
import com.android.everytalk.data.computer.ComputerFailureStage
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.data.computer.ComputerSetupStage
import com.android.everytalk.data.computer.ComputerStatus
import com.android.everytalk.navigation.Screen
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.addConfirmedComputer
import com.android.everytalk.statecontroller.probeComputerHostKey
import com.android.everytalk.statecontroller.provisionComputerContainer
import com.android.everytalk.statecontroller.refreshComputerFromList
import com.android.everytalk.statecontroller.showSnackbar
import com.android.everytalk.ui.components.floatingEdgeGradient
import com.android.everytalk.ui.screens.settings.SettingsTabMenu
import com.android.everytalk.util.locale.localizeUiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ComputerScreen(
    viewModel: AppViewModel,
    navController: NavController,
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
    val latestPrepared by rememberUpdatedState(prepared)
    ComputerSecureWindowEffect(showAddCard)

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    DisposableEffect(Unit) {
        onDispose { latestPrepared?.clear() }
    }

    fun closeAddCard() {
        if (isBusy) return
        prepared?.clear()
        prepared = null
        hostKey = null
        setupStage = null
        errorText = null
        form = ComputerAddFormState()
        showAddCard = false
    }

    /** 返回已有设置页，并把三点菜单选择的目标交给该页面处理。 */
    fun returnToSettings(tabIndex: Int? = null, openImportExport: Boolean = false) {
        val existingSettingsEntry = runCatching {
            navController.getBackStackEntry(Screen.SETTINGS_SCREEN)
        }.getOrNull()
        val targetEntry = existingSettingsEntry ?: run {
            navController.navigate(Screen.SETTINGS_SCREEN) { launchSingleTop = true }
            navController.currentBackStackEntry
        }
        tabIndex?.let {
            targetEntry?.savedStateHandle?.set(Screen.SETTINGS_TAB_REQUEST_KEY, it)
        }
        if (openImportExport) {
            targetEntry?.savedStateHandle?.set(Screen.SETTINGS_IMPORT_EXPORT_REQUEST_KEY, true)
        }
        showTabMenu = false
        if (existingSettingsEntry != null) {
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
                        onImportExport = { returnToSettings(openImportExport = true) },
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
