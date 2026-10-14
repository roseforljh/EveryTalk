package com.android.everytalk.data.sync

import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 直接读取已提交的合同示例生成假服务器输入/响应，避免另一份手写 JSON 与合同漂移。
 * 这里只提取示例中明确的标量和 conversationIds，不给应用增加 YAML 解析依赖。
 */
class SyncContractTest {
    private val contract get() = File(System.getProperty("everytalk.project.root"), "docs/sync-api.yaml").readText()
    private fun scalar(block: String, key: String): String? = Regex("(?m)^\\s+(?:- )?$key: ([^\\r\\n]+)$")
        .find(block)?.groupValues?.get(1)?.trim()?.trim('\'')
    private fun payload(block: String): JsonObject = buildJsonObject {
        val strings = listOf("title", "sessionId", "text", "sender", "outputType", "kind", "key")
        val numbers = listOf("clientModifiedAt", "timestamp", "creationTimestamp", "lastModifiedTimestamp")
        strings.forEach { scalar(block, it)?.let { value -> put(it, value) } }
        numbers.forEach { scalar(block, it)?.let { value -> put(it, value.toLong()) } }
        scalar(block, "isImageGeneration")?.let { put("isImageGeneration", it.toBooleanStrict()) }
        if (block.contains("conversationIds:")) {
            val id = Regex("conversationIds:\\s+ - ([0-9a-f-]+)").find(block)?.groupValues?.get(1)
                ?: Regex("conversationIds:\\s+- ([0-9a-f-]+)").find(block)!!.groupValues[1]
            put("value", buildJsonObject { put("conversationIds", JsonArray(listOf(JsonPrimitive(id)))) })
        }
    }
    private fun blocks(example: String, marker: String): List<String> {
        // 示例使用普通 '- '，锚定行首避免匹配 description 中的解释文字。
        val offsets = Regex("(?m)^ +\\- $marker:.*").findAll(example).map { it.range.first }.toList()
        return offsets.mapIndexed { index, offset -> example.substring(offset, offsets.getOrNull(index + 1) ?: example.length) }
    }
    private fun pushExample(): List<PushChange> {
        val section = contract.substringAfter("  /sync/push:")
        val example = section.substringAfter("            example:").substringBefore("      responses:")
        return blocks(example, "entity").map { block -> PushChange(
            scalar(block, "entity")!!, scalar(block, "id")!!, scalar(block, "deleted")!!.toBooleanStrict(), payload(block)) }
    }
    private fun resultExample(): PushResponse {
        val example = contract.substringAfter("                results:").substringBefore("        '401':")
        return PushResponse(blocks(example, "index").map { block -> PushResult(
            scalar(block, "index")!!.toInt(), scalar(block, "entity"), scalar(block, "id"), scalar(block, "status")!!,
            scalar(block, "server_seq")?.toLong(), scalar(block, "canonical_id"),
            scalar(block, "code")?.let { SyncError(it, scalar(block, "message")!!) }) })
    }
    private fun pullExample(): PullResponse {
        val example = contract.substringAfter("  /sync/pull:").substringAfter("              example:").substringBefore("        '401':")
        return PullResponse(blocks(example.substringBefore("                next_cursor:"), "entity").map { block -> SyncRecord(
            scalar(block, "entity")!!, scalar(block, "id")!!, payload(block), scalar(block, "created_at"),
            scalar(block, "updated_at"), scalar(block, "deleted")!!.toBooleanStrict(), scalar(block, "server_seq")!!.toLong()) },
            scalar(example, "next_cursor")!!.toLong(), scalar(example, "has_more")!!.toBooleanStrict(), scalar(example, "epoch")!!.toLong())
    }

    @Test fun `合同push示例假服务器逐条处理拒绝不阻塞成功记录`() = runBlocking {
        val input = pushExample(); assertEquals(4, input.size)
        val response = FakeSyncApi().push(input)
        assertEquals(listOf("accepted", "accepted", "accepted", "rejected"), response.results.map { it.status })
        assertEquals("INVALID_USER_SETTING_KIND", response.results.last().error!!.code)
        assertNull(response.results.last().serverSeq)
        val json = syncJson.encodeToString(PushRequest.serializer(), PushRequest(input))
        assertEquals(input, syncJson.decodeFromString<PushRequest>(json).changes)
    }

    @Test fun `合同响应示例解析canonical墓碑序号游标与epoch`() {
        val push = resultExample(); assertEquals(4, push.results.size)
        val decoded = syncJson.decodeFromString<PushResponse>(syncJson.encodeToString(PushResponse.serializer(), push))
        assertEquals("04fd8fa5-4c91-4c44-888d-8d2f2d6f3303", decoded.results[2].canonicalId)
        assertNull(decoded.results[3].serverSeq)
        val pull = pullExample(); assertEquals(3, pull.changes.size)
        assertEquals(listOf(41L, 42L, 43L), pull.changes.map { it.serverSeq })
        assertTrue(pull.changes[1].deleted); assertTrue(pull.changes[1].payload.isEmpty())
        assertEquals(43L, pull.nextCursor); assertTrue(pull.hasMore); assertEquals(0L, pull.epoch)
        assertEquals(push.results[2].canonicalId, pull.changes[2].id)
        assertEquals(pull, syncJson.decodeFromString<PullResponse>(syncJson.encodeToString(PullResponse.serializer(), pull)))
        assertEquals(listOf(pull.changes[0].id), SyncPayloadMapper.conversationIds(pull.changes[2].payload))
    }
}
