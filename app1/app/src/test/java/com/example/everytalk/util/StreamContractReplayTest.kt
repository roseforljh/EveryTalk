package com.example.everytalk.util

import com.example.everytalk.data.network.AppStreamEvent
import com.example.everytalk.util.messageprocessor.MessageProcessor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class StreamContractReplayTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "type"
    }

    @Test
    fun replay_sample_hello_ndjson() = runBlocking {
        val url = this@StreamContractReplayTest::class.java.classLoader
            .getResource("stream_contract/sample_hello.ndjson")
        assertNotNull("找不到回放快照资源 stream_contract/sample_hello.ndjson", url)

        val path = Paths.get(url!!.toURI())
        val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val mp = MessageProcessor()
        val messageId = "msg-sample-hello"
        mp.initialize(sessionId = "s-ndjson-replay", messageId = messageId)

        var lastText: String? = null

        for (line in lines) {
            val event = json.decodeFromString<AppStreamEvent>(line)
            when (event) {
                is AppStreamEvent.Content -> {
                    val res = mp.processStreamEvent(event, messageId)
                    assertTrue(
                        "Content 事件应返回 ContentUpdated",
                        res is com.example.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated
                    )
                    lastText =
                        (res as com.example.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated).content
                    // 覆盖式累计应至少包含当前帧文本
                    assertTrue(lastText!!.contains(event.text))
                }
                is AppStreamEvent.ContentFinal -> {
                    val res = mp.processStreamEvent(event, messageId)
                    assertTrue(res is com.example.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated)
                    lastText =
                        (res as com.example.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated).content
                }
                is AppStreamEvent.Reasoning -> {
                    mp.processStreamEvent(event, messageId)
                }
                is AppStreamEvent.StatusUpdate -> {
                    mp.processStreamEvent(event, messageId)
                }
                is AppStreamEvent.WebSearchResults -> {
                    mp.processStreamEvent(event, messageId)
                }
                is AppStreamEvent.Error -> {
                    mp.processStreamEvent(event, messageId)
                }
                is AppStreamEvent.Finish -> {
                    mp.processStreamEvent(event, messageId)
                }
                is AppStreamEvent.Text,
                is AppStreamEvent.OutputType,
                is AppStreamEvent.StreamEnd,
                is AppStreamEvent.ToolCall,
                is AppStreamEvent.ImageGeneration -> {
                    // 其余事件类型：保持幂等处理/忽略
                    mp.processStreamEvent(event, messageId)
                }
            }
        }

        val finalText = mp.getCurrentText()
        assertNotNull("最终文本不应为空", finalText)
        assertTrue("最终文本应包含 Hello, world!", finalText!!.contains("Hello, world!"))

        // 幂等性：再次喂相同最终帧不应改变内容
        val repeatRes = mp.processStreamEvent(
            AppStreamEvent.ContentFinal(
                text = "Hello, world!",
                output_type = "general",
                block_type = "text"
            ),
            messageId
        )
        val afterRepeat =
            (repeatRes as com.example.everytalk.util.messageprocessor.ProcessedEventResult.ContentUpdated).content
        assertEquals("重复最终帧不应导致内容变化", finalText, afterRepeat)
    }
}