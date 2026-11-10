package com.android.everytalk.data.database.daos

import android.util.Log
import com.android.everytalk.BuildConfig
import com.android.everytalk.data.database.entities.MessageEntity
import com.android.everytalk.data.database.entities.hasSameSyncContent

/**
 * 已落库正式消息的同步字段只能追加，不能原地改写或复活删除记录。
 * debug 立即暴露违规调用；release 只跳过当前记录，后面的合法新增消息仍然保存。
 * 日志只记录身份，不输出聊天正文、思考过程或凭据。
 */
/**
 * 页面保存前会去掉正文和思考过程首尾的空白，而云端拉下来的消息保留原文（思考过程常以换行结尾）。
 * 只差首尾空白不算改写：沿用已落库的原文，否则每次保存这类会话都会被判成改写已同步消息。
 */
internal fun MessageEntity.keepingStoredPadding(stored: MessageEntity): MessageEntity = copy(
    text = if (text.trim() == stored.text.trim()) stored.text else text,
    reasoning = if (reasoning.orEmpty().trim() == stored.reasoning.orEmpty().trim()) stored.reasoning else reasoning,
)

internal fun acceptFormalMessageUpdate(
    stored: MessageEntity,
    incoming: MessageEntity,
    debug: Boolean = BuildConfig.DEBUG,
    log: (String) -> Unit = { Log.w("ChatDao", it) },
): Boolean {
    if (!stored.deleted && stored.sessionId == incoming.sessionId && stored.hasSameSyncContent(incoming)) return true
    // 只列出不一致的字段名和长度，定位是哪条链路带来了旧快照；不输出内容。
    val differing = listOfNotNull(
        "deleted".takeIf { stored.deleted }, "session".takeIf { stored.sessionId != incoming.sessionId },
        "text(${stored.text.length}->${incoming.text.length})".takeIf { stored.text != incoming.text },
        "reasoning(${stored.reasoning?.length}->${incoming.reasoning?.length})".takeIf { stored.reasoning != incoming.reasoning },
        "sender".takeIf { stored.sender != incoming.sender }, "name".takeIf { stored.name != incoming.name },
        "timestamp".takeIf { stored.timestamp != incoming.timestamp }, "isError".takeIf { stored.isError != incoming.isError },
        "outputType".takeIf { stored.outputType != incoming.outputType }, "modelName".takeIf { stored.modelName != incoming.modelName },
        "providerName".takeIf { stored.providerName != incoming.providerName },
    )
    val reason = "拒绝改写正式消息或恢复已删除消息：uuid=${stored.uuid} 字段=$differing"
    if (debug) throw IllegalStateException(reason)
    log(reason)
    return false
}
