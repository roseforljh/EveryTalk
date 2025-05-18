package com.example.everytalk.data.DataClass // 请确认包名

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
// import kotlinx.serialization.json.JsonObject // 如果不再直接使用，可以移除

/**
 * 代表从后端代理服务接收到的一个流式事件。
 * 这个类现在更通用，以匹配后端自定义的SSE事件结构。
 */
@Serializable
data class AppStreamEvent( // 或者您可以继续叫 OpenAiStreamChunk，但其含义已变
    @SerialName("type")
    val type: String, // 事件类型，例如: "status_update", "web_search_results", "content", "reasoning", "tool_calls_chunk", "google_function_call_request", "finish", "error"

    // --- 对应 "status_update" 类型 ---
    @SerialName("stage")
    val stage: String? = null, // 例如: "web_indexing_started", "web_analysis_started", "web_analysis_complete"

    // --- 对应 "web_search_results" 类型 ---
    @SerialName("results")
    val results: List<WebSearchResult>? = null, // WebSearchResult 来自 Message.kt 或单独定义

    // --- 对应 "content" 和 "reasoning" 类型 ---
    @SerialName("text")
    val text: String? = null, // 用于 "content" 和 "reasoning" 事件的文本内容

    // --- 对应 "tool_calls_chunk" 类型 (来自OpenAI兼容流的工具调用) ---
    @SerialName("data") // 后端 process_openai_sse_line 中用的是 "data" 字段
    val toolCallsData: List<OpenAiToolCall>? = null, // OpenAiToolCall 来自您原有的定义

    // --- 对应 "google_function_call_request" 类型 ---
    @SerialName("id") // 也可用于 google_function_call_request 的 id
    val id: String? = null,
    @SerialName("name") // 用于 google_function_call_request 的 name
    val name: String? = null,
    @SerialName("arguments_obj") // 用于 google_function_call_request 的 arguments_obj
    val argumentsObj: kotlinx.serialization.json.JsonObject? = null, // 假设参数是JSON对象

    // --- 对应 "finish" 类型 ---
    @SerialName("reason")
    val reason: String? = null, // 例如: "stop", "length", "error", "timeout_error", "network_error"

    // --- 对应 "error" 类型 ---
    @SerialName("message")
    val message: String? = null, // 错误消息
    @SerialName("upstream_status")
    val upstreamStatus: Int? = null, // 上游API的错误状态码

    // --- 通用时间戳 (后端已为所有事件添加) ---
    @SerialName("timestamp")
    val timestamp: String? = null,

)

// 您原有的 OpenAiToolCall 和 OpenAiFunctionCall 可以保持不变，因为它们被 toolCallsData 引用
@Serializable
data class OpenAiToolCall(
    @SerialName("index")
    val index: Int? = null, // 注意: 后端 process_openai_sse_line 中 "tool_calls_chunk" 的 "data" 是直接 List<OriginalOpenAIToolCall>
    @SerialName("id")
    val id: String? = null,
    @SerialName("type")
    val type: String? = null, // e.g., "function"
    @SerialName("function")
    val function: OpenAiFunctionCall? = null
)

@Serializable
data class OpenAiFunctionCall(
    @SerialName("name")
    val name: String? = null,
    @SerialName("arguments")
    val arguments: String? = null // Usually a raw JSON string
)

