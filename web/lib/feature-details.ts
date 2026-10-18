export type LocalizedCopy = {
  zh: string
  en: string
}

export type FeatureDetail = {
  slug: string
  label: string
  title: LocalizedCopy
  description: LocalizedCopy
  sourcePath: string
  sourceLine: string
  language: string
  code: string
  explanationTitle: LocalizedCopy
  explanation: LocalizedCopy[]
  tags: LocalizedCopy[]
}

/**
 * 官网详情页展示的是真实 Android 工程中的精选片段。
 * 只截取能说明功能边界的关键代码，避免把密钥、业务无关实现和整文件复制到页面。
 */
export const featureDetails: readonly FeatureDetail[] = [
  {
    slug: 'multi-model',
    label: 'MULTI-MODEL',
    title: { zh: '模型，自己选。', en: 'Your models. Your choice.' },
    description: {
      zh: 'EveryTalk 把服务地址、模型、参数和能力记录在同一份配置里，让你可以自由切换自己的模型服务。',
      en: 'EveryTalk keeps the endpoint, model, parameters and capabilities together so you can move between your own providers.',
    },
    sourcePath: 'app/src/main/.../data/database/entities/ApiConfigEntity.kt',
    sourceLine: '10–26',
    language: 'Kotlin',
    code: `data class ApiConfigEntity(
    @PrimaryKey
    val id: String,
    val address: String,
    val key: String,
    val model: String,
    val provider: String,
    val name: String,
    val channel: String,
    val isValid: Boolean,
    val modalityType: ModalityType,
    val temperature: Float,
    val topP: Float?,
    val maxTokens: Int?,
    val defaultUseWebSearch: Boolean?,
    val imageSize: String?,
    val numInferenceSteps: Int?,
    val guidanceScale: Float?,
)`,
    explanationTitle: { zh: '把选择权留给用户', en: 'Keep the choice with the user' },
    explanation: [
      {
        zh: '模型配置不是写死在界面里的名称，而是可持久化的配置实体。服务地址、模型名和提供商可以分别调整。',
        en: 'A model is not a hard-coded name in the UI. It is a persisted configuration whose endpoint, model and provider can change independently.',
      },
      {
        zh: '同一份结构同时承载文本和图像能力，参数也会跟着模型类型保存，切换时不需要重新填写。',
        en: 'The same structure carries text and image capabilities, so model-specific parameters survive a mode switch.',
      },
    ],
    tags: [
      { zh: '服务地址', en: 'Endpoints' },
      { zh: '模型参数', en: 'Parameters' },
    ],
  },
  {
    slug: 'search-mcp',
    label: 'SEARCH & MCP',
    title: { zh: '对话，连接世界。', en: 'Connect to the world.' },
    description: {
      zh: '联网搜索和 MCP 服务器都由配置驱动。工具可以被启用、携带请求头，并在需要时接入对话流程。',
      en: 'Web search and MCP servers are configuration-driven. Tools can be enabled, carry headers and join a conversation when needed.',
    },
    sourcePath: 'app/src/main/.../data/database/entities/McpServerConfigEntity.kt',
    sourceLine: '11–50',
    language: 'Kotlin',
    code: `data class McpServerConfigEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val url: String,
    val transportType: String = "SSE",
    val enabled: Boolean = true,
    val headers: String = "[]"
) {
    fun toModel(): McpServerConfig {
        val headerList: List<Pair<String, String>> = try {
            Json.decodeFromString<List<List<String>>>(headers).map { it[0] to it[1] }
        } catch (e: Exception) {
            try {
                val headerMap = Json.decodeFromString<Map<String, String>>(headers)
                headerMap.toList()
            } catch (e2: Exception) {
                emptyList()
            }
        }

        val commonOptions = McpCommonOptions(
            enable = enabled,
            name = name,
            headers = headerList
        )

        return when (McpTransportType.valueOf(transportType)) {
            McpTransportType.SSE -> McpServerConfig.SseTransportServer(
                id = id,
                commonOptions = commonOptions,
                url = url
            )
            McpTransportType.HTTP -> McpServerConfig.StreamableHTTPServer(
                id = id,
                commonOptions = commonOptions,
                url = url
            )
        }
    }
}`,
    explanationTitle: { zh: '工具是连接，不是装饰', en: 'Tools are connections, not decoration' },
    explanation: [
      {
        zh: '服务器地址、传输方式和启用状态会被保存下来，应用启动后可以还原用户的工具配置。',
        en: 'The server URL, transport and enabled state are persisted so the app can restore a user’s tool setup.',
      },
      {
        zh: 'SSE 和 Streamable HTTP 共用同一套配置模型，接入不同 MCP 服务时不需要改动聊天界面。',
        en: 'SSE and Streamable HTTP share one configuration model, so adding a different MCP service does not require a new chat UI.',
      },
    ],
    tags: [
      { zh: '联网搜索', en: 'Web search' },
      { zh: 'MCP 工具', en: 'MCP tools' },
    ],
  },
  {
    slug: 'image-generation',
    label: 'IMAGE GENERATION',
    title: { zh: '想法，变成画面。', en: 'Turn ideas into images.' },
    description: {
      zh: '图像生成会根据模型和提供商选择直连客户端，同时兼容 Gemini、OpenAI 兼容接口以及其他图像服务。',
      en: 'Image generation selects a direct client from the provider and model, supporting Gemini, OpenAI-compatible APIs and more.',
    },
    sourcePath: 'app/src/main/.../data/network/llm/ImageGenerationDirectClient.kt',
    sourceLine: '75–112',
    language: 'Kotlin',
    code: `/**
 * 直连 OpenAI 兼容图像生成 API
 */
suspend fun generateImageOpenAI(
    client: HttpClient,
    request: ImageGenRequest
): ImageGenerationResponse {
    Log.i(TAG, "🔄 启动 OpenAI 兼容图像生成直连模式")

    val rawAddress = request.apiAddress.trimEnd('/').takeIf { it.isNotBlank() }
        ?: com.android.everytalk.BuildConfig.DEFAULT_OPENAI_API_BASE_URL.trimEnd('/').takeIf { it.isNotBlank() }
        ?: "https://api.openai.com"

    // 如果地址已经包含完整的图像生成端点路径，直接使用；否则追加标准路径
    val url = if (rawAddress.endsWith("/v1/images/generations") ||
                  rawAddress.endsWith("/images/generations")) {
        rawAddress
    } else {
        "$rawAddress/v1/images/generations"
    }

    Log.d(TAG, "直连 URL: $url")
    val payload = buildOpenAIImagePayload(request)

    return client.preparePost(url) {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer \${request.apiKey}")
        setBody(payload)
    }.execute { response ->
        if (!response.status.isSuccess()) {
            val errorBody = response.readErrorTextAtMost() ?: "(no body)"
            Log.e(TAG, "OpenAI 图像生成错误 \${response.status}: bodyChars=\${errorBody.length}")
            throw Exception("OpenAI 图像生成错误 \${response.status}: $errorBody")
        }

        parseOpenAIImageResponse(response.readTextAtMost(MAX_INLINE_IMAGE_JSON_BYTES))
    }
}`,
    explanationTitle: { zh: '同一个入口，适配不同服务', en: 'One entry point, different providers' },
    explanation: [
      {
        zh: '调用前先检查客户端状态和图像请求，缺少必要配置时直接给出明确错误，避免发出半成品请求。',
        en: 'The client and image request are checked before any network call, so incomplete configuration fails clearly.',
      },
      {
        zh: '提供商判断集中在客户端入口，具体请求格式交给对应的直连实现，新增服务时不会污染聊天流程。',
        en: 'Provider selection stays at the client boundary while request formats live in direct implementations, keeping chat flow clean.',
      },
    ],
    tags: [
      { zh: '多提供商', en: 'Multiple providers' },
      { zh: '直连 API', en: 'Direct API' },
    ],
  },
  {
    slug: 'read-organize',
    label: 'READ & ORGANIZE',
    title: { zh: '内容，好好呈现。', en: 'Read clearly. Stay organized.' },
    description: {
      zh: 'Markdown、代码和公式交给渲染层清晰呈现；聊天与图像生成历史分开保存，重新打开时仍能回到原来的上下文。',
      en: 'Markdown, code and equations stay readable in the renderer, while chat and image histories remain separate and restorable.',
    },
    sourcePath: 'app/src/main/.../data/database/RoomDataSource.kt',
    sourceLine: '186–224',
    language: 'Kotlin',
    code: `suspend fun saveChatHistory(
    history: List<List<Message>>,
    protectedSessionIds: Set<String> = emptySet(),
) {
    saveSessions(
        history = history,
        isImageGeneration = false,
        protectedSessionIds = protectedSessionIds,
    )
}

suspend fun saveImageGenerationHistory(
    history: List<List<Message>>,
    protectedSessionIds: Set<String> = emptySet(),
) {
    saveSessions(
        history = history,
        isImageGeneration = true,
        protectedSessionIds = protectedSessionIds,
    )
}

suspend fun clearChatHistory() {
    chatDao.clearAllSessions(isImageGen = false)
}`,
    explanationTitle: { zh: '让内容回到它该在的位置', en: 'Keep content where it belongs' },
    explanation: [
      {
        zh: '普通对话和图像生成历史使用同一个持久化入口，但用 isImageGeneration 明确区分，读取和清理不会互相影响。',
        en: 'Chat and image history share one persistence entry point, while isImageGeneration keeps reads and cleanup independent.',
      },
      {
        zh: '渲染层负责把 Markdown、代码和公式变成可读内容，数据层负责让会话在重新打开后保持连续。',
        en: 'The renderer makes Markdown, code and equations readable; the data layer keeps a conversation continuous after reopening.',
      },
    ],
    tags: [
      { zh: 'Markdown / 公式', en: 'Markdown / math' },
      { zh: '会话管理', en: 'Conversations' },
    ],
  },
]

export function getFeatureDetail(slug: string): FeatureDetail | undefined {
  return featureDetails.find((feature) => feature.slug === slug)
}
