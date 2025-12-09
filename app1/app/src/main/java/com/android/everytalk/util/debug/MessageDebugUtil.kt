package com.android.everytalk.util.debug

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.ui.components.MarkdownPart

/**
 * 消息显示调试工具
 * 帮助诊断AI输出不完整的问题
 */
object MessageDebugUtil {
    private const val TAG = "MessageDebug"
    
    /**
     * 分析消息的显示状态
     */
    fun analyzeMessageDisplayState(message: Message, context: String = "") {
        android.util.Log.d(TAG, "=== Message Display Analysis ${if (context.isNotEmpty()) "[$context]" else ""} ===")
        android.util.Log.d(TAG, "Message ID: ${message.id}")
        android.util.Log.d(TAG, "Sender: ${message.sender}")
        android.util.Log.d(TAG, "Content Started: ${message.contentStarted}")
        android.util.Log.d(TAG, "Is Error: ${message.isError}")
        android.util.Log.d(TAG, "Text Length: ${message.text.length}")
        android.util.Log.d(TAG, "Text Hash: ${message.text.hashCode()}")
        android.util.Log.d(TAG, "Parts Count: ${message.parts.size}")
        
        // 文本内容分析
        if (message.text.isNotBlank()) {
            val lines = message.text.lines()
            android.util.Log.d(TAG, "Text Lines: ${lines.size}")
            android.util.Log.d(TAG, "Text Preview (first 100 chars): ${message.text.take(100)}")
            android.util.Log.d(TAG, "Text Preview (last 100 chars): ${message.text.takeLast(100)}")
            
            // 检查是否有异常截断
            val lastLine = lines.lastOrNull()?.trim() ?: ""
            if (lastLine.isNotEmpty() && !lastLine.endsWith("。") && !lastLine.endsWith("！") && !lastLine.endsWith("？") && !lastLine.endsWith(".")) {
                android.util.Log.w(TAG, "Possible truncation detected - last line doesn't end with punctuation: '$lastLine'")
            }
        } else {
            android.util.Log.w(TAG, "Message text is blank!")
        }
        
        // Parts分析
        if (message.parts.isNotEmpty()) {
            android.util.Log.d(TAG, "Parts breakdown:")
            message.parts.forEachIndexed { index, part ->
                when (part) {
                    is MarkdownPart.Text -> {
                        android.util.Log.d(TAG, "  Part $index: Text (${part.content.length} chars) - '${part.content.take(50)}${if (part.content.length > 50) "..." else ""}' ")
                    }
                    is MarkdownPart.CodeBlock -> {
                        android.util.Log.d(TAG, "  Part $index: CodeBlock (${part.language}) - ${part.content.length} chars")
                    }
                    // Math blocks removed
                    // is MarkdownPart.Table -> {
                    //     android.util.Log.d(TAG, "  Part $index: Table (${part.tableData.headers.size} cols, ${part.tableData.rows.size} rows)")
                    // }
                    else -> {
                        android.util.Log.d(TAG, "  Part $index: ${part::class.simpleName}")
                    }
                }
            }
            
            // 检查parts重建后的内容长度
            val reconstructedLength = message.parts.sumOf { part ->
                when (part) {
                    is MarkdownPart.Text -> part.content.length
                    is MarkdownPart.CodeBlock -> part.content.length + part.language.length + 6 // ```language\n...\n```
                    // Math blocks removed
                    // is MarkdownPart.Table -> part.tableData.headers.size * 10 + part.tableData.rows.size * 20 // 估算
                    else -> 0
                }
            }
            android.util.Log.d(TAG, "Estimated reconstructed length: $reconstructedLength")
            
            if (message.text.length > reconstructedLength * 1.2) {
                android.util.Log.w(TAG, "Original text significantly longer than parts - possible parsing issue")
            }
        } else {
            android.util.Log.w(TAG, "No parts found!")
        }
        
        android.util.Log.d(TAG, "=== End Analysis ===")
    }
    
    /**
     * 比较两个消息状态
     */
    fun compareMessageStates(oldMessage: Message, newMessage: Message, context: String = "") {
        android.util.Log.d(TAG, "=== Message State Comparison ${if (context.isNotEmpty()) "[$context]" else ""} ===")
        
        if (oldMessage.text != newMessage.text) {
            android.util.Log.d(TAG, "Text changed: ${oldMessage.text.length} -> ${newMessage.text.length} chars")
            if (newMessage.text.length < oldMessage.text.length) {
                android.util.Log.w(TAG, "Text got shorter - possible content loss!")
            }
        }
        
        if (oldMessage.parts.size != newMessage.parts.size) {
            android.util.Log.d(TAG, "Parts count changed: ${oldMessage.parts.size} -> ${newMessage.parts.size}")
        }
        
        if (oldMessage.contentStarted != newMessage.contentStarted) {
            android.util.Log.d(TAG, "Content started changed: ${oldMessage.contentStarted} -> ${newMessage.contentStarted}")
        }
        
        android.util.Log.d(TAG, "=== End Comparison ===")
    }
    
    /**
     * 监控流式更新过程
     */
    fun logStreamingUpdate(messageId: String, textChunk: String, totalLength: Int) {
        android.util.Log.d(TAG, "Streaming Update - Message: $messageId")
        android.util.Log.d(TAG, "  Chunk: '${textChunk.take(50)}${if (textChunk.length > 50) "..." else ""}'")
        android.util.Log.d(TAG, "  Chunk length: ${textChunk.length}")
        android.util.Log.d(TAG, "  Total length: $totalLength")
    }
    
    /**
     * 检查消息完整性
     */
    fun checkMessageIntegrity(message: Message): List<String> {
        val issues = mutableListOf<String>()
        
        if (message.sender.name == "AI" && message.text.isBlank()) {
            issues.add("AI message has blank text")
        }
        
        if (message.contentStarted && message.text.isBlank() && message.parts.isEmpty()) {
            issues.add("Content started but no text or parts")
        }
        
        if (message.text.isNotBlank() && message.parts.isEmpty()) {
            issues.add("Has text but no parts (may need processing)")
        }
        
        // 检查可能的截断
        val text = message.text
        if (text.isNotBlank()) {
            val lines = text.lines()
            val lastLine = lines.lastOrNull()?.trim() ?: ""
            
            // 检查是否在句子中间截断
            if (lastLine.isNotEmpty()) {
                val suspiciousEndings = listOf(
                    "从枝头", "在微弱", "它们", "层层", "或深或浅", 
                    "的", "了", "着", "在", "是", "有", "和", "与"
                )
                
                if (suspiciousEndings.any { lastLine.endsWith(it) }) {
                    issues.add("Possible truncation - ends with suspicious fragment: '$lastLine'")
                }
                
                // 检查是否在段落中间截断
                if (!lastLine.endsWith("。") && !lastLine.endsWith("！") && !lastLine.endsWith("？") && 
                    !lastLine.endsWith(".") && !lastLine.endsWith("!") && !lastLine.endsWith("?") &&
                    text.length > 100) {
                    issues.add("Possible truncation - doesn't end with punctuation")
                }
            }
        }
        
        return issues
    }
}