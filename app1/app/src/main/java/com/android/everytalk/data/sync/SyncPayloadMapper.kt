package com.android.everytalk.data.sync

import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.MemoryEntity
import com.android.everytalk.data.database.entities.MessageEntity
import com.android.everytalk.data.database.entities.UserSettingEntity
import com.android.everytalk.data.database.entities.SyncAttachmentEntity
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.MessageContentPart
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
 * 所有同步字段都必须从这里进入网络层。消息正文保持不可变；统计字段只有消息定稿后
 * 才会作为同一条消息的“补充合并”上传，避免流式过程中触发合同的 IMMUTABLE_FIELD。
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
            if (!message.isIncomplete) {
                // 已发送的 Skill 标签是正文结构；只传稳定引用，不传草稿、设备路径或技能文件。
                if (message.contentParts.any { it is MessageContentPart.SkillReference }) {
                    put("bodySegments", JsonArray(message.contentParts.map { part -> buildJsonObject {
                        when (part) {
                            is MessageContentPart.Text -> { put("type", "text"); put("text", part.text) }
                            is MessageContentPart.SkillReference -> {
                                put("type", "skill"); put("skillId", part.reference.skillId)
                                put("displayName", part.reference.displayName); put("sourceType", part.reference.sourceType.name)
                                put("contentHash", part.reference.contentHash)
                            }
                        }
                    } }))
                }
                // 工具调用前后的白话和思考也是用户可见文字。只传文字片段，不传命令、参数、工具结果。
                var contentOffset = 0
                var reasoningOffset = 0
                val segments = message.executionTrace.mapNotNull { event ->
                    val type = when (event) {
                        is ExecutionTraceEvent.Content -> "content"
                        is ExecutionTraceEvent.Reasoning -> "reasoning"
                        else -> return@mapNotNull null
                    }
                    val text = when (event) {
                        is ExecutionTraceEvent.Content -> event.text
                        is ExecutionTraceEvent.Reasoning -> event.text
                        else -> return@mapNotNull null
                    }
                    // 显示片段常常再次包含整段思考，重复发送会白白撞上服务端 64 KiB 上限。
                    // 只在原文中逐字找到时使用 UTF-16 位置引用；独有的工具前后文字仍完整发送。
                    val source = if (type == "reasoning") message.reasoning.orEmpty() else message.text
                    val searchFrom = if (type == "reasoning") reasoningOffset else contentOffset
                    val start = if (text.isNotEmpty()) source.indexOf(text, searchFrom) else -1
                    buildJsonObject {
                        put("type", type)
                        if (start >= 0) {
                            put("startUtf16", start); put("lengthUtf16", text.length)
                            if (type == "reasoning") reasoningOffset = start + text.length else contentOffset = start + text.length
                        } else put("text", text)
                    }
                }
                if (segments.isNotEmpty()) put("displaySegments", JsonArray(segments))
                message.tokenUsage?.let { usage ->
                    put("tokenUsage", buildJsonObject {
                        usage.inputTokens?.let { put("inputTokens", it) }
                        usage.outputTokens?.let { put("outputTokens", it) }
                        usage.reasoningTokens?.let { put("reasoningTokens", it) }
                        usage.cachedInputTokens?.let { put("cachedInputTokens", it) }
                        usage.cacheWriteTokens?.let { put("cacheWriteTokens", it) }
                        usage.totalTokens?.let { put("totalTokens", it) }
                        put("isFinal", usage.isFinal)
                        put("source", usage.source.name)
                        usage.requestOrdinal?.let { put("requestOrdinal", it) }
                    })
                }
                message.executionFinishedAt?.let { put("executionFinishedAt", it) }
                if (message.enabledToolIds.isNotEmpty()) put("enabledToolIds", JsonArray(message.enabledToolIds.map(::JsonPrimitive)))
                message.contextUsageSnapshot?.let { snapshot ->
                    put("contextUsageSnapshot", buildJsonObject {
                        put("messageId", message.uuid)
                        snapshot.configId?.let { put("configId", it) }
                        put("systemPromptTokens", snapshot.systemPromptTokens)
                        put("conversationTextTokens", snapshot.conversationTextTokens)
                        put("mediaTokens", snapshot.mediaTokens)
                        put("toolSchemaTokens", snapshot.toolSchemaTokens)
                        put("protocolOverheadTokens", snapshot.protocolOverheadTokens)
                        put("reservedOutputTokens", snapshot.reservedOutputTokens)
                        put("contextWindowTokens", snapshot.contextWindowTokens)
                        put("inputCalibrationTokens", snapshot.inputCalibrationTokens)
                        snapshot.measuredInputTokens?.let { put("measuredInputTokens", it) }
                        snapshot.measuredOutputTokens?.let { put("measuredOutputTokens", it) }
                        snapshot.measuredTotalTokens?.let { put("measuredTotalTokens", it) }
                        snapshot.measuredUsageSource?.let { put("measuredUsageSource", it.name) }
                        snapshot.activeContextTokensOverride?.let { put("activeContextTokensOverride", it) }
                        snapshot.agentRunInputTokens?.let { put("agentRunInputTokens", it) }
                        snapshot.agentRunOutputTokens?.let { put("agentRunOutputTokens", it) }
                        snapshot.agentRunTotalTokens?.let { put("agentRunTotalTokens", it) }
                        snapshot.agentRequestCount?.let { put("agentRequestCount", it) }
                        snapshot.conversationLifetimeTokens?.let { put("conversationLifetimeTokens", it) }
                    })
                }
            }
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

    fun attachment(attachment: SyncAttachmentEntity): PushChange = PushChange(
        entity = SyncEntity.ATTACHMENTS.wireName,
        id = attachment.id,
        deleted = attachment.deleted,
        // 附件墓碑也必须携带父级和修改时刻，服务器据此校验归属并淘汰旧版本。
        payload = buildJsonObject {
            put("clientModifiedAt", attachment.updatedAt)
            put("sessionId", attachment.sessionId)
            put("messageId", attachment.messageId)
            put("name", attachment.name)
            put("mime", attachment.mime)
            put("size", attachment.size)
            put("sha256", attachment.sha256)
            put("fileId", attachment.fileId?.let(::JsonPrimitive) ?: JsonNull)
            put("kind", attachment.kind)
            put("position", attachment.position)
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
            put("value", settingValue(setting.value, setting.kind))
        },
    )

    /** 将旧版数组设置转换成合同对象；新版配置逐条保留 value 的白名单字段。 */
    fun settingValue(raw: String, kind: String? = null): JsonElement {
        val parsed = syncJson.parseToJsonElement(raw)
        if (parsed is JsonObject) {
            // 旧版分组/置顶曾把所有附加字段塞进同一个 JSON；迁移时只保留会话 UUID，
            // 防止 API Key、附件和搜索结果沿着兼容路径进入同步。
            if (kind == null || kind == "conversation_groups" || kind == "pinned_items") {
                return buildJsonObject { parsed["conversationIds"]?.let { put("conversationIds", it) } }
            }
            return parsed
        }
        val ids = when (parsed) {
            is JsonArray -> parsed
            else -> error("设置中缺少对话 UUID 列表")
        }
        // 不透传 value 对象的其他字段，避免旧导入数据夹带密钥、图片或附件。
        ids.forEach { require(java.util.UUID.fromString(it.jsonPrimitive.content).toString() == it.jsonPrimitive.content.lowercase()) }
        return when (kind) {
            "pinned_items" -> buildJsonObject { }
            else -> buildJsonObject { put("conversationIds", ids) }
        }
    }

    fun conversationIds(payload: JsonObject): List<String> {
        val value = requireNotNull(payload["value"])
        return when {
            value is JsonObject && value["conversationId"] != null -> listOf(value.getValue("conversationId").jsonPrimitive.content)
            value is JsonObject && value["conversationIds"] != null -> value.getValue("conversationIds").jsonArray.map { it.jsonPrimitive.content }
            else -> emptyList()
        }
    }

    fun string(payload: JsonObject, key: String): String? =
        payload[key]?.jsonPrimitive?.contentOrNull

    fun long(payload: JsonObject, key: String, default: Long = 0L): Long =
        payload[key]?.jsonPrimitive?.long ?: default

    fun boolean(payload: JsonObject, key: String, default: Boolean = false): Boolean =
        payload[key]?.jsonPrimitive?.booleanOrNull ?: default
}
