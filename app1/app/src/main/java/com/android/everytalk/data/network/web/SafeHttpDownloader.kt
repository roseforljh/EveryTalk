package com.android.everytalk.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object SafeHttpDownloader {
    private const val MAX_REDIRECTS = 5

    private val sharedClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    data class DownloadedBody(
        val bytes: ByteArray,
        val contentType: String,
        val finalUrl: String,
    )

    suspend fun download(
        url: String,
        maxBytes: Long,
        timeoutMillis: Int,
        accept: String = "*/*",
        headers: Map<String, String> = emptyMap(),
        trustedOrigin: String? = null,
    ): DownloadedBody = withContext(Dispatchers.IO) {
        require(maxBytes in 1..Int.MAX_VALUE.toLong()) { "下载大小上限无效" }
        require(timeoutMillis > 0) { "下载超时必须大于 0" }
        require(headers.size <= 16) { "请求头数量超过上限" }
        require(headers.all { (name, value) -> name.length <= 64 && value.length <= 8192 }) { "请求头过长" }

        var currentUrl = parseHttpUrl(url)
        val trustedOriginUrl = parseTrustedOrigin(trustedOrigin)
        var currentHeaders = headersForTarget(headers, trustedOriginUrl, currentUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val allowPrivateAddresses = trustedOriginUrl?.let { sameOrigin(it, currentUrl) } == true
            val addresses = resolveAddresses(currentUrl.host, allowPrivateAddresses)
            val pinnedClient = sharedClient.newBuilder()
                .dns(PinnedDns(currentUrl.host, addresses))
                .callTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                .build()
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", "EveryTalk/1.0 (Android)")
                .header("Accept", accept)
            currentHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }
            val request = requestBuilder.build()

            val response = pinnedClient.newCall(request).await()
            response.use {
                if (it.code in 300..399) {
                    if (redirectCount == MAX_REDIRECTS) throw IOException("重定向次数超过上限")
                    val location = it.header("Location") ?: throw IOException("重定向响应缺少 Location")
                    val redirectedUrl = currentUrl.resolve(location) ?: throw IOException("重定向地址无效")
                    currentHeaders = headersForTarget(currentHeaders, trustedOriginUrl, redirectedUrl)
                    currentUrl = redirectedUrl
                    return@repeat
                }
                if (!it.isSuccessful) throw IOException("HTTP ${it.code}")

                val body = it.body
                val declaredLength = body.contentLength()
                if (declaredLength > maxBytes) throw ResponseTooLargeException(maxBytes)
                return@withContext DownloadedBody(
                    bytes = readAtMost(body.byteStream(), maxBytes),
                    contentType = body.contentType()?.toString().orEmpty().ifBlank { "application/octet-stream" },
                    finalUrl = currentUrl.toString(),
                )
            }
        }
        throw IOException("重定向次数超过上限")
    }

    internal fun resolvePublicAddresses(host: String): List<InetAddress> {
        return resolveAddresses(host, allowPrivate = false)
    }

    internal fun resolveAddresses(host: String, allowPrivate: Boolean): List<InetAddress> {
        return requireAddresses(InetAddress.getAllByName(host).toList(), allowPrivate)
    }

    internal fun requirePublicAddresses(addresses: List<InetAddress>): List<InetAddress> {
        return requireAddresses(addresses, allowPrivate = false)
    }

    internal fun requireAddresses(addresses: List<InetAddress>, allowPrivate: Boolean): List<InetAddress> {
        if (addresses.isEmpty() || (!allowPrivate && addresses.any { !isPublicAddress(it) })) {
            throw UnknownHostException("目标地址不允许访问")
        }
        return addresses
    }

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false

        val bytes = address.address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            val third = bytes[2].toInt() and 0xff
            if (first == 0 || first == 10 || first == 127 || first >= 224) return false
            if (first == 100 && second in 64..127) return false
            if (first == 169 && second == 254) return false
            if (first == 172 && second in 16..31) return false
            if (first == 192 && second == 168) return false
            if (first == 192 && second == 0 && (third == 0 || third == 2)) return false
            if (first == 192 && second == 88 && third == 99) return false
            if (first == 198 && second in 18..19) return false
            if (first == 198 && second == 51 && third == 100) return false
            if (first == 203 && second == 0 && third == 113) return false
        } else if (address is Inet6Address) {
            val first = bytes[0].toInt() and 0xff
            if (first and 0xfe == 0xfc) return false
            if (hasIpv6Prefix(bytes, intArrayOf(0x20, 0x01, 0x00, 0x00), 32)) return false // Teredo
            if (hasIpv6Prefix(bytes, intArrayOf(0x20, 0x01, 0x00, 0x02, 0x00, 0x00), 48)) return false // 基准测试
            if (hasIpv6Prefix(bytes, intArrayOf(0x20, 0x01, 0x0d, 0xb8), 32)) return false // 文档地址
            if (hasIpv6Prefix(bytes, intArrayOf(0x20, 0x02), 16)) return false // 6to4
            if (hasIpv6Prefix(bytes, intArrayOf(0x3f, 0xff, 0x00), 20)) return false // 文档地址
        }
        return true
    }

    private fun hasIpv6Prefix(address: ByteArray, prefix: IntArray, prefixBits: Int): Boolean {
        val fullBytes = prefixBits / 8
        for (index in 0 until fullBytes) {
            if ((address[index].toInt() and 0xff) != prefix[index]) return false
        }
        val remainingBits = prefixBits % 8
        if (remainingBits == 0) return true
        val mask = 0xff shl (8 - remainingBits) and 0xff
        return (address[fullBytes].toInt() and mask) == (prefix[fullBytes] and mask)
    }

    internal fun readAtMost(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192L).toInt())
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw ResponseTooLargeException(maxBytes)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parseHttpUrl(url: String): HttpUrl {
        val parsed = url.toHttpUrlOrNull() ?: throw IOException("URL 无效")
        if (parsed.scheme !in setOf("http", "https") || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw IOException("仅允许无凭据的 HTTP(S) URL")
        }
        return parsed
    }

    private fun parseTrustedOrigin(url: String?): HttpUrl? {
        if (url.isNullOrBlank()) return null
        return url.toHttpUrlOrNull()?.takeIf {
            it.scheme in setOf("http", "https") && it.username.isEmpty() && it.password.isEmpty()
        }
    }

    private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme && first.host.equals(second.host, ignoreCase = true) && first.port == second.port

    internal fun isSensitiveHeader(name: String): Boolean {
        val normalized = name.trim().lowercase()
        return normalized in setOf("authorization", "proxy-authorization", "cookie", "cookie2", "x-api-key", "api-key") ||
            normalized.endsWith("-key") || normalized.contains("token") || normalized.contains("secret")
    }

    internal fun headersForTarget(
        headers: Map<String, String>,
        trustedOrigin: HttpUrl?,
        target: HttpUrl,
    ): Map<String, String> {
        if (trustedOrigin != null && sameOrigin(trustedOrigin, target)) return headers
        return headers.filterKeys { name -> !isSensitiveHeader(name) }
    }

    private class PinnedDns(
        private val host: String,
        private val addresses: List<InetAddress>,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (!hostname.equals(host, ignoreCase = true)) throw UnknownHostException("DNS 主机不匹配")
            return addresses
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, cancelledResponse, _ ->
                    cancelledResponse.close()
                }
            }
        })
    }

    class ResponseTooLargeException(maxBytes: Long) : IOException("响应超过 ${maxBytes} 字节上限")
}
