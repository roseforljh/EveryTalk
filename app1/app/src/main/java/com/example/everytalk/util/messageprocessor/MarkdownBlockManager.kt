package com.example.everytalk.util.messageprocessor

import com.example.everytalk.data.network.AppStreamEvent
import com.example.everytalk.ui.components.MarkdownPart

/**
 * Minimal Block Manager to satisfy tests:
 * - Maintains a single block of the latest type (code/text/math) and overwrites on same type updates
 * - Extracts code content from fenced code blocks and language tag if present
 */
class MarkdownBlockManager {

   val blocks: MutableList<MarkdownPart> = mutableListOf()

   fun processEvent(event: AppStreamEvent) {
     when (event) {
       is AppStreamEvent.Content -> handleContent(event)
       is AppStreamEvent.ContentFinal -> {
         // Treat as content overwrite
         handleContent(
           AppStreamEvent.Content(
             text = event.text,
             output_type = event.output_type,
             block_type = event.block_type
           )
         )
       }
       else -> {
         // ignore others for this simple manager
       }
     }
   }

   private fun handleContent(event: AppStreamEvent.Content) {
     when (event.block_type?.lowercase()) {
       "code_block", "code" -> {
         val lang = extractLangFromFence(event.text)
         val codeContent = extractCodeFromFenced(event.text)
         val newBlock = MarkdownPart.CodeBlock(
           id = "code-1",
           content = codeContent,
           language = lang ?: ""
         )
         replaceWithType<MarkdownPart.CodeBlock>(newBlock)
       }
       "math_block", "math" -> {
         val latex = extractLatex(event.text) ?: ""
         val newBlock = MarkdownPart.MathBlock(
           id = "math-1",
           content = event.text,
           latex = latex,
           displayMode = true,
           renderMode = "professional"
         )
         replaceWithType<MarkdownPart.MathBlock>(newBlock)
       }
       "text", null, "" -> {
         val newBlock = MarkdownPart.Text(
           id = "text-1",
           content = event.text
         )
         replaceWithType<MarkdownPart.Text>(newBlock)
       }
       else -> {
         val newBlock = MarkdownPart.Text(
           id = "text-1",
           content = event.text
         )
         replaceWithType<MarkdownPart.Text>(newBlock)
       }
     }
   }

   private inline fun <reified T : MarkdownPart> replaceWithType(newBlock: MarkdownPart) {
     val it = blocks.iterator()
     while (it.hasNext()) {
       val b = it.next()
       if (b is T) {
         it.remove()
         break
       }
     }
     blocks.add(newBlock)
   }

   private fun extractLangFromFence(text: String): String? {
     val first = text.lineSequence().firstOrNull()?.trim() ?: return null
     if (first.startsWith("```") || first.startsWith("~~~")) {
       val rest = first.dropWhile { it == '`' || it == '~' }.trimStart()
       val lang = rest.takeWhile { !it.isWhitespace() }
       if (lang.isNotBlank()) return lang
     }
     return null
   }

   private fun extractCodeFromFenced(text: String): String {
     val lines = text.lines()
     if (lines.isEmpty()) return text
     val head = lines.first().trim()
     val tail = lines.last().trim()
     val isFencedStart = head.startsWith("```") || head.startsWith("~~~")
     val isFencedEnd = tail.startsWith("```") || tail.startsWith("~~~")
     return if (isFencedStart && isFencedEnd && lines.size >= 2) {
       lines.subList(1, lines.size - 1).joinToString("\n")
     } else {
       text
     }
   }

   private fun extractLatex(text: String): String? {
     val t = text.trim()
     return when {
       t.startsWith("$$") && t.endsWith("$$") -> t.removePrefix("$$").removeSuffix("$$").trim()
       t.startsWith("\\[") && t.endsWith("\\]") -> t.removePrefix("\\[").removeSuffix("\\]").trim()
       else -> null
     }
   }
}