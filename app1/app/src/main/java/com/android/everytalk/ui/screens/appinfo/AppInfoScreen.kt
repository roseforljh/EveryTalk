package com.android.everytalk.ui.screens.appinfo

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.android.everytalk.BuildConfig
import com.android.everytalk.R
import com.android.everytalk.ui.components.floatingEdgeGradient
import com.android.everytalk.ui.screens.MainScreen.AboutDialog

private const val PROJECT_URL = "https://github.com/roseforljh/KunTalkwithAi"
private const val PRIVACY_EFFECTIVE_DATE = "2026年8月9日"

private data class PrivacySection(
    val title: String,
    val body: String,
)

private val privacySections = listOf(
    PrivacySection(
        title = "1. 本地保存的信息",
        body = "聊天记录、模型服务配置、API 密钥、分组、置顶状态及其他应用设置会保存在你的设备本地。EveryTalk 已关闭 Android 系统备份，不会通过系统备份迁移这些数据。",
    ),
    PrivacySection(
        title = "2. 网络请求与第三方服务",
        body = "当你主动使用聊天、图像生成、语音识别、联网搜索或 MCP 功能时，应用会把完成该次请求所必需的文本、系统提示词、附件、图像、音频或搜索词发送给你选择或应用配置的服务。相关服务将依据各自的隐私政策处理数据。请勿向不受信任的服务提交敏感信息。",
    ),
    PrivacySection(
        title = "3. 权限用途",
        body = "网络权限用于连接模型及相关服务；麦克风权限仅用于你主动启动的语音输入；相机权限仅用于你主动拍摄并添加图片；蓝牙连接权限用于兼容已配对的音频设备。拒绝可选权限不会影响与该权限无关的功能。",
    ),
    PrivacySection(
        title = "4. 信息共享",
        body = "EveryTalk 不接入广告 SDK，也不出售个人信息。为执行你主动发起的功能，必要数据会传输给对应的 AI、语音识别、搜索或 MCP 服务，这类传输属于完成请求所需的处理。",
    ),
    PrivacySection(
        title = "5. 保存期限与删除",
        body = "本地数据会保留到你在应用内清除记录、删除相应配置或卸载应用。你主动导出或保存到系统存储中的文件由 Android 文件管理机制管理，不会随聊天记录一同删除。",
    ),
    PrivacySection(
        title = "6. 数据安全",
        body = "应用禁止明文网络流量，并将数据库和配置保存在应用私有目录。网络服务的安全性同时取决于你所选择的服务商、接口地址和设备本身的安全状态。请妥善保管 API 密钥。",
    ),
    PrivacySection(
        title = "7. AI 内容安全与举报",
        body = "应用会对高风险生成请求执行本地拦截，并向支持的模型服务传递安全约束。你主动举报 AI 内容时，应用会记录举报类别、补充说明、相关回复摘要、模型与服务商名称及应用版本，不会附带 API 密钥或整段会话。网络失败时举报会暂存在应用私有目录并重试；成功提交后会清除本地举报正文与说明，仅保留去重回执。",
    ),
    PrivacySection(
        title = "8. 政策更新与联系",
        body = "本政策可能随功能或合规要求更新，更新后的版本会在本页面展示。对本政策有疑问时，可通过 EveryTalk 开源项目页面联系维护者。",
    ),
)

@Composable
fun AppInfoScreen(
    onBack: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    ImmersiveInfoPage(
        title = "应用信息",
        onBack = onBack,
        modifier = modifier,
    ) {
        item(key = "app_identity") {
            AppIdentityHeader()
        }
        item(key = "app_info_section_title") {
            Text(
                text = "信息与支持",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
        item(key = "app_info_entries") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.82f),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                ),
            ) {
                Column {
                    AppInfoEntry(
                        iconRes = R.drawable.gpt_privacy,
                        title = "隐私政策",
                        description = "在应用内查看数据处理说明",
                        onClick = onOpenPrivacyPolicy,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 80.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                    AppInfoEntry(
                        iconRes = R.drawable.ic_info,
                        title = "关于 EveryTalk",
                        description = "版本 ${BuildConfig.VERSION_NAME}",
                        onClick = { showAboutDialog = true },
                    )
                }
            }
        }
    }

    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false },
        )
    }
}

@Composable
fun PrivacyPolicyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    BackHandler(onBack = onBack)

    ImmersiveInfoPage(
        title = "隐私政策",
        onBack = onBack,
        modifier = modifier,
    ) {
        item(key = "privacy_intro") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "EveryTalk 隐私政策",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "生效日期：$PRIVACY_EFFECTIVE_DATE",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "EveryTalk 是一款 AI 客户端。本政策说明应用在你使用聊天、图像、语音、联网搜索和 MCP 功能时如何处理信息。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        privacySections.forEach { section ->
            item(key = section.title) {
                PrivacySectionCard(section)
            }
        }
        item(key = "privacy_contact") {
            Surface(
                onClick = { uriHandler.openUri(PROJECT_URL) },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_link),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = "EveryTalk 开源项目与联系方式",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_gpt_chevron_right),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIdentityHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.size(76.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_robot_head),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        Text(
            text = "EveryTalk",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "隐私、版本与应用信息",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppInfoEntry(
    @DrawableRes iconRes: Int,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_gpt_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PrivacySectionCard(section: PrivacySection) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.74f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = section.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImmersiveInfoPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topChromeHeight = statusBarInset + 68.dp
    val bottomChromeHeight = navigationBarInset + 48.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = topChromeHeight + 12.dp,
                end = 20.dp,
                bottom = bottomChromeHeight + 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(topChromeHeight)
                .floatingEdgeGradient(backgroundColor, fromTop = true)
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
            Surface(
                onClick = onBack,
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
                ),
                shadowElevation = 3.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "返回",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bottomChromeHeight)
                .floatingEdgeGradient(backgroundColor, fromTop = false)
                .align(Alignment.BottomCenter)
                .zIndex(1f),
        )
    }
}
