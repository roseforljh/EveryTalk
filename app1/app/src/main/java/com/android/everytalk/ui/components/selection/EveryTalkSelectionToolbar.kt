package com.android.everytalk.ui.components.selection

import com.android.everytalk.ui.components.dialog.AppOutlinedButton
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.data.DataClass.MessageQuoteReference
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogSubtextColor

internal const val ASK_EVERYTALK_MENU_TITLE = "询问Everytalk"

/**
 * 询问 EveryTalk 弹窗的三个目标路由选项。
 */
enum class EveryTalkAskOption {
    NEW_CONVERSATION,
    CURRENT_CONVERSATION,
    FORK,
}

private object AskEveryTalkMenuKey

/**
 * 给子树里 AI 回复的文本选择菜单加上「询问Everytalk」，并固定排在「复制」之后。
 * Compose 1.11 默认走新版文本菜单（不再调用 LocalTextToolbar）：按钮挂在菜单数据上；
 * 系统浮动工具栏按组件序号排序，厂商的「朗读」「AI 写作」等会插在后面，
 * 所以包一层菜单提供者把顺序重排为 复制 → 询问Everytalk → 其余。
 * 选中文本没有公开读取接口：从系统「复制」项的回调里取，见 readSelectedText。
 */
@Composable
fun AskEveryTalkSelectionArea(
    enabled: Boolean,
    onAsk: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val context = LocalContext.current
    val latestOnAsk by rememberUpdatedState(onAsk)
    val copyItem = remember { arrayOfNulls<TextContextMenuItem>(1) }
    val menuModifier = Modifier
        .filterTextContextMenuComponents { component ->
            if (component is TextContextMenuItem && component.key == TextContextMenuKeys.CopyKey) {
                copyItem[0] = component
            }
            true
        }
        .appendTextContextMenuComponents {
            item(key = AskEveryTalkMenuKey, label = ASK_EVERYTALK_MENU_TITLE) {
                val copy = copyItem[0]
                val text = copy?.let(::readSelectedText) ?: copy?.let { copyViaClipboard(context, it, this) }.orEmpty()
                close()
                if (text.isNotBlank()) latestOnAsk(text)
            }
        }
    // 平台默认的菜单实现是内部类，且只在 SelectionContainer 里兜底创建，无法包装；
    // 这里自己开系统浮动工具栏，按 复制 → 询问Everytalk → 其余 排序，保证按钮在第一排。
    val view = LocalView.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val toolbar = remember(view) { OrderedFloatingToolbarProvider(view) { coordinates[0]?.takeIf { it.isAttached } } }
    CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
        Box(modifier = menuModifier.onGloballyPositioned { coordinates[0] = it }) { content() }
    }
}

/**
 * 系统「复制」项的回调里持有 SelectionManager，直接调用它取选中文本，不经过剪贴板
 * （经过剪贴板会被输入法记进「来自剪贴板」）。取不到时返回 null，由调用方退回剪贴板方式。
 */
private fun readSelectedText(copy: TextContextMenuItem): String? = runCatching {
    val seen = HashSet<Any>()
    fun visit(target: Any, depth: Int): String? {
        if (depth > 3 || !seen.add(target)) return null
        target.javaClass.declaredMethods.firstOrNull {
            it.parameterCount == 0 && it.returnType == AnnotatedString::class.java
        }?.let { method ->
            method.isAccessible = true
            return (method.invoke(target) as? AnnotatedString)?.text
        }
        for (field in target.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
            field.isAccessible = true
            val value = field.get(target) ?: continue
            visit(value, depth + 1)?.let { return it }
        }
        return null
    }
    visit(copy.onClick, 0)?.trim()?.takeIf { it.isNotEmpty() }
}.getOrNull()

/** 兜底：借复制取文本后把剪贴板原样还原。 */
private fun copyViaClipboard(context: Context, copy: TextContextMenuItem, session: TextContextMenuSession): String {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return ""
    val previous = runCatching { clipboard.primaryClip }.getOrNull()
    copy.onClick(session)
    val selected = runCatching {
        clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull().orEmpty().trim()
    runCatching {
        when {
            previous != null -> clipboard.setPrimaryClip(previous)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> clipboard.clearPrimaryClip()
        }
    }
    return selected
}

private class OrderedFloatingToolbarProvider(
    private val view: View,
    private val coordinates: () -> LayoutCoordinates?,
) : TextContextMenuProvider {
    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) = coroutineScope {
        var mode: ActionMode? = null
        val closed = CompletableDeferred<Unit>()
        val session = object : TextContextMenuSession {
            override fun close() {
                mode?.finish()
            }
        }
        fun ordered(): List<TextContextMenuItem> = dataProvider.data().components
            .filterIsInstance<TextContextMenuItem>()
            .sortedBy {
                when (it.key) {
                    TextContextMenuKeys.CopyKey -> 0
                    AskEveryTalkMenuKey -> 1
                    else -> 2
                }
            }
        fun populate(menu: Menu) {
            menu.clear()
            ordered().forEachIndexed { index, item ->
                val id = when (item.key) {
                    TextContextMenuKeys.CopyKey -> android.R.id.copy
                    TextContextMenuKeys.SelectAllKey -> android.R.id.selectAll
                    TextContextMenuKeys.CutKey -> android.R.id.cut
                    TextContextMenuKeys.PasteKey -> android.R.id.paste
                    else -> Menu.FIRST + index
                }
                val menuItem = menu.add(Menu.NONE, id, index, item.label)
                menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menuItem.setOnMenuItemClickListener {
                    item.onClick(session)
                    true
                }
            }
        }
        val callback = object : ActionMode.Callback2() {
            override fun onCreateActionMode(m: ActionMode, menu: Menu): Boolean = true.also { populate(menu) }
            override fun onPrepareActionMode(m: ActionMode, menu: Menu): Boolean = true.also { populate(menu) }
            override fun onActionItemClicked(m: ActionMode, item: MenuItem): Boolean = false
            override fun onDestroyActionMode(m: ActionMode) {
                closed.complete(Unit)
            }
            override fun onGetContentRect(m: ActionMode, v: View, outRect: AndroidRect) {
                val layout = coordinates() ?: return
                val bounds = dataProvider.contentBounds(layout).translate(layout.localToRoot(Offset.Zero))
                outRect.set(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
            }
        }
        mode = view.startActionMode(callback, ActionMode.TYPE_FLOATING) ?: return@coroutineScope
        // 选区变化（如全选）会改变菜单项和位置，跟着刷新。
        val watcher = launch {
            snapshotFlow {
                val layout = coordinates()
                ordered().map { it.key } to layout?.let { dataProvider.contentBounds(it) }
            }.collect {
                mode?.invalidate()
                mode?.invalidateContentRect()
            }
        }
        try {
            closed.await()
        } finally {
            watcher.cancel()
            mode?.finish()
        }
    }
}

/**
 * 点击「询问Everytalk」后的选择弹窗：上方是引用预览，下方三个等宽入口
 * （新会话 / 本会话 / Fork），点一下即执行。
 */
@Composable
fun AskEveryTalkOptionDialog(
    quote: MessageQuoteReference,
    onSelectOption: (EveryTalkAskOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val contentColor = appDialogContentColor()
    val subtextColor = appDialogSubtextColor()
    val accent = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppDialogShape,
        containerColor = appDialogContainerColor(),
        titleContentColor = contentColor,
        textContentColor = contentColor,
        title = {
            Text(
                text = "询问 EveryTalk",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(accent),
                    )
                    Text(
                        text = quote.quotedText.replace(Regex("\\s+"), " ").trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = subtextColor,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AskOptionTile(Icons.Outlined.AddComment, "新会话", "ask-everytalk-option-new") {
                        onSelectOption(EveryTalkAskOption.NEW_CONVERSATION)
                    }
                    AskOptionTile(Icons.Outlined.ChatBubbleOutline, "本会话", "ask-everytalk-option-current") {
                        onSelectOption(EveryTalkAskOption.CURRENT_CONVERSATION)
                    }
                    AskOptionTile(Icons.AutoMirrored.Outlined.CallSplit, "Fork", "ask-everytalk-option-fork") {
                        onSelectOption(EveryTalkAskOption.FORK)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            AppOutlinedButton(onClick = onDismiss) {
                Text(text = "取消", color = subtextColor)
            }
        },
    )
}

@Composable
private fun RowScope.AskOptionTile(
    icon: ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        modifier = Modifier
            .weight(1f)
            .testTag(testTag),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = appDialogContentColor(),
            )
        }
    }
}

/**
 * 水平可滚动的引用文本标签栏。
 * 用于主会话输入框、右侧并发/fork 抽屉输入框以及已发送用户气泡上方。
 * 单个标签限制最大宽度（默认 176.dp），超出部分显示 `...` 省略号；支持单击跳转到来源消息并高亮 3 秒。
 */
@Composable
fun QuoteReferenceTagsRow(
    quotes: List<MessageQuoteReference>,
    onQuoteClick: (MessageQuoteReference) -> Unit,
    onRemoveQuote: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (quotes.isEmpty()) return
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            .testTag("quote-reference-tags-row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        quotes.forEach { quote ->
            QuoteReferenceTagPill(
                quote = quote,
                onClick = { onQuoteClick(quote) },
                onRemove = onRemoveQuote?.let { remove -> { remove(quote.id) } },
            )
        }
    }
}

@Composable
fun QuoteReferenceTagPill(
    quote: MessageQuoteReference,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cleanSnippet = remember(quote.quotedText) {
        quote.quotedText.replace(Regex("\\s+"), " ").trim()
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        border = BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
        ),
        modifier = modifier
            .widthIn(max = 180.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("quote-reference-pill-${quote.id}"),
    ) {
        Row(
            modifier = Modifier.padding(
                start = 8.dp,
                end = if (onRemove != null) 6.dp else 10.dp,
                top = 5.dp,
                bottom = 5.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.FormatQuote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = cleanSnippet,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onRemove != null) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onRemove)
                        .testTag("quote-reference-remove-${quote.id}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "移除引用",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}
