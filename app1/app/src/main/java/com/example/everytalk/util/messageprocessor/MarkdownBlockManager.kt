package com.example.everytalk.util.messageprocessor

import androidx.compose.runtime.mutableStateListOf
import com.example.everytalk.data.network.AppStreamEvent
import com.example.everytalk.ui.components.MarkdownPart
import com.example.everytalk.ui.components.TableData
import com.example.everytalk.ui.components.parseMarkdownTable
import java.util.UUID

/**
 * Manages a list of MarkdownPart objects for incremental, stable rendering of streaming content.
 */
class MarkdownBlockManager {
    private var currentBlockId: String? = null
    private var currentBlockType: String? = null
    private var incompleteBlockContent = StringBuilder()

    val blocks = mutableStateListOf<MarkdownPart>()

    fun processEvent(event: AppStreamEvent.Content) {
        val eventBlockType = event.block_type ?: "text"
        
        if (eventBlockType != currentBlockType) {
            // Finalize the previous block before starting a new one
            finalizeCurrentBlock()
            
            // Start a new block
            currentBlockType = eventBlockType
            currentBlockId = UUID.randomUUID().toString()
            incompleteBlockContent.append(event.text)
            
            val newBlock = createNewBlock(currentBlockId!!, currentBlockType!!, incompleteBlockContent.toString())
            blocks.add(newBlock)
        } else {
            // Continue with the current block
            incompleteBlockContent.append(event.text)
            val updatedBlock = createNewBlock(currentBlockId!!, currentBlockType!!, incompleteBlockContent.toString())
            
            if (blocks.isNotEmpty()) {
                blocks[blocks.lastIndex] = updatedBlock
            } else {
                blocks.add(updatedBlock)
            }
        }
    }

    fun finalizeCurrentBlock() {
        if (currentBlockId != null && incompleteBlockContent.isNotEmpty()) {
            val finalContent = incompleteBlockContent.toString()
            val finalBlock = createNewBlock(currentBlockId!!, currentBlockType!!, finalContent, isFinal = true)
            
            if (blocks.isNotEmpty() && blocks.last().id == finalBlock.id) {
                blocks[blocks.lastIndex] = finalBlock
            } else if (blocks.isEmpty()) {
                blocks.add(finalBlock)
            }
        }
        
        // Reset for the next block (if any)
        currentBlockId = null
        currentBlockType = null
        incompleteBlockContent.clear()
    }

    private fun createNewBlock(id: String, type: String, content: String, isFinal: Boolean = false): MarkdownPart {
        return when (type) {
            "code_block" -> {
                val (lang, code) = parseCodeBlock(content)
                MarkdownPart.CodeBlock(id = id, content = code, language = lang)
            }
            "table" -> {
                val tableData = if (isFinal) {
                    parseMarkdownTable(content) ?: TableData(headers = listOf("..."), rows = listOf(listOf("Parsing error")), alignsString = listOf("Left"))
                } else {
                    // For streaming, we create a placeholder table structure
                    TableData(headers = listOf("Streaming Table..."), rows = listOf(listOf(content.replace("\n", " | "))), alignsString = listOf("Left"))
                }
                MarkdownPart.Table(id = id, tableData = tableData)
            }
            "math_block" -> MarkdownPart.MathBlock(id = id, latex = content.trim())
            else -> {
                // For text, we can do a more refined parsing even during streaming
                // This part could be enhanced to split text into smaller MarkdownParts (e.g., with inline math)
                MarkdownPart.Text(id = id, content = content)
            }
        }
    }
    
    private fun parseCodeBlock(content: String): Pair<String, String> {
        val lines = content.lines()
        if (lines.isEmpty()) return "" to ""
        
        val firstLine = lines.first().trim()
        return if (firstLine.startsWith("```")) {
            val lang = firstLine.removePrefix("```").trim()
            val code = lines.drop(1).joinToString("\n").removeSuffix("```").trimEnd('\n')
            lang to code
        } else {
            "" to content
        }
    }

    fun reset() {
        blocks.clear()
        currentBlockId = null
        currentBlockType = null
        incompleteBlockContent.clear()
    }
}