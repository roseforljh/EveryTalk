package com.example.everytalk.util

import com.example.everytalk.data.network.AppStreamEvent
import com.example.everytalk.util.messageprocessor.MessageProcessor
import com.example.everytalk.util.messageprocessor.ProcessedEventResult
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * 测试MessageProcessor的重复内容检测和处理功能
 */
class MessageProcessorDuplicationTest {
    
    private lateinit var messageProcessor: MessageProcessor
    private val testMessageId = "test_message_123"
    
    @Before
    fun setup() {
        messageProcessor = MessageProcessor()
    }
    
    @Test
    fun `test duplicate text chunks are filtered out`() = runBlocking {
        // 发送相同的文本块两次
        val duplicateText = "这是一个测试文本"
        
        val result1 = messageProcessor.processStreamEvent(
            AppStreamEvent.Text(duplicateText), 
            testMessageId
        )
        
        val result2 = messageProcessor.processStreamEvent(
            AppStreamEvent.Text(duplicateText), 
            testMessageId
        )
        
        // 第一次应该成功处理
        assertTrue(result1 is ProcessedEventResult.ContentUpdated)
        assertEquals(duplicateText, (result1 as ProcessedEventResult.ContentUpdated).content)
        
        // 第二次应该被过滤，但仍然返回相同内容（不重复添加）
        assertTrue(result2 is ProcessedEventResult.ContentUpdated)
        assertEquals(duplicateText, (result2 as ProcessedEventResult.ContentUpdated).content)
        
        // 验证最终文本没有重复
        val finalText = messageProcessor.getCurrentText()
        assertEquals(duplicateText, finalText)
        assertFalse(finalText.contains("${duplicateText}${duplicateText}"))
    }
    
    @Test
    fun `test cumulative stream content is handled correctly`() = runBlocking {
        // 模拟累积式流：每次发送的内容包含之前的所有内容
        val text1 = "第一部分"
        val text2 = "第一部分第二部分"
        val text3 = "第一部分第二部分第三部分"
        
        messageProcessor.processStreamEvent(AppStreamEvent.Text(text1), testMessageId)
        messageProcessor.processStreamEvent(AppStreamEvent.Text(text2), testMessageId)
        messageProcessor.processStreamEvent(AppStreamEvent.Text(text3), testMessageId)
        
        val finalText = messageProcessor.getCurrentText()
        assertEquals(text3, finalText)
        
        // 确保没有重复内容
        val expectedParts = listOf("第一部分", "第二部分", "第三部分")
        expectedParts.forEach { part ->
            val occurrences = finalText.split(part).size - 1
            assertEquals("部分 '$part' 应该只出现一次", 1, occurrences)
        }
    }
    
    @Test
    fun `test non-cumulative stream with overlap detection`() = runBlocking {
        // 模拟非累积式流：新内容可能与现有内容有重叠
        val text1 = "计算结果："
        val text2 = "结果：16 - 216"
        val text3 = "16 - 216 = -200"
        
        messageProcessor.processStreamEvent(AppStreamEvent.Text(text1), testMessageId)
        messageProcessor.processStreamEvent(AppStreamEvent.Text(text2), testMessageId)
        messageProcessor.processStreamEvent(AppStreamEvent.Text(text3), testMessageId)
        
        val finalText = messageProcessor.getCurrentText()
        
        // 验证重叠部分没有重复
        assertFalse(finalText.contains("结果：结果："))
        assertFalse(finalText.contains("16 - 21616 - 216"))
        
        // 验证最终内容合理
        assertTrue(finalText.contains("计算"))
        assertTrue(finalText.contains("16 - 216 = -200"))
    }
    
    @Test
    fun `test empty and whitespace content is filtered`() = runBlocking {
        // 测试空内容和纯空白字符的过滤
        val meaningfulText = "有意义的内容"
        
        messageProcessor.processStreamEvent(AppStreamEvent.Text(meaningfulText), testMessageId)
        messageProcessor.processStreamEvent(AppStreamEvent.Text(""), testMessageId) // 空字符串
        messageProcessor.processStreamEvent(AppStreamEvent.Text("   "), testMessageId) // 空格
        messageProcessor.processStreamEvent(AppStreamEvent.Text("\n\n"), testMessageId) // 换行符
        
        val finalText = messageProcessor.getCurrentText()
        assertEquals(meaningfulText, finalText.trim())
    }
    
    @Test
    fun `test recent content cache prevents immediate duplicates`() = runBlocking {
        val testText = "测试重复检测缓存"
        
        // 快速发送相同内容多次
        repeat(5) {
            messageProcessor.processStreamEvent(AppStreamEvent.Text(testText), testMessageId)
        }
        
        val finalText = messageProcessor.getCurrentText()
        assertEquals(testText, finalText)
        
        // 确保没有重复
        val occurrences = finalText.split(testText).size - 1
        assertEquals(1, occurrences)
    }
    
    @Test
    fun `test reset clears all state including caches`() = runBlocking {
        val testText = "重置前的内容"
        
        messageProcessor.processStreamEvent(AppStreamEvent.Text(testText), testMessageId)
        assertEquals(testText, messageProcessor.getCurrentText())
        
        // 重置处理器
        messageProcessor.reset()
        
        // 验证状态已清空
        assertEquals("", messageProcessor.getCurrentText())
        assertNull(messageProcessor.getCurrentReasoning())
        
        // 验证重置后可以正常处理新内容
        val newText = "重置后的新内容"
        messageProcessor.processStreamEvent(AppStreamEvent.Text(newText), "new_message_id")
        assertEquals(newText, messageProcessor.getCurrentText())
    }
    
    @Test
    fun `test mathematical content duplication scenario`() = runBlocking {
        // 模拟图片中显示的数学计算重复问题
        val calculation1 = "2. 骨算减法"
        val calculation2 = "骨算减法*:"
        val calculation3 = "16 - 216 = -200"
        val finalAnswer = "最终答案: *"
        val result = "oxed{-200}"
        
        messageProcessor.processStreamEvent(AppStreamEvent.Text(calculation1), testMessageId)
        messageProcessor.processStreamEvent(AppStreamEvent.Text(calculation2), testMessageId)
        messageProcessor.processStreamEvent(AppStreamEvent.Text(calculation3), testMessageId)
        messageProcessor.processStreamEvent(AppStreamEvent.Text(finalAnswer), testMessageId)
        messageProcessor.processStreamEvent(AppStreamEvent.Text(result), testMessageId)
        
        val finalText = messageProcessor.getCurrentText()
        
        // 验证没有重复的计算步骤
        assertFalse(finalText.contains("骨算减法骨算减法"))
        assertFalse(finalText.contains("16 - 21616 - 216"))
        assertFalse(finalText.contains("最终答案最终答案"))
        
        // 验证内容完整性
        assertTrue(finalText.contains("骨算减法"))
        assertTrue(finalText.contains("16 - 216 = -200"))
        assertTrue(finalText.contains("最终答案"))
        assertTrue(finalText.contains("oxed{-200}"))
    }
}
