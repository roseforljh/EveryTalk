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
internal fun acceptFormalMessageUpdate(
    stored: MessageEntity,
    incoming: MessageEntity,
    debug: Boolean = BuildConfig.DEBUG,
    log: (String) -> Unit = { Log.w("ChatDao", it) },
): Boolean {
    if (!stored.deleted && stored.sessionId == incoming.sessionId && stored.hasSameSyncContent(incoming)) return true
    val reason = "拒绝改写正式消息或恢复已删除消息：uuid=${stored.uuid}"
    if (debug) throw IllegalStateException(reason)
    log(reason)
    return false
}
