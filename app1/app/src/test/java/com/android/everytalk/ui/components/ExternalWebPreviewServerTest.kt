package com.android.everytalk.ui.components

import java.net.Socket
import java.net.URI
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalWebPreviewServerTest {
    /** 用真实 HTTP 连接核对返回内容，避免只检查拼接网址而漏掉下载或资源错误。 */
    private fun request(url: String, method: String = "GET", host: String? = null): String {
        val uri = URI(url)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(
                "$method ${uri.rawPath} HTTP/1.1\r\nHost: ${host ?: uri.rawAuthority}\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test
    fun `preview is served inline with unicode and original scripts`() {
        val html = "<!doctype html><html><head><style>body{color:red}</style></head><body>中文<script>window.ready=true</script></body></html>"
        ExternalWebPreviewServer(html) { error("不应读取资源") }.use { server ->
            val response = request(server.url)
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("Content-Type: text/html; charset=utf-8"))
            assertTrue(response.contains("Content-Length: ${html.toByteArray(Charsets.UTF_8).size}\r\n"))
            assertTrue(response.contains("Cache-Control: no-store"))
            assertFalse(response.contains("Content-Disposition:"))
            assertEquals(html, response.substringAfter("\r\n\r\n"))
            assertEquals("", request(server.url, "HEAD").substringAfter("\r\n\r\n"))
        }
    }

    @Test
    fun `relative chart assets work while other paths and hosts are rejected`() {
        val loaded = mutableListOf<String>()
        ExternalWebPreviewServer("<script src='../mermaid/mermaid.min.js'></script>") {
            loaded.add(it)
            "window.chartLoaded=true;".toByteArray()
        }.use { server ->
            val scriptUrl = URI(server.url).resolve("../mermaid/mermaid.min.js").toString()
            assertEquals("window.chartLoaded=true;", request(scriptUrl).substringAfter("\r\n\r\n"))
            assertTrue(request(server.url, host = "untrusted.example").startsWith("HTTP/1.1 404"))
            assertTrue(request(server.url, "POST").startsWith("HTTP/1.1 404"))
            listOf("/templates/preview.html", "/wrong/templates/preview.html", "../private.txt", "../%2e%2e/private.txt").forEach {
                assertTrue(request(URI(server.url).resolve(it).toString()).startsWith("HTTP/1.1 404"))
            }
            assertEquals(listOf("mermaid/mermaid.min.js"), loaded)
        }
    }

    @Test
    fun `opening a new preview serves the new document and closes the old listener`() {
        val first = ExternalWebPreviewServer.open("first") { byteArrayOf() }
        val firstUrl = first.url
        ExternalWebPreviewServer.open("second") { byteArrayOf() }.use { second ->
            assertEquals("second", request(second.url).substringAfter("\r\n\r\n"))
            assertTrue(runCatching { request(firstUrl) }.isFailure)
        }
    }

    @Test
    fun `every chart template can load its bundled scripts through the preview url`() {
        val assets = File(System.getProperty("everytalk.project.root"), "app/src/main/assets")
        listOf("mermaid", "echarts", "chartjs", "flowchart", "vega").forEach { name ->
            val template = File(assets, "templates/$name.html").readText()
            ExternalWebPreviewServer(template) { File(assets, it).readBytes() }.use { server ->
                val scripts = Regex("src=\"([^\"]+)\"").findAll(template).toList()
                assertTrue(name, scripts.isNotEmpty())
                scripts.forEach { match ->
                    val response = request(URI(server.url).resolve(match.groupValues[1]).toString())
                    assertTrue(match.value, response.startsWith("HTTP/1.1 200 OK"))
                    assertTrue(response.contains("Content-Type: text/javascript; charset=utf-8"))
                    assertTrue(response.substringAfter("\r\n\r\n").isNotEmpty())
                }
            }
        }
    }
}
