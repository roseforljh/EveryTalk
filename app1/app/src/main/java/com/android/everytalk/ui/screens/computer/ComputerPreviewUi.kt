package com.android.everytalk.ui.screens.computer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.everytalk.R
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerPreview
import com.android.everytalk.data.computer.ComputerPreviewStatus
import com.android.everytalk.data.computer.ComputerPreviewVisibility
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.respondToComputerPublicPreview
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldColors
import java.text.DateFormat
import java.util.Date

/** Preview 创建表单的纯 UI 状态。端口与有效期在提交时统一校验。 */
internal data class ComputerPreviewFormState(
    val port: String = "3000",
    val protocol: String = "http",
    val expiresInMinutes: String = "60",
)

@Composable
internal fun ComputerPreviewCreateDialog(
    visibility: ComputerPreviewVisibility?,
    isBusy: Boolean,
    errorText: String?,
    onDismiss: () -> Unit,
    onCreate: (port: Int, protocol: String, expiresInSeconds: Long?) -> Unit,
) {
    if (visibility == null) return
    var form by remember(visibility) { mutableStateOf(ComputerPreviewFormState()) }
    var localError by remember(visibility) { mutableStateOf<String?>(null) }
    val publicPreview = visibility == ComputerPreviewVisibility.PUBLIC
    val invalidPortText = stringResource(R.string.computer_preview_invalid_port)
    val invalidExpiryText = stringResource(R.string.computer_preview_invalid_expiry)

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = appDialogContainerColor(),
        titleContentColor = appDialogContentColor(),
        textContentColor = appDialogContentColor(),
        title = {
            Text(
                stringResource(
                    if (publicPreview) R.string.computer_preview_public_create
                    else R.string.computer_preview_private_create,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (publicPreview) {
                    Text(
                        text = stringResource(R.string.computer_preview_public_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    value = form.port,
                    onValueChange = { value ->
                        if (value.all(Char::isDigit)) form = form.copy(port = value)
                        localError = null
                    },
                    label = { Text(stringResource(R.string.computer_preview_port)) },
                    singleLine = true,
                    enabled = !isBusy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppDialogTextFieldShape,
                    colors = appDialogTextFieldColors(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = form.protocol == "http",
                        onClick = { form = form.copy(protocol = "http") },
                        label = { Text("HTTP") },
                        enabled = !isBusy,
                        shape = AppDialogTextFieldShape,
                    )
                    FilterChip(
                        selected = form.protocol == "https",
                        onClick = { form = form.copy(protocol = "https") },
                        label = { Text("HTTPS") },
                        enabled = !isBusy,
                        shape = AppDialogTextFieldShape,
                    )
                }
                if (publicPreview) {
                    OutlinedTextField(
                        value = form.expiresInMinutes,
                        onValueChange = { value ->
                            if (value.all(Char::isDigit)) form = form.copy(expiresInMinutes = value)
                            localError = null
                        },
                        label = { Text(stringResource(R.string.computer_preview_expires_minutes)) },
                        supportingText = { Text(stringResource(R.string.computer_preview_expires_hint)) },
                        singleLine = true,
                        enabled = !isBusy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppDialogTextFieldShape,
                        colors = appDialogTextFieldColors(),
                    )
                }
                (localError ?: errorText)?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
                if (isBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.computer_action_working),
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isBusy,
                onClick = {
                    val port = form.port.toIntOrNull()
                    val minutes = form.expiresInMinutes.toLongOrNull()
                    when {
                        port == null || port !in 1..65_535 -> {
                            localError = invalidPortText
                        }
                        publicPreview && (minutes == null || minutes !in 1..10_080) -> {
                            localError = invalidExpiryText
                        }
                        else -> onCreate(
                            port,
                            form.protocol,
                            if (publicPreview) minutes!! * 60 else null,
                        )
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (publicPreview) R.string.computer_preview_confirm_public
                        else R.string.computer_preview_open,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun ComputerPreviewList(
    computer: Computer,
    previews: List<ComputerPreview>,
    onOpen: (String) -> Unit,
    onStop: (ComputerPreview) -> Unit,
) {
    if (previews.isEmpty()) {
        Text(
            text = stringResource(R.string.computer_preview_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        previews.forEach { preview ->
            val url = computerPreviewUrl(computer, preview)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(
                            if (preview.visibility == ComputerPreviewVisibility.PUBLIC) R.drawable.ic_globe
                            else R.drawable.ic_link,
                        ),
                        contentDescription = null,
                    )
                    Column(
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .weight(1f),
                    ) {
                        Text(
                            text = stringResource(
                                if (preview.visibility == ComputerPreviewVisibility.PUBLIC) {
                                    R.string.computer_preview_public_label
                                } else {
                                    R.string.computer_preview_private_label
                                },
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = url ?: stringResource(R.string.computer_preview_no_url),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = previewStatusLabel(preview.status),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (preview.status == ComputerPreviewStatus.ACTIVE) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (url != null && preview.status == ComputerPreviewStatus.ACTIVE) {
                        TextButton(onClick = { onOpen(url) }) {
                            Text(stringResource(R.string.computer_preview_view))
                        }
                    }
                    if (preview.status == ComputerPreviewStatus.ACTIVE) {
                        IconButton(onClick = { onStop(preview) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_stop),
                                contentDescription = stringResource(R.string.computer_preview_stop),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun previewStatusLabel(status: ComputerPreviewStatus): String = stringResource(
    when (status) {
        ComputerPreviewStatus.ACTIVE -> R.string.computer_preview_status_active
        ComputerPreviewStatus.STOPPED -> R.string.computer_preview_status_stopped
        ComputerPreviewStatus.REVOKED -> R.string.computer_preview_status_revoked
        ComputerPreviewStatus.EXPIRED -> R.string.computer_preview_status_expired
        ComputerPreviewStatus.ERROR -> R.string.computer_preview_status_error
    },
)

internal fun computerPreviewUrl(computer: Computer, preview: ComputerPreview): String? {
    val port = when (preview.visibility) {
        ComputerPreviewVisibility.PRIVATE -> preview.localPort
        ComputerPreviewVisibility.PUBLIC -> preview.publicPort
    } ?: return null
    val host = when (preview.visibility) {
        ComputerPreviewVisibility.PRIVATE -> "127.0.0.1"
        ComputerPreviewVisibility.PUBLIC -> if (':' in computer.host) "[${computer.host}]" else computer.host
    }
    return "${preview.protocol}://$host:$port"
}

/**
 * Private Preview 必须在 App 内打开，WebView 只允许 HTTP/HTTPS 网络地址。
 * 文件、Content URI 和跨域文件访问全部关闭，避免远端页面读取手机本地内容。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ComputerUrlPreviewDialog(url: String?, onDismiss: () -> Unit) {
    if (url == null) return
    var errorText by remember(url) { mutableStateOf<String?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var webView: WebView? by remember(url) { mutableStateOf(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.action_close),
                        )
                    }
                    Text(
                        text = url,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.computer_card_refresh),
                        )
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.allowFileAccessFromFileURLs = false
                                settings.allowUniversalAccessFromFileURLs = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val scheme = request?.url?.scheme?.lowercase()
                                        return scheme !in setOf("http", "https")
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        loading = true
                                        errorText = null
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loading = false
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        if (request?.isForMainFrame == true) {
                                            loading = false
                                            errorText = error?.description?.toString()
                                        }
                                    }
                                }
                                webView = this
                                loadUrl(url)
                            }
                        },
                    )
                    if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    errorText?.let { message ->
                        Surface(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
        }
    }
}

/** Tool Call 请求公开端口时，无论用户当前在哪个页面都显示这张确认卡。 */
@Composable
fun ComputerPublicPreviewConfirmationDialog(viewModel: AppViewModel) {
    val request by viewModel.pendingComputerPublicPreview.collectAsState()
    val computers by viewModel.computers.collectAsState()
    val pending = request ?: return
    val computer = computers.firstOrNull { it.id == pending.context.computerId }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    AlertDialog(
        onDismissRequest = { viewModel.respondToComputerPublicPreview(false) },
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = appDialogContainerColor(),
        titleContentColor = appDialogContentColor(),
        textContentColor = appDialogContentColor(),
        title = { Text(stringResource(R.string.computer_public_confirmation_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.computer_public_confirmation_warning),
                    color = MaterialTheme.colorScheme.error,
                )
                Text(stringResource(R.string.computer_public_confirmation_server, computer?.displayName.orEmpty()))
                Text(stringResource(R.string.computer_public_confirmation_port, pending.protocol.uppercase(), pending.port))
                pending.expiresInSeconds?.let { seconds ->
                    Text(
                        stringResource(
                            R.string.computer_public_confirmation_expires,
                            dateFormat.format(Date(System.currentTimeMillis() + seconds * 1000)),
                        ),
                    )
                }
                if (
                    pending.target == com.android.everytalk.data.computer.ComputerExecTarget.HOST ||
                    computer?.runMode == ComputerRunMode.DIRECT
                ) {
                    Text(stringResource(R.string.computer_public_confirmation_direct))
                }
                Text(
                    text = stringResource(R.string.computer_preview_firewall_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.respondToComputerPublicPreview(true) }) {
                Text(stringResource(R.string.computer_preview_confirm_public))
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.respondToComputerPublicPreview(false) }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
