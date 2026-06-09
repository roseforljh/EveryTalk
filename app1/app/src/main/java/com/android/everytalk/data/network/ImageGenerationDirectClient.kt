package com.android.everytalk.data.network

import android.util.Base64
import android.util.Log
import com.android.everytalk.data.DataClass.ImageGenRequest
import com.android.everytalk.data.DataClass.ImageGenerationResponse
import com.android.everytalk.data.DataClass.ImageUrl
import com.android.everytalk.data.DataClass.Timings
import com.android.everytalk.ui.components.ImageGenCapabilities
import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlin.random.Random

/**
 * 图像生成直连客户端
 * 支持 Gemini、OpenAI 兼容、Seedream、Modal Z-Image-Turbo 和 Qwen 图像编辑 API
 */
object ImageGenerationDirectClient {
    private const val TAG = "ImageGenDirectClient"
    
    /**
     * 直连 Gemini 图像生成 API
     */
    suspend fun generateImageGemini(
        client: HttpClient,
        request: ImageGenRequest
    ): ImageGenerationResponse {
        Log.i(TAG, "🔄 启动 Gemini 图像生成直连模式")
        
        val rawAddress = request.apiAddress.trimEnd('/').takeIf { it.isNotBlank() }
            ?: com.android.everytalk.BuildConfig.GOOGLE_API_BASE_URL.trimEnd('/').takeIf { it.isNotBlank() }
            ?: "https://generativelanguage.googleapis.com"

        // 清理可能存在的 OpenAI 风格路径，提取纯基础 URL
        val baseUrl = rawAddress
            .replace(Regex("/v1/images/generations.*$"), "")
            .replace(Regex("/v1beta/models.*$"), "")
            .replace(Regex("/v1/.*$"), "")
            .trimEnd('/')

        val model = request.model
        val url = "$baseUrl/v1beta/models/$model:generateContent?key=${request.apiKey}"
        
        Log.d(TAG, "直连 URL: ${url.substringBefore("?key=")}")
        
        val payload = buildGeminiImagePayload(request)
        
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        
        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
            Log.e(TAG, "Gemini 图像生成错误 ${response.status}: $errorBody")
            throw Exception("Gemini 图像生成错误 ${response.status}: $errorBody")
        }
        
        return parseGeminiImageResponse(response.bodyAsText())
    }
    
    /**
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
        
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${request.apiKey}")
            setBody(payload)
        }
        
        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
            Log.e(TAG, "OpenAI 图像生成错误 ${response.status}: $errorBody")
            throw Exception("OpenAI 图像生成错误 ${response.status}: $errorBody")
        }
        
        return parseOpenAIImageResponse(response.bodyAsText())
    }

    /**
     * 直连 OpenAI 兼容图像编辑 API
     * 官方 GPT Image 编辑接口使用 multipart/form-data: /v1/images/edits
     */
    suspend fun generateImageOpenAIWithReference(
        client: HttpClient,
        request: ImageGenRequest,
        inputImages: List<Pair<String, String>>
    ): ImageGenerationResponse {
        Log.i(TAG, "🔄 启动 OpenAI 兼容图像编辑直连模式")

        if (inputImages.isEmpty()) {
            throw Exception("OpenAI 图像编辑需要提供输入图片")
        }

        val rawAddress = request.apiAddress.trimEnd('/').takeIf { it.isNotBlank() }
            ?: com.android.everytalk.BuildConfig.DEFAULT_OPENAI_API_BASE_URL.trimEnd('/').takeIf { it.isNotBlank() }
            ?: "https://api.openai.com"

        val url = when {
            rawAddress.endsWith("/v1/images/edits") || rawAddress.endsWith("/images/edits") -> rawAddress
            rawAddress.endsWith("/v1/images/generations") -> rawAddress.removeSuffix("/generations") + "/edits"
            rawAddress.endsWith("/images/generations") -> rawAddress.removeSuffix("/generations") + "/edits"
            else -> "$rawAddress/v1/images/edits"
        }

        Log.d(TAG, "直连 URL: $url")

        val response = client.post(url) {
            header(HttpHeaders.Authorization, "Bearer ${request.apiKey}")
            setBody(MultiPartFormDataContent(formData {
                append("model", request.model)
                append("prompt", request.prompt.takeIf { it.isNotBlank() } ?: "Edit this image")
                request.imageSize?.takeIf { it.isNotBlank() }?.let { append("size", it) }
                request.batchSize?.let { append("n", it.toString()) }
                request.quality?.takeIf { it.isNotBlank() }?.let { append("quality", it) }
                inputImages.forEachIndexed { index, (base64, mimeType) ->
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    append(
                        key = "image[]",
                        value = bytes,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, mimeType)
                            append(HttpHeaders.ContentDisposition, "filename=\"image_$index.${extensionForMime(mimeType)}\"")
                        }
                    )
                }
            }))
        }

        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
            Log.e(TAG, "OpenAI 图像编辑错误 ${response.status}: $errorBody")
            throw Exception("OpenAI 图像编辑错误 ${response.status}: $errorBody")
        }

        return parseOpenAIImageResponse(response.bodyAsText())
    }

    /**
     * 直连 Seedream (豆包·即梦) 图像生成 API
     */
    suspend fun generateImageSeedream(
        client: HttpClient,
        request: ImageGenRequest
    ): ImageGenerationResponse {
        Log.i(TAG, "🔄 启动 Seedream 图像生成直连模式")
        
        // 清理 URL：移除尾部的 '#' 和 '/v1/images/generations' 等多余路径
        val rawUrl = request.apiAddress
            .trimEnd('/')
            .removeSuffix("#")
            .removeSuffix("/v1/images/generations")
            .removeSuffix("#")
            .trimEnd('/')
        
        val baseUrl = rawUrl.takeIf { it.isNotBlank() }
            ?: "https://ark.cn-beijing.volces.com/api/v3/images/generations"
            
        Log.d(TAG, "直连 URL: $baseUrl")
        
        val payload = buildSeedreamPayload(request)
        
        val response = client.post(baseUrl) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${request.apiKey}")
            setBody(payload)
        }
        
        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
            Log.e(TAG, "Seedream 图像生成错误 ${response.status}: $errorBody")
            throw Exception("Seedream 图像生成错误 ${response.status}: $errorBody")
        }
        
        // Seedream 返回格式兼容 OpenAI (data[].url)
        return parseOpenAIImageResponse(response.bodyAsText())
    }

    /**
     * 直连 Modal Z-Image-Turbo 图像生成 API
     * Modal 使用 GET 请求 + Query Parameters，返回 JPEG 字节流
     */
    suspend fun generateImageModal(
        client: HttpClient,
        request: ImageGenRequest,
        modalUrls: List<String>
    ): ImageGenerationResponse {
        Log.i(TAG, "🔄 启动 Modal Z-Image-Turbo 图像生成直连模式")
        
        if (modalUrls.isEmpty()) {
            throw Exception("Modal API URL 列表为空")
        }
        
        // 解析目标尺寸
        var width = 1024
        var height = 1024
        
        try {
            val aspectRatio = request.aspectRatio
            val sizeVal = request.imageSize?.lowercase() ?: "2k"
            
            // Modal 支持的尺寸映射
            val modalSizeMap = mapOf(
                Pair("2k", "1:1") to Pair(2048, 2048),
                Pair("2k", "16:9") to Pair(2048, 1152),
                Pair("2k", "9:16") to Pair(1152, 2048),
                Pair("2k", "4:3") to Pair(2048, 1536),
                Pair("hd", "1:1") to Pair(1024, 1024),
                Pair("hd", "16:9") to Pair(1024, 576),
                Pair("hd", "9:16") to Pair(576, 1024)
            )
            
            // 解析逻辑：优先尝试从 aspectRatio 中提取 "2K"/"HD" 和比例
            var targetSizeKey: String? = null
            var targetRatioKey: String? = null
            
            val arClean = (aspectRatio ?: "").trim().uppercase()
            if (arClean.isNotEmpty()) {
                // 尝试解析 "2K 1:1" 或 "HD 16:9" 格式
                for (prefix in listOf("2K", "HD")) {
                    if (arClean.contains(prefix)) {
                        targetSizeKey = prefix.lowercase()
                        // 提取比例部分，移除 "2K"/"HD" 和空格
                        targetRatioKey = arClean.replace(prefix, "").trim()
                        break
                    }
                }
                
                // 如果没找到前缀，假设只传了 "1:1"，则需要结合 sizeVal
                if (targetSizeKey == null) {
                    targetRatioKey = arClean
                }
            }
            
            // 如果 aspectRatio 里没包含档位，尝试从 sizeVal 解析
            if (targetSizeKey == null) {
                targetSizeKey = when {
                    sizeVal in listOf("2k", "4k") -> "2k"
                    sizeVal in listOf("hd", "1k", "1024x1024") -> "hd"
                    else -> "2k" // 默认为 2K 档位
                }
            }
            
            if (targetRatioKey.isNullOrBlank()) {
                targetRatioKey = "1:1"
            }
            
            // 查找映射
            modalSizeMap[Pair(targetSizeKey, targetRatioKey)]?.let { (w, h) ->
                width = w
                height = h
            }
            
            // 如果没找到精确映射（例如传了 HD 4:3），回退逻辑
            if (width == 1024 && height == 1024 && targetSizeKey == "hd" && targetRatioKey == "4:3") {
                // HD 不支持 4:3，回退到 HD 1:1
                modalSizeMap[Pair("hd", "1:1")]?.let { (w, h) ->
                    width = w
                    height = h
                }
                Log.w(TAG, "Modal HD 4:3 not supported, fallback to HD 1:1")
            }
            
            Log.i(TAG, "Modal 尺寸解析: '$aspectRatio' + '$sizeVal' -> $width x $height")
            
        } catch (e: Exception) {
            Log.w(TAG, "Modal 尺寸解析失败，使用默认 1024x1024", e)
        }
        
        // 对齐到 8 的倍数
        width = (width / 8) * 8
        height = (height / 8) * 8
        width = maxOf(256, minOf(width, 2048))
        height = maxOf(256, minOf(height, 2048))
        
        val steps = request.numInferenceSteps ?: 4
        val prompt = request.prompt
        
        if (prompt.isBlank()) {
            throw Exception("提示词为空，无法生成图像")
        }
        
        var lastError: Exception? = null
        val startTime = System.currentTimeMillis()
        
        // 轮询多个 Modal URL
        for ((idx, modalUrl) in modalUrls.withIndex()) {
            try {
                Log.i(TAG, "尝试 Modal URL ${idx + 1}/${modalUrls.size}: $modalUrl")
                Log.d(TAG, "请求参数: prompt='${prompt.take(50)}...', width=$width, height=$height, steps=$steps")
                
                val response = client.get(modalUrl) {
                    parameter("prompt", prompt)
                    parameter("width", width)
                    parameter("height", height)
                    parameter("steps", steps)
                    timeout {
                        requestTimeoutMillis = 1800_000
                        connectTimeoutMillis = 60_000
                        socketTimeoutMillis = 1800_000
                    }
                }
                
                if (!response.status.isSuccess()) {
                    val errorText = try { response.bodyAsText().take(500) } catch (_: Exception) { "(empty)" }
                    Log.w(TAG, "Modal URL ${idx + 1} 返回 ${response.status}: $errorText")
                    lastError = Exception("HTTP ${response.status}: $errorText")
                    continue
                }
                
                // 获取 JPEG 字节流
                val jpegBytes = response.readRawBytes()
                if (jpegBytes.isEmpty()) {
                    Log.w(TAG, "Modal URL ${idx + 1} 返回空内容")
                    lastError = Exception("Empty response body")
                    continue
                }
                
                // 转为 Data URI
                val b64Str = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                val dataUri = "data:image/jpeg;base64,$b64Str"
                
                val elapsedMs = System.currentTimeMillis() - startTime
                Log.i(TAG, "✓ Modal 图像生成成功，大小=${jpegBytes.size} bytes，耗时=${elapsedMs}ms")
                
                return ImageGenerationResponse(
                    images = listOf(ImageUrl(url = dataUri)),
                    text = prompt,
                    timings = Timings(inference = elapsedMs.toInt()),
                    seed = Random.nextInt()
                )
                
            } catch (e: Exception) {
                Log.w(TAG, "Modal URL ${idx + 1} 异常: ${e.message}")
                lastError = e
                continue
            }
        }
        
        throw lastError ?: Exception("所有 Modal URL 均失败")
    }

    /**
     * 直连 Qwen 图像编辑 API
     * 基于输入图片进行编辑
     */
    suspend fun generateImageQwenEdit(
        client: HttpClient,
        request: ImageGenRequest,
        inputImageBase64: String,
        qwenUrls: List<String>,
        apiSecret: String
    ): ImageGenerationResponse {
        Log.i(TAG, "🔄 启动 Qwen 图像编辑直连模式")
        
        if (qwenUrls.isEmpty()) {
            throw Exception("Qwen Edit API URL 列表为空")
        }
        
        if (inputImageBase64.isBlank()) {
            throw Exception("请提供一张图片以进行编辑")
        }
        
        val prompt = request.prompt.takeIf { it.isNotBlank() } ?: "Edit this image"
        val steps = request.numInferenceSteps ?: 30
        val guidanceScale = request.guidanceScale ?: 7.5f
        
        var lastError: Exception? = null
        val startTime = System.currentTimeMillis()
        
        for ((idx, qwenUrl) in qwenUrls.withIndex()) {
            try {
                Log.i(TAG, "尝试 Qwen URL ${idx + 1}/${qwenUrls.size}: $qwenUrl")
                
                val payload = buildJsonObject {
                    put("image_base64", inputImageBase64)
                    put("prompt", prompt)
                    put("steps", steps)
                    put("guidance_scale", guidanceScale)
                }.toString()
                
                val response = client.post(qwenUrl) {
                    contentType(ContentType.Application.Json)
                    header("x-api-key", apiSecret)
                    setBody(payload)
                    timeout {
                        requestTimeoutMillis = 1800_000
                        connectTimeoutMillis = 60_000
                        socketTimeoutMillis = 1800_000
                    }
                }
                
                if (!response.status.isSuccess()) {
                    val errorText = try { response.bodyAsText().take(500) } catch (_: Exception) { "(empty)" }
                    Log.w(TAG, "Qwen URL ${idx + 1} 返回 ${response.status}: $errorText")
                    lastError = Exception("HTTP ${response.status}: $errorText")
                    continue
                }
                
                val responseJson = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                
                if (responseJson["status"]?.jsonPrimitive?.contentOrNull != "success") {
                    val detail = responseJson["detail"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                    Log.w(TAG, "Qwen URL ${idx + 1} 状态非 success: $detail")
                    lastError = Exception("API Error: $detail")
                    continue
                }
                
                val resultB64 = responseJson["image_base64"]?.jsonPrimitive?.contentOrNull
                if (resultB64.isNullOrBlank()) {
                    lastError = Exception("Empty image_base64 in response")
                    continue
                }
                
                val elapsedMs = System.currentTimeMillis() - startTime
                val dataUri = "data:image/png;base64,$resultB64"
                
                Log.i(TAG, "✓ Qwen 图像编辑成功，耗时=${elapsedMs}ms")
                
                return ImageGenerationResponse(
                    images = listOf(ImageUrl(url = dataUri)),
                    text = prompt,
                    timings = Timings(inference = elapsedMs.toInt()),
                    seed = Random.nextInt()
                )
                
            } catch (e: Exception) {
                Log.w(TAG, "Qwen URL ${idx + 1} 异常: ${e.message}")
                lastError = e
                continue
            }
        }
        
        throw lastError ?: Exception("所有 Qwen URL 均失败")
    }

    /**
     * 增强版 Gemini 图像生成，支持参考图编辑 (img2img)
     */
    suspend fun generateImageGeminiWithReference(
        client: HttpClient,
        request: ImageGenRequest,
        referenceImageBase64: String?,
        referenceImageMimeType: String = "image/png"
    ): ImageGenerationResponse {
        Log.i(TAG, "🔄 启动 Gemini 图像生成（带参考图）直连模式")
        
        val rawAddress = request.apiAddress.trimEnd('/').takeIf { it.isNotBlank() }
            ?: com.android.everytalk.BuildConfig.GOOGLE_API_BASE_URL.trimEnd('/').takeIf { it.isNotBlank() }
            ?: "https://generativelanguage.googleapis.com"

        // 清理可能存在的 OpenAI 风格路径，提取纯基础 URL
        val baseUrl = rawAddress
            .replace(Regex("/v1/images/generations.*$"), "")
            .replace(Regex("/v1beta/models.*$"), "")
            .replace(Regex("/v1/.*$"), "")
            .trimEnd('/')

        val model = request.model
        val url = "$baseUrl/v1beta/models/$model:generateContent?key=${request.apiKey}"
        
        Log.d(TAG, "直连 URL: ${url.substringBefore("?key=")}")
        
        val payload = buildGeminiImagePayloadWithReference(request, referenceImageBase64, referenceImageMimeType)
        
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        
        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
            Log.e(TAG, "Gemini 图像生成错误 ${response.status}: $errorBody")
            throw Exception("Gemini 图像生成错误 ${response.status}: $errorBody")
        }
        
        return parseGeminiImageResponse(response.bodyAsText())
    }
    
    private fun buildGeminiImagePayloadWithReference(
        request: ImageGenRequest,
        referenceImageBase64: String?,
        referenceImageMimeType: String
    ): String {
        return buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        // 先添加文本提示
                        addJsonObject {
                            put("text", request.prompt)
                        }
                        // 如果有参考图，添加到 parts
                        if (!referenceImageBase64.isNullOrBlank()) {
                            addJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", referenceImageMimeType)
                                    put("data", referenceImageBase64)
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") {
                    add("IMAGE")
                    add("TEXT")
                }
                // 构建 imageConfig（aspectRatio + imageSize）
                val supportedRatios = setOf(
                    "1:1", "2:3", "3:2", "3:4", "4:3",
                    "4:5", "5:4", "9:16", "16:9", "21:9"
                )
                val hasValidAspectRatio = request.aspectRatio?.let { it in supportedRatios } == true
                // 恢复限制：仅 Gemini 3 Pro Image 支持 imageSize 参数
                // Gemini 2.5 Flash Image 收到此参数会报 400 INVALID_ARGUMENT
                val isGemini3Pro = ImageGenCapabilities.isGemini3ProImage(request.model)
                val hasImageSize = isGemini3Pro && !request.geminiImageSize.isNullOrBlank()

                if (hasValidAspectRatio || hasImageSize) {
                    putJsonObject("imageConfig") {
                        if (hasValidAspectRatio) {
                            put("aspectRatio", request.aspectRatio!!)
                        }
                        if (hasImageSize) {
                            put("imageSize", request.geminiImageSize!!)
                        }
                    }
                } else if (!request.aspectRatio.isNullOrBlank() && !hasValidAspectRatio) {
                    Log.w(TAG, "Gemini 不支持宽高比 '${request.aspectRatio}'，已忽略该参数（将使用默认 1:1）")
                }
            }
        }.toString()
    }

    /**
     * 增强版 Seedream 图像生成，支持参考图编辑
     */
    suspend fun generateImageSeedreamWithReference(
        client: HttpClient,
        request: ImageGenRequest,
        referenceImages: List<String>  // Data URI 或 URL 列表
    ): ImageGenerationResponse {
        Log.i(TAG, "🔄 启动 Seedream 图像生成（带参考图）直连模式")
        
        // 清理 URL：移除尾部的 '#' 和 '/v1/images/generations' 等多余路径
        val rawUrl = request.apiAddress
            .trimEnd('/')
            .removeSuffix("#")
            .removeSuffix("/v1/images/generations")
            .removeSuffix("#")
            .trimEnd('/')
        
        val baseUrl = rawUrl.takeIf { it.isNotBlank() }
            ?: "https://ark.cn-beijing.volces.com/api/v3/images/generations"
            
        Log.d(TAG, "直连 URL: $baseUrl")
        
        val payload = buildSeedreamPayloadWithReference(request, referenceImages)
        
        val response = client.post(baseUrl) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${request.apiKey}")
            setBody(payload)
        }
        
        if (!response.status.isSuccess()) {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "(no body)" }
            Log.e(TAG, "Seedream 图像生成错误 ${response.status}: $errorBody")
            throw Exception("Seedream 图像生成错误 ${response.status}: $errorBody")
        }
        
        return parseOpenAIImageResponse(response.bodyAsText())
    }
    
    private fun buildSeedreamPayloadWithReference(
        request: ImageGenRequest,
        referenceImages: List<String>
    ): String {
        return buildJsonObject {
            put("model", request.model)
            put("prompt", request.prompt)
            put("response_format", "url")
            // 显式关闭水印，强制默认为 false，除非明确为 true
            // 注意：Seedream API 默认值为 true，所以必须显式传 false
            val enableWatermark = request.watermark == true
            put("watermark", enableWatermark)
            Log.d(TAG, "Seedream watermark set to: $enableWatermark (req=${request.watermark})")
            
            val sizeVal = request.imageSize?.takeIf { it.isNotBlank() } ?: "2k"
            put("size", mapSeedreamSize(sizeVal, request.aspectRatio))
            
            // 添加参考图片
            if (referenceImages.isNotEmpty()) {
                putJsonArray("image") {
                    referenceImages.forEach { imageUrl ->
                        add(imageUrl)
                    }
                }
                Log.i(TAG, "添加 ${referenceImages.size} 张参考图")
            }
        }.toString()
    }
    
    private fun buildGeminiImagePayload(request: ImageGenRequest): String {
        return buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", request.prompt)
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                // 指定输出图片
                putJsonArray("responseModalities") {
                    add("IMAGE")
                    add("TEXT")
                }
                // 宽高比 + 尺寸配置
                val supportedRatios = setOf(
                    "1:1", "2:3", "3:2", "3:4", "4:3",
                    "4:5", "5:4", "9:16", "16:9", "21:9"
                )
                val hasValidAspectRatio = request.aspectRatio?.let { it in supportedRatios } == true
                // 恢复限制：仅 Gemini 3 Pro Image 支持 imageSize 参数
                val isGemini3Pro = ImageGenCapabilities.isGemini3ProImage(request.model)
                val hasImageSize = isGemini3Pro && !request.geminiImageSize.isNullOrBlank()

                if (hasValidAspectRatio || hasImageSize) {
                    putJsonObject("imageConfig") {
                        if (hasValidAspectRatio) {
                            put("aspectRatio", request.aspectRatio!!)
                        }
                        if (hasImageSize) {
                            put("imageSize", request.geminiImageSize!!)
                        }
                    }
                } else if (!request.aspectRatio.isNullOrBlank() && !hasValidAspectRatio) {
                    Log.w(TAG, "Gemini 不支持宽高比 '${request.aspectRatio}'，已忽略该参数（将使用默认 1:1）")
                }
            }
        }.toString()
    }
    
    private fun buildOpenAIImagePayload(request: ImageGenRequest): String {
        return buildJsonObject {
            put("model", request.model)
            put("prompt", request.prompt)
            request.imageSize?.let { put("size", it) }
            request.batchSize?.let { put("n", it) }
            request.numInferenceSteps?.let { put("num_inference_steps", it) }
            request.guidanceScale?.let { put("guidance_scale", it) }
        }.toString()
    }

    private fun extensionForMime(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/jpeg", "image/jpg" -> "jpg"
        else -> "png"
    }

    private fun buildSeedreamPayload(request: ImageGenRequest): String {
        return buildJsonObject {
            put("model", request.model)
            put("prompt", request.prompt)
            put("response_format", "url")
            // 显式关闭水印，强制默认为 false
            val enableWatermark = request.watermark == true
            put("watermark", enableWatermark)
            Log.d(TAG, "Seedream watermark set to: $enableWatermark (req=${request.watermark})")
            
            // 尺寸处理：优先使用 size 字段，其次是 imageSize
            // Seedream 支持 "2K", "4K" 或 "WxH"
            // 默认使用 2K 而非 1024x1024，以避免低清默认
            val sizeVal = request.imageSize?.takeIf {
                it.isNotBlank() && it != "1024x1024"
            } ?: "2K"
            put("size", mapSeedreamSize(sizeVal, request.aspectRatio))
            
            // 如果是流式
            // put("stream", false)
        }.toString()
    }

    private fun mapSeedreamSize(size: String, aspectRatio: String?): String {
        var s = size.lowercase().trim()
        
        // 检查是否为具体像素值，如果像素过小（小于 2K 级别的 3,686,400），强制升级为 2K 档位
        // 这是为了兼容 Seedream 4.5 等高分辨率模型
        if (s.contains("x")) {
            val parts = s.split("x")
            if (parts.size == 2) {
                val w = parts[0].toIntOrNull() ?: 0
                val h = parts[1].toIntOrNull() ?: 0
                // 3686400 是 2560x1440 的像素数，也是 Seedream 4.5 的推荐最小值
                if (w * h < 3686400) {
                    s = "2k"
                } else {
                    return s
                }
            }
        }

        // 如果不是 4k，默认都视为 2k 处理（包括 1k、hd、或者被强制升级的低像素值）
        if (s != "4k") {
            s = "2k"
        }

        // 根据官方文档适配 Seedream 2K/4K 分辨率
        return when (s) {
            "2k" -> when (aspectRatio) {
                "1:1" -> "2048x2048"
                "4:3" -> "2304x1728"
                "3:4" -> "1728x2304"
                "16:9" -> "2560x1440"
                "9:16" -> "1440x2560"
                "3:2" -> "2496x1664"
                "2:3" -> "1664x2496"
                "21:9" -> "3024x1296"
                else -> "2048x2048"
            }
            "4k" -> when (aspectRatio) {
                "1:1" -> "4096x4096"
                "4:3" -> "4608x3456"
                "3:4" -> "3456x4608"
                "16:9" -> "5120x2880"
                "9:16" -> "2880x5120"
                "3:2" -> "4992x3328"
                "2:3" -> "3328x4992"
                "21:9" -> "6048x2592"
                else -> "4096x4096"
            }
            else -> "2048x2048"
        }
    }
    
    private fun parseGeminiImageResponse(responseText: String): ImageGenerationResponse {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(responseText).jsonObject
        val images = mutableListOf<ImageUrl>()
        var text: String? = null
        
        root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.let { candidate ->
            candidate["content"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                val partObj = part.jsonObject
                // 提取文本
                partObj["text"]?.jsonPrimitive?.contentOrNull?.let { t ->
                    text = t
                }
                // 提取图片
                partObj["inlineData"]?.jsonObject?.let { inlineData ->
                    val mimeType = inlineData["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                    val data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                    if (data.isNotEmpty()) {
                        images.add(ImageUrl(url = "data:$mimeType;base64,$data"))
                        Log.i(TAG, "✓ 解析到 Gemini 图片 (${data.length} chars base64)")
                    }
                }
            }
        }
        
        Log.i(TAG, "Gemini 图像生成完成: ${images.size} 张图片")
        return ImageGenerationResponse(
            images = images,
            text = text,
            timings = Timings(inference = 0),
            seed = 0
        )
    }
    
    private fun parseOpenAIImageResponse(responseText: String): ImageGenerationResponse {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(responseText).jsonObject
        val images = mutableListOf<ImageUrl>()
        
        root["data"]?.jsonArray?.forEach { item ->
            val itemObj = item.jsonObject
            val url = itemObj["url"]?.jsonPrimitive?.contentOrNull
                ?: itemObj["b64_json"]?.jsonPrimitive?.contentOrNull?.let { "data:image/png;base64,$it" }
            if (url != null) {
                images.add(ImageUrl(url = url))
                Log.i(TAG, "✓ 解析到 OpenAI 图片")
            }
        }
        
        Log.i(TAG, "OpenAI 图像生成完成: ${images.size} 张图片")
        return ImageGenerationResponse(
            images = images,
            text = null,
            timings = Timings(inference = 0),
            seed = 0
        )
    }
}
