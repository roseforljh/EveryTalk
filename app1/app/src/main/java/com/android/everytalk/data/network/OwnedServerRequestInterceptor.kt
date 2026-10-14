package com.android.everytalk.data.network

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import java.net.URI

/** 自有服务器请求用途；第三方模型直连不经过这个门禁。 */
internal enum class OwnedServerRequestPurpose {
    AUTH,
    COMMERCIAL,
    SYNC,
    BACKGROUND_REPORT,
}

internal class LocalOnlyServerRequestException(url: String) :
    IllegalStateException("LocalOnly 禁止访问 EveryTalk 自有服务器：$url")

/**
 * Ktor 客户端插件：LocalOnly 下拒绝所有非显式认证的自有服务器请求。
 * 认证请求只有在用户主动点击登录后才允许，满足“本地使用”和“登录解锁”两个产品流程。
 */
internal fun ownedServerRequestGuard(
    isLocalOnly: () -> Boolean,
    isOwnedServer: (URI) -> Boolean,
    purpose: (HttpRequestBuilder) -> OwnedServerRequestPurpose,
) = createClientPlugin("EveryTalkOwnedServerGuard") {
    onRequest { request, _ ->
        val url = request.url.buildString()
        val uri = runCatching { URI(url) }.getOrNull() ?: return@onRequest
        if (isLocalOnly() && isOwnedServer(uri) && purpose(request) != OwnedServerRequestPurpose.AUTH) {
            throw LocalOnlyServerRequestException(url)
        }
    }
}
