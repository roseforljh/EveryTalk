package com.android.everytalk.ui.components

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * 将一份已生成的预览文档作为网页提供给外部浏览器，内容只保留在内存。
 * 仅绑定 IPv4 回环地址，随机路径限制其他页面访问；只允许读取模板实际使用的图表脚本。
 * 关闭应用进程或 30 分钟后网址失效；不随对话框销毁关闭，否则切换浏览器时页面会无法加载。
 */
internal class ExternalWebPreviewServer(
    document: String,
    private val loadAsset: (String) -> ByteArray,
) : Closeable {
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val authority = "127.0.0.1:${listener.localPort}"
    private val pathPrefix = "/${UUID.randomUUID()}/"
    private val documentBytes = document.toByteArray(Charsets.UTF_8)
    val url: String = "http://$authority${pathPrefix}templates/preview.html"

    init {
        thread(name = "external-code-preview", isDaemon = true) {
            val deadline = System.nanoTime() + LIFETIME_MILLIS * 1_000_000
            try {
                // ponytail: 顺序提供静态资源，避免为单页预览引入服务器依赖或线程池。
                while (!listener.isClosed) {
                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                    if (remaining <= 0) break
                    listener.soTimeout = remaining.toInt()
                    try {
                        listener.accept().use { socket -> serve(socket) }
                    } catch (_: SocketTimeoutException) {
                        // 请求超时后继续接收；总时限在下一次循环检查。
                    } catch (_: java.io.IOException) {
                        // 浏览器中断连接不影响后续刷新；主动关闭监听器则由循环退出。
                    }
                }
            } finally {
                close()
            }
        }
    }

    /** 只处理有限大小的 GET/HEAD 请求；拒绝错误 Host、未知路径及目录穿越。 */
    private fun serve(socket: Socket) {
        socket.soTimeout = 5_000
        val input = socket.getInputStream()
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next < 0 || header.length >= 8_192) return
            header.append(next.toChar())
        }
        val lines = header.toString().split("\r\n")
        val request = lines.first().split(' ')
        val method = request.getOrNull(0)
        val target = request.getOrNull(1)?.substringBefore('?').orEmpty()
        val hosts = lines.drop(1).filter { it.startsWith("Host:", ignoreCase = true) }
        val allowedRequest = request.size == 3 && method in setOf("GET", "HEAD") &&
            hosts.size == 1 && hosts.single().substringAfter(':').trim() == authority &&
            target.startsWith(pathPrefix)
        val path = target.removePrefix(pathPrefix)
        val body = when {
            !allowedRequest -> null
            path == "templates/preview.html" -> documentBytes
            path in SCRIPT_PATHS -> loadAsset(path)
            else -> null
        }
        val status = if (body == null) "404 Not Found" else "200 OK"
        val bytes = body ?: ByteArray(0)
        val contentType = if (path == "templates/preview.html") "text/html; charset=utf-8" else "text/javascript; charset=utf-8"
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $status\r\nContent-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            if (method != "HEAD") write(bytes)
            flush()
        }
    }

    override fun close() {
        listener.close()
        synchronized(Companion) {
            // 超时或打开失败时一并释放静态引用，避免已关闭的预览继续占用文档内存。
            if (current === this) current = null
        }
    }

    companion object {
        private const val LIFETIME_MILLIS = 30 * 60 * 1_000L
        private val SCRIPT_PATHS = setOf(
            "mermaid/mermaid.min.js", "echarts/echarts.min.js", "chartjs/chart.min.js",
            "flowchart/raphael.min.js", "flowchart/flowchart.min.js",
            "vega/vega.min.js", "vega/vega-lite.min.js", "vega/vega-embed.min.js",
        )
        private var current: ExternalWebPreviewServer? = null

        /** 仅保留最近打开的预览，重复点击时释放旧端口和文档。必须在 IO 线程调用。 */
        @Synchronized
        fun open(document: String, loadAsset: (String) -> ByteArray): ExternalWebPreviewServer {
            val next = ExternalWebPreviewServer(document, loadAsset)
            current?.close()
            current = next
            return next
        }
    }
}
