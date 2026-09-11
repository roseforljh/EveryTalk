package com.android.everytalk.data.computer

import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * D1/KV/R2 API 协议层。Account/资源标识逐段校验，Key 和分页参数独立编码。
 * 本层不代表用户授权：Provider 必须先检查绑定、scope 和写操作确认，再调用这些方法。
 */
class CloudflareResourceClient(
    httpClient: HttpClient,
    tokenProvider: suspend () -> String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    baseUrl: String = "https://api.cloudflare.com/client/v4",
) : AutoCloseable {
    private val transport = CloudflareHttpTransport(httpClient, tokenProvider, baseUrl)

    suspend fun listD1(accountId: String, page: Int = 1, perPage: Int = 100): JsonObject =
        getJson("/accounts/${id(accountId)}/d1/database", pageQuery(page, perPage))

    /** /raw 也是 POST 查询接口；schema SQL 固定在可信代码中，不接收模型拼接的 SQL。 */
    suspend fun d1Schema(accountId: String, databaseId: String): JsonObject {
        val sql = "SELECT type, name, tbl_name, sql FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name LIMIT 501"
        return jsonRequest(
            HttpMethod.Post,
            "/accounts/${id(accountId)}/d1/database/${id(databaseId)}/raw",
            buildJsonObject { put("sql", sql); put("params", JsonArray(emptyList())) }.toString(),
        )
    }

    suspend fun queryD1(
        accountId: String,
        databaseId: String,
        sql: String,
        params: List<String> = emptyList(),
    ): JsonObject {
        require(sql.isNotBlank() && sql.toByteArray().size <= 64 * 1024 && params.size <= 100) { "D1 查询超过限制" }
        val body = buildJsonObject {
            put("sql", sql)
            put("params", JsonArray(params.map(::JsonPrimitive)))
        }.toString()
        val result = jsonRequest(HttpMethod.Post, "/accounts/${id(accountId)}/d1/database/${id(databaseId)}/query", body)
        // D1 顶层 success=true 时，批处理中的单条查询仍可能失败。
        val queries = result["result"] as? JsonArray
            ?: throw CloudflareApiException("RESPONSE_INVALID", "D1 查询结果格式无效")
        if (queries.isEmpty() || queries.any { ((it as? JsonObject)?.get("success") as? JsonPrimitive)?.booleanOrNull != true }) {
            throw CloudflareApiException("D1_QUERY_FAILED", "D1 查询未成功")
        }
        return result
    }

    /** migration 与普通查询复用逐条结果校验，不能只信任外层 success。 */
    suspend fun runD1Migration(accountId: String, databaseId: String, sql: String): JsonObject =
        queryD1(accountId, databaseId, sql)

    suspend fun listKvNamespaces(accountId: String, page: Int = 1, perPage: Int = 100): JsonObject =
        getJson("/accounts/${id(accountId)}/storage/kv/namespaces", pageQuery(page, perPage))

    suspend fun listKvKeys(accountId: String, namespaceId: String, cursor: String? = null, limit: Int = 100): JsonObject {
        require(limit in 10..1000) { "KV 分页大小无效" }
        return getJson("${namespace(accountId, namespaceId)}/keys", cursorQuery(cursor) + ("limit" to limit.toString()))
    }

    suspend fun getKv(accountId: String, namespaceId: String, key: String): String =
        transport.request(HttpMethod.Get, "${namespace(accountId, namespaceId)}/values/${keySegment(key)}")

    suspend fun putKv(accountId: String, namespaceId: String, key: String, value: String) {
        // App 主动采用比云端更小的上限；UTF-8 字节数决定请求内存，不能只检查字符数。
        require(value.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "KV Value 过大" }
        jsonRequest(HttpMethod.Put, "${namespace(accountId, namespaceId)}/values/${keySegment(key)}", value, ContentType.Text.Plain)
    }

    suspend fun deleteKv(accountId: String, namespaceId: String, key: String) {
        jsonRequest(HttpMethod.Delete, "${namespace(accountId, namespaceId)}/values/${keySegment(key)}")
    }

    suspend fun listR2Buckets(accountId: String): JsonObject = getJson("/accounts/${id(accountId)}/r2/buckets")

    /** Durable Objects、Queues、Cron 先提供状态读取；写操作由显式方法单独暴露。 */
    suspend fun listDurableObjectNamespaces(accountId: String): JsonObject =
        getJson("/accounts/${id(accountId)}/workers/durable_objects/namespaces")

    suspend fun listDurableObjectInstances(accountId: String, namespaceId: String, cursor: String? = null, limit: Int = 100): JsonObject {
        require(limit in 10..1000) { "Durable Objects 分页大小无效" }
        return getJson(
            "/accounts/${id(accountId)}/workers/durable_objects/namespaces/${id(namespaceId)}/objects",
            cursorQuery(cursor) + ("limit" to limit.toString()),
        )
    }

    suspend fun listQueues(accountId: String): JsonObject =
        getJson("/accounts/${id(accountId)}/queues")

    suspend fun getQueue(accountId: String, queueId: String): JsonObject =
        getJson("/accounts/${id(accountId)}/queues/${id(queueId)}")

    suspend fun queueMetrics(accountId: String, queueId: String): JsonObject =
        getJson("/accounts/${id(accountId)}/queues/${id(queueId)}/metrics")

    /** Peek 不会租赁或删除消息，但响应中的消息正文仍作为不可信外部输入处理。 */
    suspend fun peekQueue(accountId: String, queueId: String, batchSize: Int = 10): JsonObject {
        require(batchSize in 1..100) { "Queue Peek 数量无效" }
        return jsonRequest(
            HttpMethod.Post,
            "/accounts/${id(accountId)}/queues/${id(queueId)}/messages/peek",
            buildJsonObject { put("batch_size", batchSize) }.toString(),
        )
    }

    suspend fun listWorkerSchedules(accountId: String, workerName: String): JsonObject {
        require(workerName.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Worker 名称无效" }
        return getJson("/accounts/${id(accountId)}/workers/scripts/$workerName/schedules")
    }

    /** PUT 接受 [{cron: ...}]，禁止把模型给出的任意 JSON 直接发给 API。 */
    suspend fun updateWorkerSchedules(accountId: String, workerName: String, schedules: List<String>): JsonObject {
        val body = CloudflareCronSchedules.encode(schedules)
        return jsonRequest(HttpMethod.Put, "/accounts/${id(accountId)}/workers/scripts/${id(workerName)}/schedules", body.toString())
    }

    suspend fun createQueue(accountId: String, name: String): JsonObject {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "Queue 名称无效" }
        return jsonRequest(HttpMethod.Post, "/accounts/${id(accountId)}/queues", buildJsonObject { put("queue_name", name) }.toString())
    }

    suspend fun deleteQueue(accountId: String, queueId: String) {
        jsonRequest(HttpMethod.Delete, "/accounts/${id(accountId)}/queues/${id(queueId)}")
    }

    suspend fun listR2Objects(accountId: String, bucket: String, cursor: String? = null, perPage: Int = 100): JsonObject {
        require(perPage in 1..1000) { "R2 分页大小无效" }
        require(bucket.matches(Regex("[a-z0-9][a-z0-9-]{1,62}[a-z0-9]"))) { "Bucket 名称无效" }
        return getJson("/accounts/${id(accountId)}/r2/buckets/$bucket/objects", cursorQuery(cursor) + ("per_page" to perPage.toString()))
    }

    suspend fun getR2Metadata(accountId: String, bucket: String, key: String): JsonObject {
        val headers = transport.objectHeaders("/accounts/${id(accountId)}/r2/buckets/${bucketName(bucket)}/objects/${objectKeySegment(key)}")
        // 只返回白名单元数据；不读取对象正文，也不透传任意响应头。
        return buildJsonObject {
            put("ok", true)
            headers["Content-Length"]?.let { put("content_length", it) }
            headers["Content-Type"]?.let { put("content_type", it.take(200)) }
            headers["ETag"]?.let { put("etag", it.take(200)) }
            headers["Last-Modified"]?.let { put("last_modified", it.take(100)) }
        }
    }

    suspend fun uploadR2Object(accountId: String, bucket: String, key: String, value: ByteArray) {
        require(value.size <= 32 * 1024 * 1024) { "R2 对象过大" }
        decodeCloudflareEnvelope(transport.requestBytes(HttpMethod.Put, "/accounts/${id(accountId)}/r2/buckets/${bucketName(bucket)}/objects/${objectKeySegment(key)}", value, ContentType.Application.OctetStream), json)
    }

    suspend fun deleteR2Object(accountId: String, bucket: String, key: String) {
        jsonRequest(HttpMethod.Delete, "/accounts/${id(accountId)}/r2/buckets/${bucketName(bucket)}/objects/${objectKeySegment(key)}")
    }

    private fun bucketName(bucket: String): String {
        require(bucket.matches(Regex("[a-z0-9][a-z0-9-]{1,62}[a-z0-9]"))) { "Bucket 名称无效" }
        return bucket
    }

    private suspend fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonObject =
        decodeCloudflareEnvelope(transport.request(HttpMethod.Get, path, query = query), json)

    private suspend fun jsonRequest(
        method: HttpMethod,
        path: String,
        body: String? = null,
        contentType: ContentType = ContentType.Application.Json,
    ): JsonObject = decodeCloudflareEnvelope(transport.request(method, path, body, contentType), json)

    private fun namespace(accountId: String, namespaceId: String) =
        "/accounts/${id(accountId)}/storage/kv/namespaces/${id(namespaceId)}"

    private fun id(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Cloudflare 资源标识无效" }
        return value
    }

    private fun keySegment(key: String): String {
        require(key.isNotEmpty() && key.toByteArray(Charsets.UTF_8).size <= 512 &&
            key.none { it.isWhitespace() || it.isISOControl() } && key != "." && key != "..") { "Cloudflare Key 无效" }
        return URLEncoder.encode(key, "UTF-8").replace("+", "%20")
    }

    private fun objectKeySegment(key: String): String {
        require(key.isNotEmpty() && key.toByteArray(Charsets.UTF_8).size <= 512 &&
            key.none { it.isWhitespace() || it.isISOControl() } &&
            key.split('/').none { it == "." || it == ".." }) { "R2 Object Key 无效" }
        // R2 官方对象接口要求保留 Key 中的斜线，其他保留字符仍需编码。
        return URLEncoder.encode(key, "UTF-8").replace("+", "%20").replace("%2F", "/", ignoreCase = true)
    }

    private fun pageQuery(page: Int, perPage: Int): Map<String, String> {
        require(page in 1..10000 && perPage in 1..1000) { "分页参数无效" }
        return mapOf("page" to page.toString(), "per_page" to perPage.toString())
    }

    private fun cursorQuery(cursor: String?): Map<String, String> {
        require(cursor == null || (cursor.length <= 4096 && cursor.none(Char::isISOControl))) { "分页游标无效" }
        return cursor?.let { mapOf("cursor" to it) }.orEmpty()
    }

    override fun close() = transport.close()
}
