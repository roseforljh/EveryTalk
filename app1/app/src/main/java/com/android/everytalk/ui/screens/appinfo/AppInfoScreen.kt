package com.android.everytalk.ui.screens.appinfo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.IntRect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.android.everytalk.ui.theme.AccentColor
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.android.everytalk.R
import com.android.everytalk.ui.components.popup.AppFloatingCardPopup
import com.android.everytalk.ui.components.floatingEdgeGradient
import com.android.everytalk.ui.screens.MainScreen.AboutDialog
import com.android.everytalk.util.locale.AppLanguage
import com.android.everytalk.util.locale.AppLanguageController
import com.android.everytalk.util.theme.AppTheme
import com.android.everytalk.util.theme.AppThemeController

private const val PROJECT_URL = "https://github.com/roseforljh/KunTalkwithAi"

private data class PrivacySection(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
)

private val privacySections = listOf(
    PrivacySection(
        titleRes = R.string.privacy_section_1_title,
        bodyRes = R.string.privacy_section_1_body,
    ),
    PrivacySection(
        titleRes = R.string.privacy_section_2_title,
        bodyRes = R.string.privacy_section_2_body,
    ),
    PrivacySection(
        titleRes = R.string.privacy_section_3_title,
        bodyRes = R.string.privacy_section_3_body,
    ),
    PrivacySection(
        titleRes = R.string.privacy_section_4_title,
        bodyRes = R.string.privacy_section_4_body,
    ),
    PrivacySection(
        titleRes = R.string.privacy_section_5_title,
        bodyRes = R.string.privacy_section_5_body,
    ),
    PrivacySection(
        titleRes = R.string.privacy_section_6_title,
        bodyRes = R.string.privacy_section_6_body,
    ),
    PrivacySection(
        titleRes = R.string.privacy_section_7_title,
        bodyRes = R.string.privacy_section_7_body,
    ),
    PrivacySection(
        titleRes = R.string.privacy_section_8_title,
        bodyRes = R.string.privacy_section_8_body,
    ),
    PrivacySection(
        titleRes = R.string.privacy_account_title,
        bodyRes = R.string.privacy_account_body,
    ),
)

@Composable
fun AppInfoScreen(
    onBack: () -> Unit,
    onOpenDataManagement: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenAccountManagement: () -> Unit,
    onOpenSubscription: () -> Unit,
    onOpenMemoryManagement: () -> Unit = {},
    subscriptionLocked: Boolean = false,
    accentColor: AccentColor = AccentColor.Default,
    onSelectAccent: (AccentColor) -> Unit = {},
    accountAvatarUrl: String? = null,
    accountName: String? = null,
    onEditAvatar: () -> Unit = onOpenAccountManagement,
    avatarEditingEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val currentLanguage = AppLanguageController.currentLanguage()
    var currentTheme by remember { mutableStateOf(AppThemeController.currentTheme(context)) }
    BackHandler(onBack = onBack)

    ImmersiveInfoPage(
        title = "",
        onBack = onBack,
        modifier = modifier.testTag("app_info_screen"),
        backgroundColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            // u101.f → gn8.c 默认读取 pcm.g；普通 Dark 下为纯黑。
            Color.Black
        } else {
            Color(0xFFFCFCFC)
        },
    ) {
        item(key = "app_identity") {
            AppIdentityHeader(
                avatarUrl = accountAvatarUrl,
                accountName = accountName,
                onEditAvatar = onEditAvatar,
                editingEnabled = avatarEditingEnabled,
            )
        }
        // ChatGPT 是分多个独立卡片，不是一个大卡片：
        //   1) 账户信息（邮箱/电话/年龄）
        //   2) 外观（外观 / 强调色）
        //   3) 自定义
        //   4) 常规（通知/语音/安全/账户安全）
        // 每张卡片圆角 16dp、行高 60dp、分隔线从标题列开始缩进 21dp。
        item(key = "app_info_entries") {
            SettingsGroupCard {
                AppInfoEntry(
                    iconRes = R.drawable.gpt_user_icon,
                    title = stringResource(R.string.account_title),
                    onClick = onOpenAccountManagement,
                )
                SettingsRowDivider()
                AppInfoEntry(
                    iconRes = R.drawable.gpt_sparkle,
                    title = stringResource(R.string.subscription_title),
                    onClick = onOpenSubscription,
                    locked = subscriptionLocked,
                )
            }
        }
        item(key = "appearance_group") {
            SettingsGroupCard {
                // 主题：右侧显示当前值 + 向下的折叠箭头（不是 chevron>），点开悬浮卡片。
                ThemeEntry(
                    currentTheme = currentTheme,
                    onThemeSelected = { theme ->
                        currentTheme = theme
                        AppThemeController.setTheme(context, theme)
                    },
                )
                SettingsRowDivider()
                AccentColorEntry(
                    selected = accentColor,
                    onSelect = onSelectAccent,
                )
            }
        }
        item(key = "general_group") {
            SettingsGroupCard {
                LanguageEntry(
                    currentLanguage = currentLanguage,
                    onLanguageSelected = AppLanguageController::setLanguage,
                )
                SettingsRowDivider()
                AppInfoEntry(
                    iconRes = R.drawable.gpt_folder,
                    title = stringResource(R.string.app_info_data_management_title),
                    onClick = onOpenDataManagement,
                )
                SettingsRowDivider()
                AppInfoEntry(
                    iconRes = R.drawable.gpt_memory,
                    title = stringResource(R.string.memory_title),
                    onClick = onOpenMemoryManagement,
                )
                SettingsRowDivider()
                AppInfoEntry(
                    iconRes = R.drawable.gpt_privacy,
                    title = stringResource(R.string.app_info_privacy_title),
                    onClick = onOpenPrivacyPolicy,
                )
                SettingsRowDivider()
                AppInfoEntry(
                    iconRes = R.drawable.gpt_info,
                    title = stringResource(R.string.app_info_about_title),
                    onClick = { showAboutDialog = true },
                )
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
        title = stringResource(R.string.privacy_policy_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        item(key = "privacy_intro") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.privacy_policy_heading),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(
                        R.string.privacy_effective_date,
                        stringResource(R.string.privacy_effective_date_value),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.privacy_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        privacySections.forEach { section ->
            item(key = section.titleRes) {
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
                        text = stringResource(R.string.privacy_project_link),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painter = painterResource(R.drawable.gpt_chevron_right),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * 顶部身份区：ChatGPT 这里放的是用户头像（圆形，带编辑角标），不是 app logo。
 * 未登录时退回 app 图标；名称优先显示账号名。
 */
@Composable
private fun AppIdentityHeader(
    avatarUrl: String? = null,
    accountName: String? = null,
    onEditAvatar: () -> Unit,
    editingEnabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(88.dp).clickable(
                enabled = editingEnabled,
                role = Role.Button,
                onClickLabel = stringResource(R.string.account_change_avatar),
                onClick = onEditAvatar,
            ),
        ) {
            com.android.everytalk.ui.screens.account.AccountAvatar(
                avatarUrl = avatarUrl,
                name = accountName ?: stringResource(R.string.app_name),
                description = stringResource(R.string.account_change_avatar),
                modifier = Modifier.fillMaxSize(),
            )
            Surface(
                modifier = Modifier.size(28.dp).align(Alignment.BottomEnd),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_gpt_edit),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Text(
            text = accountName ?: stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun AppInfoEntry(
    @DrawableRes iconRes: Int,
    title: String,
    onClick: () -> Unit,
    locked: Boolean = false,
    expanded: Boolean = false,
) {
    // 普通入口只显示图标、标题和箭头；当前值仅由主题/强调色行展示。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 50.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (locked) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = stringResource(R.string.feature_locked),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        AnimatedChevron(
            expanded = expanded,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

/** 1.2026.279：qgr0 使用 pcm.a，普通暗色为 #303030；qwl0 使用 1dp qcm.a 描边。 */
@Composable
internal fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(27.dp),
        color = if (dark) Color(0xFF303030) else Color.White,
        border = BorderStroke(
            1.dp,
            if (dark) Color(0x26FFFFFF) else Color(0x1A000000),
        ),
    ) {
        Column(content = content)
    }
}

/** 行间分隔线：从左边距 16dp 开始（对齐 lgr0.d），右端留白同卡片内边距。 */
@Composable
internal fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            Color(0x1AFFFFFF)
        } else {
            Color(0x0D000000)
        },
    )
}

/** 弹层复用统一动画容器：alpha图层包住阴影留白，不能用卡片大小的矩形图层裁剪。 */
@Composable
private fun AnchoredPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val maxHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } - 32.dp
    val positionProvider = remember(density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = appInfoPopupOffset(
                anchorBounds, windowSize, popupContentSize,
                with(density) { 6.dp.roundToPx() },
                with(density) { 8.dp.roundToPx() },
            )
        }
    }
    AppFloatingCardPopup(
        visible = expanded,
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(226.dp).heightIn(max = maxHeight),
        properties = PopupProperties(focusable = true),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            content = content,
        )
    }
}

/** 优先贴行下方；放不下则贴行上方，两侧都不足时夹在窗口内，内容可滚动。 */
internal fun appInfoPopupOffset(
    anchor: IntRect,
    window: IntSize,
    popup: IntSize,
    gap: Int,
    margin: Int,
): IntOffset {
    val maxX = (window.width - popup.width - margin).coerceAtLeast(margin)
    val maxY = (window.height - popup.height - margin).coerceAtLeast(margin)
    val below = anchor.bottom + gap
    val above = anchor.top - gap - popup.height
    val y = when {
        below <= maxY -> below
        above >= margin -> above
        else -> below.coerceIn(margin, maxY)
    }
    return IntOffset((anchor.right - popup.width).coerceIn(margin, maxX), y)
}

/** 行尾 chevron：展开时顺时针转 90°（朝下），收起转回。 */
@Composable
private fun AnimatedChevron(expanded: Boolean, tint: Color) {
    val angle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "chevron-rotate",
    )
    Icon(
        painter = painterResource(R.drawable.gpt_chevron_right),
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(20.dp)
            .rotate(angle),
    )
}

/**
 * 强调色行：右侧「色点 + 名称 + ⌄」，点开在行右下方弹出悬浮卡片。
 *
 * 逆向自 ChatGPT：弹窗内边距 io6.M(mod, 16f, 8f) 即水平 16dp、垂直 8dp，行高 54dp。
 */
@Composable
private fun AccentColorEntry(
    selected: AccentColor,
    onSelect: (AccentColor) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .heightIn(min = 50.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.gpt_paint_palette),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.account_accent_color),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(selected.color)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = selected.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            AnimatedChevron(
                expanded = expanded,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnchoredPopup(
            expanded = expanded,
            onDismiss = { expanded = false },
        ) {
            AccentColor.pickerEntries.forEach { accent ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(accent); expanded = false }
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(accent.color)
                    )
                    Spacer(Modifier.width(11.dp))
                    Text(
                        text = accent.displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (accent == selected) {
                        Icon(
                            painter = painterResource(R.drawable.gpt_check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeEntry(
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .heightIn(min = 50.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.gpt_theme),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.app_info_theme_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(currentTheme.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            AnimatedChevron(
                expanded = expanded,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnchoredPopup(
            expanded = expanded,
            onDismiss = { expanded = false },
        ) {
            AppTheme.entries.forEach { theme ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onThemeSelected(theme); expanded = false }
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(theme.labelRes),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (theme == currentTheme) {
                        Icon(
                            painter = painterResource(R.drawable.gpt_check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

private val AppLanguage.labelRes: Int
    get() = when (this) {
        AppLanguage.SYSTEM -> R.string.app_language_system
        AppLanguage.SIMPLIFIED_CHINESE -> R.string.app_language_simplified_chinese
        AppLanguage.ENGLISH -> R.string.app_language_english
    }

private val AppTheme.labelRes: Int
    get() = when (this) {
        AppTheme.SYSTEM -> R.string.app_theme_system
        AppTheme.LIGHT -> R.string.app_theme_light
        AppTheme.DARK -> R.string.app_theme_dark
    }

@Composable
internal fun LanguageEntry(
    currentLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        AppInfoEntry(
            iconRes = R.drawable.gpt_language,
            title = stringResource(R.string.app_info_language_title),
            onClick = { expanded = !expanded },
            expanded = expanded,
        )
        AnchoredPopup(expanded = expanded, onDismiss = { expanded = false }) {
            AppLanguage.entries.forEach { language ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = language == currentLanguage,
                            role = Role.RadioButton,
                            onClick = {
                                expanded = false
                                onLanguageSelected(language)
                            },
                        )
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(language.labelRes),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (language == currentLanguage) {
                        Icon(
                            painter = painterResource(R.drawable.gpt_check),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
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
                text = stringResource(section.titleRes),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(section.bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ImmersiveInfoPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    opaqueTopBar: Boolean = false,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    bottomBar: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
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
                start = 16.dp,
                top = topChromeHeight + 12.dp,
                end = 16.dp,
                bottom = if (bottomBar == null) bottomChromeHeight + 20.dp else navigationBarInset + 96.dp,
            ),
            // 分组间距 24dp，对齐 ChatGPT 的 SettingsListSectionSpacer。
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
        if (bottomBar != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .zIndex(3f),
            ) {
                bottomBar()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(topChromeHeight)
                .then(
                    if (opaqueTopBar) Modifier.background(backgroundColor)
                    else Modifier.floatingEdgeGradient(backgroundColor, fromTop = true),
                )
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
                        contentDescription = stringResource(R.string.navigation_back),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (title.isNotBlank()) {
                Spacer(Modifier.width(14.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
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
