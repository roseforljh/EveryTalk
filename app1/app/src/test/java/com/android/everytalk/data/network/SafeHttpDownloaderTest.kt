package com.android.everytalk.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.HttpUrl.Companion.toHttpUrl

class SafeHttpDownloaderTest {

    @Test
    fun `rejects local private and mixed dns addresses`() {
        val publicAddress = InetAddress.getByName("8.8.8.8")
        val privateAddress = InetAddress.getByName("192.168.1.1")

        assertTrue(SafeHttpDownloader.isPublicAddress(publicAddress))
        assertFalse(SafeHttpDownloader.isPublicAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(SafeHttpDownloader.isPublicAddress(privateAddress))
        assertFalse(SafeHttpDownloader.isPublicAddress(InetAddress.getByName("198.18.0.1")))
        assertFalse(SafeHttpDownloader.isPublicAddress(InetAddress.getByName("203.0.113.1")))
        assertFalse(SafeHttpDownloader.isPublicAddress(InetAddress.getByName("fc00::1")))
        assertFalse(SafeHttpDownloader.isPublicAddress(InetAddress.getByName("2001:db8::1")))
        assertFalse(SafeHttpDownloader.isPublicAddress(InetAddress.getByName("2002:0808:0808::1")))
        assertThrows(UnknownHostException::class.java) {
            SafeHttpDownloader.requirePublicAddresses(listOf(publicAddress, privateAddress))
        }
    }

    @Test
    fun `bounded reader rejects oversized response`() {
        val input = ByteArrayInputStream(ByteArray(9))
        assertThrows(SafeHttpDownloader.ResponseTooLargeException::class.java) {
            SafeHttpDownloader.readAtMost(input, 8)
        }
    }

    @Test
    fun `private addresses require an explicitly trusted origin`() {
        val privateAddress = InetAddress.getByName("192.168.1.8")

        assertEquals(
            listOf(privateAddress),
            SafeHttpDownloader.requireAddresses(listOf(privateAddress), allowPrivate = true),
        )
        assertThrows(UnknownHostException::class.java) {
            SafeHttpDownloader.requireAddresses(listOf(privateAddress), allowPrivate = false)
        }
    }

    @Test
    fun `image extraction caps url count`() {
        val markdown = (1..20).joinToString("\n") { "![image](https://example.com/content-$it.png)" }
        assertEquals(WebFetchImageExtractor.MAX_IMAGE_URLS, WebFetchImageExtractor.extractImageUrls(markdown).size)
    }

    @Test
    fun `cross origin sensitive headers are recognized`() {
        assertTrue(SafeHttpDownloader.isSensitiveHeader("Authorization"))
        assertTrue(SafeHttpDownloader.isSensitiveHeader("X-Access-Token"))
        assertFalse(SafeHttpDownloader.isSensitiveHeader("Referer"))
    }

    @Test
    fun `sensitive headers are sent only to the trusted origin`() {
        val headers = mapOf(
            "Authorization" to "Bearer secret",
            "X-Api-Key" to "secret-key",
            "Referer" to "http://192.168.1.8:8080/v1/images",
        )
        val trustedOrigin = "http://192.168.1.8:8080/v1/images".toHttpUrl()

        assertEquals(
            headers,
            SafeHttpDownloader.headersForTarget(
                headers,
                trustedOrigin,
                "http://192.168.1.8:8080/files/image.png".toHttpUrl(),
            ),
        )

        val crossOrigin = SafeHttpDownloader.headersForTarget(
            headers,
            trustedOrigin,
            "https://cdn.example.com/image.png".toHttpUrl(),
        )
        assertFalse(crossOrigin.containsKey("Authorization"))
        assertFalse(crossOrigin.containsKey("X-Api-Key"))
        assertEquals(headers["Referer"], crossOrigin["Referer"])

        val differentPort = SafeHttpDownloader.headersForTarget(
            headers,
            trustedOrigin,
            "http://192.168.1.8:8081/image.png".toHttpUrl(),
        )
        assertFalse(differentPort.containsKey("Authorization"))
    }
}
