package com.example.everytalk.ui

import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.ui.components.MarkdownPart
import com.example.everytalk.util.messageprocessor.MessageProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * 测试消息显示问题的修复效果
 * 验证AI输出文本能够完整显示
 */
class MessageDisplayTest {
    
    private lateinit var messageProcessor: MessageProcessor
    private val testSessionId = "test_session_123"
    
    @Before
    fun setup() {
        messageProcessor = MessageProcessor()
        messageProcessor.initialize(testSessionId, "test_message_123")
    }
    
    @Test
    fun `test AI message with complete text shows all content`() = runBlocking {
        // 模拟一个完整的AI回复消息
        val completeText = """
            这是一个测试回复。

            ## 主要内容
            1. 第一点内容
            2. 第二点内容
            3. 第三点内容

            ### 代码示例
            ```kotlin
            fun hello() {
                println("Hello World")
            }
            ```
            
            测试完成。
            ```kotlin
            fun main() {
                println("Hello, World!")
            }
            ```

            这就是完整的回复内容。
        """.trimIndent()

        val message = Message(
            id = "test_message_123",
            text = completeText,
            sender = Sender.AI,
            contentStarted = true,
            parts = emptyList() // 模拟parts为空的情况
        )

        // 测试finalizeMessageProcessing能否正确处理
        val processedMessage = messageProcessor.finalizeMessageProcessing(message)

        // 验证处理结果
        assertNotNull("处理后的消息不应为null", processedMessage)
        assertEquals("消息文本应保持完整", completeText, processedMessage.text)
        assertTrue("处理后应该有parts", processedMessage.parts.isNotEmpty())

        // 验证parts内容
        val textParts = processedMessage.parts.filterIsInstance<MarkdownPart.Text>()
        val codeParts = processedMessage.parts.filterIsInstance<MarkdownPart.CodeBlock>()

        assertTrue("应该包含文本部分", textParts.isNotEmpty())
        assertTrue("应该包含代码块", codeParts.isNotEmpty())
        
        // 验证重建的文本不会丢失内容
        val rebuiltText = processedMessage.parts.joinToString("\n") { part ->
            when (part) {
                is MarkdownPart.Text -> part.content
                is MarkdownPart.CodeBlock -> "```${part.language}\n${part.content}\n```"
                else -> ""
            }
        }.trim()

        // 允许一些格式差异，但核心内容应该保持
        assertTrue("重建的文本应包含核心内容", 
            rebuiltText.contains("这是一个测试回复") && 
            rebuiltText.contains("主要内容") &&
            rebuiltText.contains("Hello, World!")
        )

        println("✅ 消息显示测试通过 - 完整内容得到保留")
    }
    
    @Test
    fun `test streaming message updates correctly`() = runBlocking {
        // 模拟流式消息的incremental updates
        val chunks = listOf(
            "这是",
            "这是一个",
            "这是一个测试",
            "这是一个测试回复",
            "这是一个测试回复。\n\n## 标题\n内容"
        )

        var currentMessage = Message(
            id = "streaming_test_123",
            text = "",
            sender = Sender.AI,
            contentStarted = false,
            parts = emptyList()
        )

        messageProcessor.initialize(testSessionId, "streaming_test_123")

        // 模拟每次文本更新
        for (chunk in chunks) {
            currentMessage = currentMessage.copy(text = chunk, contentStarted = true)
            
            // 测试每次更新后的finalizeMessageProcessing
            val processedMessage = messageProcessor.finalizeMessageProcessing(currentMessage)
            
            assertNotNull("每次更新后消息都应有效", processedMessage)
            assertEquals("文本内容应正确", chunk, processedMessage.text)
            
            // 即使在流式过程中，也应该有基本的parts
            if (chunk.isNotBlank()) {
                assertTrue("应该至少有一个part", processedMessage.parts.isNotEmpty())
            }
        }

        println("✅ 流式消息更新测试通过")
    }
    
    @Test
    fun `test message with empty parts gets processed correctly`() = runBlocking {
        // 测试parts为空但text有内容的情况
        val messageText = "这是一个只有text没有parts的消息"
        
        val message = Message(
            id = "empty_parts_test",
            text = messageText,
            sender = Sender.AI,
            contentStarted = true,
            parts = emptyList()
        )

        val processedMessage = messageProcessor.finalizeMessageProcessing(message)

        assertNotNull("处理后消息应有效", processedMessage)
        assertEquals("文本内容应保持", messageText, processedMessage.text)
        assertTrue("应该生成parts", processedMessage.parts.isNotEmpty())

        // 应该至少有一个Text part包含完整内容
        val textParts = processedMessage.parts.filterIsInstance<MarkdownPart.Text>()
        assertTrue("应该有Text part", textParts.isNotEmpty())
        assertTrue("Text part应包含原始内容", 
            textParts.any { it.content.contains("这是一个只有text没有parts的消息") }
        )

        println("✅ 空parts消息处理测试通过")
    }
    
    @Test
    fun `test fallback mechanism when parsing fails`() = runBlocking {
        // 测试解析失败时的fallback机制
        val problematicText = "这是一个包含\u0000特殊字符的消息"
        
        val message = Message(
            id = "fallback_test",
            text = problematicText,
            sender = Sender.AI,
            contentStarted = true,
            parts = emptyList()
        )

        val processedMessage = messageProcessor.finalizeMessageProcessing(message)

        assertNotNull("即使解析失败也应返回有效消息", processedMessage)
        assertEquals("文本内容应保持", problematicText, processedMessage.text)
        assertTrue("应该有fallback parts", processedMessage.parts.isNotEmpty())

        println("✅ Fallback机制测试通过")
    }
}