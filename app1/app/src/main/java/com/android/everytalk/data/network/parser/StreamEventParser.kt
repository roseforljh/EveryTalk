package com.android.everytalk.data.network.parser

import android.util.Log
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.DataClass.WebSearchResult
import kotlinx.serialization.json.*

/**
 * SSE 流事件解析器
 * 从 ApiClient.kt 中提取，负责解析后端流式响应事件
 */
object StreamEventParser {
    private const val TAG = "StreamEventParser"
    
    /**
     * 解析后端流事件 JSON 格式并转换为 AppStreamEvent
     */
    fun parseBackendStreamEvent(jsonChunk: String): AppStreamEvent? {
        try {
            val jsonObject = Json.parseToJsonElement(jsonChunk).jsonObject
            val type = jsonObject["type"]?.jsonPrimitive?.content
            
            return when (type) {
                "content" -> parseContentEvent(jsonObject)
                "text" -> parseTextEvent(jsonObject)
                "content_final" -> parseContentFinalEvent(jsonObject)
                "reasoning" -> parseReasoningEvent(jsonObject)
                "reasoning_finish" -> parseReasoningFinishEvent(jsonObject)
                "stream_end" -> parseStreamEndEvent(jsonObject)
                "web_search_status" -> parseWebSearchStatusEvent(jsonObject)
                "web_search_results" -> parseWebSearchResultsEvent(jsonObject)
                "status_update" -> parseStatusUpdateEvent(jsonObject)
                "tool_call" -> parseToolCallEvent(jsonObject)
                "error" -> parseErrorEvent(jsonObject)
                "finish" -> parseFinishEvent(jsonObject)
                "image_generation" -> parseImageGenerationEvent(jsonObject)
                "code_execution_result" -> parseCodeExecutionResultEvent(jsonObject)
                "code_executable" -> parseCodeExecutableEvent(jsonObject)
                else -> {
                    Log.w(TAG, "Unknown stream event type: $type")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse backend stream event: $jsonChunk", e)
            return null
        }
    }
    
    private fun parseContentEvent(jsonObject: JsonObject): AppStreamEvent.Content {
        val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
        val outputType = jsonObject["output_type"]?.jsonPrimitive?.content
        val blockType = jsonObject["block_type"]?.jsonPrimitive?.content
        return AppStreamEvent.Content(text, outputType, blockType)
    }
    
    private fun parseTextEvent(jsonObject: JsonObject): AppStreamEvent.Text {
        val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
        return AppStreamEvent.Text(text)
    }
    
    private fun parseContentFinalEvent(jsonObject: JsonObject): AppStreamEvent.ContentFinal {
        val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
        val outputType = jsonObject["output_type"]?.jsonPrimitive?.content
        val blockType = jsonObject["block_type"]?.jsonPrimitive?.content
        return AppStreamEvent.ContentFinal(text, outputType, blockType)
    }
    
    private fun parseReasoningEvent(jsonObject: JsonObject): AppStreamEvent.Reasoning {
        val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
        return AppStreamEvent.Reasoning(text)
    }
    
    private fun parseReasoningFinishEvent(jsonObject: JsonObject): AppStreamEvent.ReasoningFinish {
        val ts = jsonObject["timestamp"]?.jsonPrimitive?.content
        return AppStreamEvent.ReasoningFinish(ts)
    }
    
    private fun parseStreamEndEvent(jsonObject: JsonObject): AppStreamEvent.StreamEnd {
        val messageId = jsonObject["messageId"]?.jsonPrimitive?.content ?: ""
        return AppStreamEvent.StreamEnd(messageId)
    }
    
    private fun parseWebSearchStatusEvent(jsonObject: JsonObject): AppStreamEvent.WebSearchStatus {
        val stage = jsonObject["stage"]?.jsonPrimitive?.content ?: ""
        return AppStreamEvent.WebSearchStatus(stage)
    }
    
    private fun parseWebSearchResultsEvent(jsonObject: JsonObject): AppStreamEvent.WebSearchResults {
        val results = try {
            val resultsList = jsonObject["results"]?.jsonArray ?: JsonArray(emptyList())
            resultsList.mapIndexed { index, resultElement ->
                try {
                    val resultObject = resultElement.jsonObject
                    WebSearchResult(
                        index = index,
                        title = resultObject["title"]?.jsonPrimitive?.content ?: "",
                        snippet = resultObject["snippet"]?.jsonPrimitive?.content ?: "",
                        href = resultObject["href"]?.jsonPrimitive?.content ?: ""
                    )
                } catch (e: Exception) {
                    null
                }
            }.filterNotNull()
        } catch (e: Exception) {
            emptyList()
        }
        return AppStreamEvent.WebSearchResults(results)
    }
    
    private fun parseStatusUpdateEvent(jsonObject: JsonObject): AppStreamEvent.StatusUpdate {
        val stage = jsonObject["stage"]?.jsonPrimitive?.content ?: ""
        return AppStreamEvent.StatusUpdate(stage)
    }
    
    private fun parseToolCallEvent(jsonObject: JsonObject): AppStreamEvent.ToolCall {
        val id = jsonObject["id"]?.jsonPrimitive?.content ?: ""
        val name = jsonObject["name"]?.jsonPrimitive?.content ?: ""
        val argumentsObj = try {
            jsonObject["argumentsObj"]?.jsonObject ?: buildJsonObject { }
        } catch (e: Exception) {
            buildJsonObject { }
        }
        val isReasoningStep = jsonObject["isReasoningStep"]?.jsonPrimitive?.booleanOrNull
        return AppStreamEvent.ToolCall(id, name, argumentsObj, isReasoningStep)
    }
    
    private fun parseErrorEvent(jsonObject: JsonObject): AppStreamEvent.Error {
        val message = jsonObject["message"]?.jsonPrimitive?.content ?: ""
        val upstreamStatus = jsonObject["upstreamStatus"]?.jsonPrimitive?.intOrNull
        return AppStreamEvent.Error(message, upstreamStatus)
    }
    
    private fun parseFinishEvent(jsonObject: JsonObject): AppStreamEvent.Finish {
        val reason = jsonObject["reason"]?.jsonPrimitive?.content ?: ""
        return AppStreamEvent.Finish(reason)
    }
    
    private fun parseImageGenerationEvent(jsonObject: JsonObject): AppStreamEvent.ImageGeneration {
        val imageUrl = jsonObject["imageUrl"]?.jsonPrimitive?.content ?: ""
        return AppStreamEvent.ImageGeneration(imageUrl)
    }
    
    private fun parseCodeExecutionResultEvent(jsonObject: JsonObject): AppStreamEvent.CodeExecutionResult {
        val codeExecutionOutput = jsonObject["codeExecutionOutput"]?.jsonPrimitive?.contentOrNull
        val codeExecutionOutcome = jsonObject["codeExecutionOutcome"]?.jsonPrimitive?.contentOrNull
        val imageUrl = jsonObject["imageUrl"]?.jsonPrimitive?.contentOrNull
        return AppStreamEvent.CodeExecutionResult(codeExecutionOutput, codeExecutionOutcome, imageUrl)
    }
    
    private fun parseCodeExecutableEvent(jsonObject: JsonObject): AppStreamEvent.CodeExecutable {
        val executableCode = jsonObject["executableCode"]?.jsonPrimitive?.contentOrNull
        val codeLanguage = jsonObject["codeLanguage"]?.jsonPrimitive?.contentOrNull
        return AppStreamEvent.CodeExecutable(executableCode, codeLanguage)
    }
}
