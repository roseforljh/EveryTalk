package com.android.everytalk.data.sync

import kotlinx.serialization.json.*
import java.io.IOException
import java.time.Instant

/** 合同假服务器：两份设备数据库共用；保留序号、逻辑键 canonical id 和消息追加语义。 */
internal class FakeSyncApi : SyncApi {
    val rows = linkedMapOf<Pair<String, String>, SyncRecord>()
    var seq = 0L
    var epoch = 0L
    var pushes = 0
    var pulls = 0
    var failPushNumber: Int? = null
    var beforePushReply: (suspend () -> Unit)? = null
    var pageSize = 500
    private val now = System.currentTimeMillis()

    fun clearCloud() { rows.clear(); epoch++ }

    override suspend fun push(changes: List<PushChange>): PushResponse {
        pushes++
        val results = changes.mapIndexed { index, change ->
            val logical = if (change.entity == "user_settings") rows.values.find {
                it.entity == change.entity && it.payload["kind"] == change.payload["kind"] && it.payload["key"] == change.payload["key"]
            } else null
            val id = logical?.id ?: change.id
            val old = rows[change.entity to id]
            val session = change.payload["sessionId"]?.jsonPrimitive?.content?.let { rows["sessions" to it] }
            val rejection = when {
                change.entity == "user_settings" && change.payload["kind"]?.jsonPrimitive?.content !in setOf(
                    "api_configs", "selected_models", "web_search_configs", "voice_configs", "voice_llm_platforms",
                    "voice_custom_models", "mcp_servers", "skills", "skill_variables", "computers", "computer_workspaces",
                    "conversation_settings", "memory_switches", "group_members", "pinned_items", "crypto_keys",
                ) -> "INVALID_USER_SETTING_KIND"
                change.entity == "messages" && session?.deleted == true -> "session_deleted"
                else -> null
            }
            if (rejection != null) return@mapIndexed PushResult(index, change.entity, change.id, "rejected", error = SyncError(rejection, "测试拒绝"))
            val payload = if (change.deleted && change.entity == "messages") JsonObject(emptyMap())
                else if (change.entity == "messages") change.payload else JsonObject(change.payload +
                    ("clientModifiedAt" to JsonPrimitive(minOf(change.payload.getValue("clientModifiedAt").jsonPrimitive.long, now))))
            val status = when {
                old != null && old.deleted && !change.deleted -> "ignored"
                old != null && change.entity == "messages" && !change.deleted -> "ignored"
                old != null && old.payload == payload && old.deleted == change.deleted -> "unchanged"
                old != null && change.entity != "messages" && old.payload.getValue("clientModifiedAt").jsonPrimitive.long > payload.getValue("clientModifiedAt").jsonPrimitive.long -> "ignored"
                else -> "accepted"
            }
            if (status == "accepted") {
                rows[change.entity to id] = SyncRecord(change.entity, id, payload,
                    Instant.ofEpochMilli(now).toString(), Instant.ofEpochMilli(now).toString(), change.deleted, ++seq)
                if (change.entity == "sessions" && change.deleted) {
                    rows.values.filter { it.entity == "messages" && it.payload["sessionId"]?.jsonPrimitive?.content == id }
                        .forEach { message -> rows["messages" to message.id] = message.copy(payload = JsonObject(emptyMap()), deleted = true, serverSeq = ++seq) }
                }
            }
            PushResult(index, change.entity, change.id, status, rows.getValue(change.entity to id).serverSeq,
                canonicalId = id.takeIf { it != change.id })
        }
        // 故意在服务器已写入后模拟断线：设备重试必须幂等且不能跳过其他设备的数据。
        if (failPushNumber == pushes) throw IOException("上传已到服务器但响应丢失")
        beforePushReply?.also { beforePushReply = null }?.invoke()
        return PushResponse(results)
    }

    override suspend fun pull(since: Long, limit: Int): PullResponse {
        pulls++
        val remaining = rows.values.filter { it.serverSeq > since }.sortedBy { it.serverSeq }
        val page = remaining.take(minOf(limit, pageSize))
        return PullResponse(page, page.lastOrNull()?.serverSeq ?: since, remaining.size > page.size, epoch)
    }

    override suspend fun deleteAll() {
        clearCloud()
    }
}
