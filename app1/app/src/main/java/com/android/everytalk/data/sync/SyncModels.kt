package com.android.everytalk.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 服务器合同允许同步的四类记录。图片会话不会进入这些请求。 */
internal enum class SyncEntity(val wireName: String) {
    SESSIONS("sessions"),
    MESSAGES("messages"),
    MEMORIES("memories"),
    USER_SETTINGS("user_settings"),
    ATTACHMENTS("attachments"),
}

/** push 的单条变更；所有业务字段都严格放在 payload 内。 */
@Serializable
internal data class PushChange(
    val entity: String,
    val id: String,
    val deleted: Boolean,
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class PushRequest(val changes: List<PushChange>)

@Serializable
internal data class SyncError(
    val code: String,
    val message: String,
)

@Serializable
internal data class PushResult(
    val index: Int,
    val entity: String? = null,
    val id: String? = null,
    val status: String,
    @SerialName("server_seq") val serverSeq: Long? = null,
    @SerialName("canonical_id") val canonicalId: String? = null,
    val error: SyncError? = null,
    val reason: String? = null,
)

@Serializable
internal data class PushResponse(val results: List<PushResult>)

@Serializable
internal data class SyncRecord(
    val entity: String,
    val id: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean,
    @SerialName("server_seq") val serverSeq: Long,
)

@Serializable
internal data class PullResponse(
    val changes: List<SyncRecord>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
    val epoch: Long,
    @SerialName("secrets_epoch") val secretsEpoch: Long = 0L,
)

/** push/pull 的网络错误，保留状态码和 Retry-After 供 Worker 决定是否重试。 */
internal class SyncHttpException(
    val statusCode: Int,
    val retryAfterSeconds: Long? = null,
    val serverCode: String? = null,
    message: String,
) : IllegalStateException(message)

/** 认证刷新被明确拒绝；只有这种错误才会把账号置为 SessionExpired。 */
internal class SyncRefreshRejectedException(message: String) : IllegalStateException(message)

/** 没有配置服务器地址时同步自动关闭，不把它当成网络故障重试。 */
internal class SyncNotConfiguredException : IllegalStateException("同步服务未配置")

internal data class SyncCursorState(val cursor: Long = 0L, val epoch: Long = 0L)

/** 只在远端事务或清空完成后通知页面重读；不传正文，合并连续分页通知。 */
internal object SyncLocalChanges {
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    fun notifyChanged() { revision.value += 1 }
}

/** 用于防止 push 返回后覆盖本地新修改。 */
internal data class PendingChangeSnapshot(
    val change: PushChange,
    val entity: SyncEntity,
    val localId: String,
    val version: Long,
)
