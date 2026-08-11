package com.android.everytalk.ui.screens.computer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.android.everytalk.R
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.data.computer.ComputerStatus
import com.android.everytalk.navigation.Screen
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.addConfirmedComputer
import com.android.everytalk.statecontroller.probeComputerHostKey
import com.android.everytalk.statecontroller.provisionComputerContainer
import com.android.everytalk.statecontroller.refreshComputer
import com.android.everytalk.statecontroller.showSnackbar
import com.android.everytalk.ui.components.floatingEdgeGradient
import com.android.everytalk.ui.components.popup.AppFloatingCardPopup
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
    val density = LocalDensity.current
    val computers by viewModel.computers.collectAsState()
    val scope = rememberCoroutineScope()
    var showAddCard by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(ComputerAddFormState()) }
    var isBusy by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf<PreparedComputerAdd?>(null) }
    var hostKey by remember { mutableStateOf<com.android.everytalk.data.computer.HostKeyProbeResult?>(null) }
    val latestPrepared by rememberUpdatedState(prepared)

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
        progressText = null
        errorText = null
        form = ComputerAddFormState()
        showAddCard = false
    }

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
        progressText = context.getString(R.string.computer_progress_reading_key)
        isBusy = true
        scope.launch {
            try {
                hostKey = withContext(Dispatchers.IO) {
                    viewModel.probeComputerHostKey(current.request)
                }
            } catch (error: Throwable) {
                current.clear()
                if (prepared === current) prepared = null
                errorText = context.localizeUiMessage(
                    error.message ?: context.getString(R.string.unknown_error),
                )
            } finally {
                progressText = null
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
        progressText = context.getString(R.string.computer_progress_authenticating)
        errorText = null
        isBusy = true
        scope.launch {
            try {
                val added = withContext(Dispatchers.IO) {
                    viewModel.addConfirmedComputer(current.request, confirmed)
                }
                if (
                    added.runMode == ComputerRunMode.CONTAINER &&
                    added.status == ComputerStatus.CONFIGURATION_REQUIRED
                ) {
                    progressText = context.getString(R.string.computer_progress_provisioning)
                    withContext(Dispatchers.IO) {
                        viewModel.provisionComputerContainer(added.id, current.sudoPassword)
                    }
                }
                viewModel.showSnackbar(context.getString(R.string.computer_add_success))
                prepared = null
                progressText = null
                errorText = null
                form = ComputerAddFormState()
                showAddCard = false
            } catch (error: Throwable) {
                errorText = context.localizeUiMessage(
                    error.message ?: context.getString(R.string.unknown_error),
                )
            } finally {
                current.clear()
                if (prepared === current) prepared = null
                progressText = null
                isBusy = false
            }
        }
    }

    val topBarHeight = 72.dp
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
                        tint = MaterialTheme.colorScheme.primary,
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
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + topBarHeight,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(computers, key = { it.id }) { computer ->
                        ComputerCard(
                            computer = computer,
                            onClick = { navController.navigate(Screen.computerDetail(computer.id)) },
                            onRefresh = {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) { viewModel.refreshComputer(computer.id) }
                                    }.onFailure { error ->
                                        viewModel.showSnackbar(
                                            context.localizeUiMessage(
                                                error.message ?: context.getString(R.string.unknown_error),
                                            ),
                                        )
                                    }
                                }
                            },
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
                    iconRes = R.drawable.ic_plus,
                    contentDescription = stringResource(R.string.action_add),
                    modifier = Modifier.align(Alignment.CenterStart),
                    onClick = {
                        errorText = null
                        showAddCard = true
                    },
                )
                Text(
                    text = stringResource(R.string.computer_screen_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.Center),
                )
                TopCircleButton(
                    iconRes = R.drawable.ic_arrow_back,
                    contentDescription = stringResource(R.string.navigation_back),
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onClick = { navController.popBackStack() },
                )
            }

            val popupWidth = (LocalConfiguration.current.screenWidthDp.dp - 24.dp).coerceAtMost(380.dp)
            val popupTop = WindowInsets.statusBars.getTop(density) + with(density) { 64.dp.roundToPx() }
            AppFloatingCardPopup(
                visible = showAddCard,
                alignment = Alignment.TopStart,
                offset = IntOffset(with(density) { 12.dp.roundToPx() }, popupTop),
                onDismissRequest = { closeAddCard() },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
                modifier = Modifier.width(popupWidth),
            ) {
                ComputerAddCard(
                    form = form,
                    isBusy = isBusy,
                    progressText = progressText,
                    errorText = errorText,
                    onFormChange = { form = it; errorText = null },
                    onSubmit = ::startHostKeyProbe,
                    onDismiss = ::closeAddCard,
                )
            }
        }
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

@Composable
private fun TopCircleButton(
    iconRes: Int,
    contentDescription: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(46.dp)
            .shadow(3.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}
