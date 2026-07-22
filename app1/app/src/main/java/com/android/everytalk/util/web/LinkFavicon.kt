package com.android.everytalk.util.web

import java.net.URI
import java.util.Locale

private const val LINK_FAVICON_ENDPOINT = "https://www.google.com/s2/favicons?domain="

/**
 * 返回外部网页链接的规范化 host。仅接受 http 和 https，避免为内部 scheme 请求图标。
 */
fun linkHost(href: String): String = runCatching {
    val uri = URI(href.trim())
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return@runCatching ""
    uri.host
        ?.lowercase(Locale.ROOT)
        ?.removePrefix("www.")
        ?.trimEnd('.')
        .orEmpty()
}.getOrDefault("")

/**
 * 为外部网页链接生成 favicon 地址。只把 host 发送给图标服务。
 */
fun linkFaviconUrl(href: String): String {
    val host = linkHost(href)
    return if (host.isBlank()) "" else "$LINK_FAVICON_ENDPOINT$host&sz=64"
}

/**
 * 当 favicon 不可用时提供稳定的首字母占位符。
 */
fun linkFaviconInitial(href: String, fallback: String = "?"): String {
    val raw = linkHost(href).ifBlank { fallback }.trim()
    return raw.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}
