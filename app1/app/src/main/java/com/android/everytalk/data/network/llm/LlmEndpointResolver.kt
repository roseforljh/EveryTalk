package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ModelParameterProtocol
import java.net.URI

/**
 * 四种文本协议共用的请求地址解析器。
 *
 * 地址既可以填写域名、带代理前缀的 Base URL，也可以填写标准完整接口地址。
 * 地址末尾加 `#` 时按完整自定义地址原样使用，兼容没有标准路径的私有代理。
 */
object LlmEndpointResolver {
    fun resolve(
        protocol: ModelParameterProtocol,
        apiAddress: String,
        model: String,
    ): String {
        val raw = apiAddress.trim()
        if (raw.isEmpty()) return ""

        val useExactAddress = raw.endsWith('#')
        val withoutMarker = raw.removeSuffix("#").trim()
        val queryIndex = withoutMarker.indexOf('?')
        val address = (if (queryIndex >= 0) withoutMarker.substring(0, queryIndex) else withoutMarker)
            .trimEnd('/')
            .withHttpScheme()
        val query = if (queryIndex >= 0) withoutMarker.substring(queryIndex) else ""
        if (useExactAddress) return address + query

        val base = address.normalizeKnownProviderBase().removeKnownEndpoint()
        val endpoint = when (protocol) {
            ModelParameterProtocol.CODEX -> base.appendVersionedPath("responses")
            ModelParameterProtocol.OPENAI_COMPATIBLE -> base.appendVersionedPath("chat/completions")
            ModelParameterProtocol.ANTHROPIC -> base.appendVersionedPath("messages")
            ModelParameterProtocol.GEMINI -> base.appendGeminiPath(model)
        }
        return endpoint + query
    }

    private fun String.withHttpScheme(): String =
        if (startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)) {
            this
        } else {
            "https://$this"
        }

    /** 仅补齐官方服务明确要求的固定 Base Path，用户填写的自定义路径始终优先。 */
    private fun String.normalizeKnownProviderBase(): String {
        val uri = runCatching { URI(this) }.getOrNull() ?: return this
        if (
            uri.host.equals("open.bigmodel.cn", ignoreCase = true) &&
            (uri.path.isNullOrEmpty() || uri.path == "/")
        ) {
            return "$this/api/paas/v4"
        }
        return this
    }

    private fun String.appendVersionedPath(path: String): String =
        if (hasVersionPathSuffix()) "$this/$path" else "$this/v1/$path"

    /** 兼容 `/v1`、`/api/v3`、`/api/paas/v4` 这类已经包含版本号的代理 Base URL。 */
    private fun String.hasVersionPathSuffix(): Boolean {
        val segment = substringAfterLast('/').lowercase()
        return segment.length > 1 && segment.first() == 'v' && segment.drop(1).all(Char::isDigit)
    }

    private fun String.appendGeminiPath(model: String): String {
        val encodedModel = model.trim().removePrefix("models/").ifBlank { "{model}" }
        return when {
            endsWith("/v1beta/models", ignoreCase = true) ->
                "$this/$encodedModel:streamGenerateContent"
            endsWith("/v1beta", ignoreCase = true) ->
                "$this/models/$encodedModel:streamGenerateContent"
            else -> "$this/v1beta/models/$encodedModel:streamGenerateContent"
        }
    }

    private fun String.removeKnownEndpoint(): String {
        val lower = lowercase()
        val geminiModelsIndex = lower.lastIndexOf("/v1beta/models/")
        if (geminiModelsIndex >= 0) {
            val operation = lower.substring(geminiModelsIndex).substringAfter(':', missingDelimiterValue = "")
            if (operation == "streamgeneratecontent" || operation == "generatecontent") {
                return substring(0, geminiModelsIndex).trimEnd('/')
            }
        }

        val knownSuffixes = listOf(
            "/v1/chat/completions",
            "/chat/completions",
            "/v1/responses",
            "/responses",
            "/v1/completions",
            "/completions",
            "/v1/messages",
            "/messages",
            "/v1beta/models",
            "/v1beta",
        )
        val suffix = knownSuffixes.firstOrNull { lower.endsWith(it) } ?: return this
        return dropLast(suffix.length).trimEnd('/')
    }
}
