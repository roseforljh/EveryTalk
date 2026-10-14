package com.android.everytalk.data.sync

import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.MemoryEntity
import com.android.everytalk.data.database.entities.MessageEntity
import com.android.everytalk.data.database.entities.UserSettingEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * 同步白名单的唯一出口。
 *
 * 这里只读取文字和必要元信息；token 用量、附件、图片、搜索结果、工具轨迹、API Key
 * 和 pending message 都不进入 JsonObject。后续新增字段必须先经过这层审核。
 */
internal object SyncPayloadMapper {
    fun session(session: ChatSessionEntity): PushChange = PushChange(
        entity = SyncEntity.SESSIONS.wireName,
        id = session.uuid,
        deleted = session.deleted,
        payload = buildJsonObject {
            put("clientModifiedAt", session.clientModifiedAt)
            put("title", session.title?.let(::JsonPrimitive) ?: JsonNull)
            put("isImageGeneration", session.isImageGeneration)
            put("creationTimestamp", session.creationTimestamp)
            put("lastModifiedTimestamp", session.lastModifiedTimestamp)
        },
    )

    fun message(message: MessageEntity, sessionUuid: String): PushChange = PushChange(
        entity = SyncEntity.MESSAGES.wireName,
        id = message.uuid,
        deleted = message.deleted,
        payload = if (message.deleted) JsonObject(emptyMap()) else buildJsonObject {
            put("sessionId", sessionUuid)
            put("text", message.text)
            put("sender", message.sender.name)
            message.name?.let { put("name", it) }
            put("timestamp", message.timestamp)
            put("isError", message.isError)
            put("outputType", message.outputType)
            message.modelName?.let { put("modelName", it) }
            message.providerName?.let { put("providerName", it) }
            message.reasoning?.let { put("reasoning", it) }
        },
    )

    fun memory(memory: MemoryEntity, sourceConversationUuid: String?, sourceMessageUuid: String?): PushChange =
        PushChange(
            entity = SyncEntity.MEMORIES.wireName,
            id = memory.uuid,
            deleted = memory.deleted,
            payload = buildJsonObject {
                put("clientModifiedAt", memory.clientModifiedAt)
                put("category", memory.category)
                put("content", memory.content)
                put("origin", memory.origin)
                sourceConversationUuid?.let { put("sourceConversationId", it) }
                sourceMessageUuid?.let { put("sourceMessageId", it) }
                put("createdAt", memory.createdAt)
                put("updatedAt", memory.updatedAt)
                put("enabled", memory.enabled)
            },
        )

    fun userSetting(setting: UserSettingEntity): PushChange = PushChange(
        entity = SyncEntity.USER_SETTINGS.wireName,
        id = setting.uuid,
        deleted = setting.deleted,
        payload = buildJsonObject {
            put("clientModifiedAt", setting.clientModifiedAt)
            put("kind", setting.kind)
            put("key", setting.key)
            put("value", settingValue(setting.value))
        },
    )

    /** 本地表为了简单保存 JSON 数组；服务器合同使用带字段名的 value 对象。 */
    fun settingValue(raw: String): JsonElement {
        val parsed = syncJson.parseToJsonElement(raw)
        val ids = when (parsed) {
            is JsonObject -> requireNotNull(parsed["conversationIds"]).jsonArray
            is JsonArray -> parsed
            else -> error("设置中缺少对话 UUID 列表")
        }
        // 不透传 value 对象的其他字段，避免旧导入数据夹带密钥、图片或附件。
        ids.forEach { require(java.util.UUID.fromString(it.jsonPrimitive.content).toString() == it.jsonPrimitive.content.lowercase()) }
        return buildJsonObject { put("conversationIds", ids) }
    }

    fun conversationIds(payload: JsonObject): List<String> =
        (settingValue(requireNotNull(payload["value"]).toString()) as JsonObject)
            .getValue("conversationIds").jsonArray.map { it.jsonPrimitive.content }

    fun string(payload: JsonObject, key: String): String? =
        payload[key]?.jsonPrimitive?.contentOrNull

    fun long(payload: JsonObject, key: String, default: Long = 0L): Long =
        payload[key]?.jsonPrimitive?.long ?: default

    fun boolean(payload: JsonObject, key: String, default: Boolean = false): Boolean =
        payload[key]?.jsonPrimitive?.booleanOrNull ?: default
}
