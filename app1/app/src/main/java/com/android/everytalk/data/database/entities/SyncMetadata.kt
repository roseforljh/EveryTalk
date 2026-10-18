package com.android.everytalk.data.database.entities

import java.util.UUID

/** LOCAL_ONLY 不参与上传；PENDING 等待明确开启同步；SYNCED 已由服务器确认。 */
object SyncStatus {
    const val LOCAL_ONLY = "LOCAL_ONLY"
    const val PENDING = "PENDING"
    const val SYNCED = "SYNCED"
}

/**
 * 旧 ID 仍供页面、附件和 Agent 使用，UUID 单独供同步使用。
 * 对相同表和 ID 生成相同 UUID，迁移与重新保存采用相同规则，避免旧消息每次保存产生新身份。
 * 服务端拉取的数据必须保留服务器提供的 UUID，不能用本地 ID 重新计算。
 */
fun localSyncUuid(table: String, id: String): String =
    UUID.nameUUIDFromBytes("EveryTalk:$table:$id".toByteArray(Charsets.UTF_8)).toString()

/** 最近打开记录只是历史的副本，不能上传成另一份对话；文字和图片历史都可同步。 */
fun isSyncableTextSession(id: String, isImageGeneration: Boolean): Boolean =
    id != "last_open_chat_v1" && id != "last_open_image_generation_v1"

object UserSettingKind {
    const val CONVERSATION_GROUPS = "conversation_groups"
    const val PINNED_ITEMS = "pinned_items"
    const val PINNED_KEY = "text"
}

/** 设置投影只读取文字会话的身份和修改时间，不读取正文、附件或 API 配置。 */
data class SyncSessionReference(
    val id: String,
    val uuid: String,
    @androidx.room.ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** 只比较同步白名单；补写 token 用量、附件、搜索结果或工具状态不会改变消息的同步内容。 */
fun MessageEntity.hasSameSyncContent(other: MessageEntity): Boolean =
    text == other.text && sender == other.sender && name == other.name && timestamp == other.timestamp &&
        isError == other.isError && outputType == other.outputType && modelName == other.modelName &&
        providerName == other.providerName && reasoning == other.reasoning
