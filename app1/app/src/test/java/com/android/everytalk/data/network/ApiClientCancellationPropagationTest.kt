package com.android.everytalk.data.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApiClientCancellationPropagationTest {

    @Test
    fun `attachment preprocessing rethrows cancellation before fallback`() {
        val source = apiClientSource()
        val block = source.substringAfter("val requestForDirect = try")
            .substringBefore("val providerRegistry")

        assertCancellationPrecedesException(block)
    }

    @Test
    fun `image generation rethrows cancellation before wrapping failures`() {
        val source = apiClientSource()
        val block = source.substringAfter("suspend fun generateImage")
            .substringBefore("private fun supportsOpenAIImageEdit")

        assertCancellationPrecedesException(block.substringAfterLast("return try"))
    }

    @Test
    fun `代理流使用有界 SSE 读取并保留取消传播`() {
        val source = apiClientSource()
        val block = source.substringAfter("private suspend fun CoroutineScope.processChannel")
            .substringBefore("fun streamChatResponse")

        assertTrue(block.contains("BoundedSseLineReader(channel)"))
        assertTrue(block.contains("catch (e: CoroutineCancellationException)"))
    }

    private fun assertCancellationPrecedesException(source: String) {
        val cancellationCatch = source.indexOf("catch (e: CoroutineCancellationException)")
        val exceptionCatch = source.indexOf("catch (e: Exception)")

        assertTrue(cancellationCatch >= 0)
        assertTrue(exceptionCatch > cancellationCatch)
    }

    private fun apiClientSource(): String {
        val relativePath = "data/network/ApiClient.kt"
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/$relativePath"),
            File("app/src/main/java/com/android/everytalk/$relativePath"),
            File("app1/app/src/main/java/com/android/everytalk/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "找不到 $relativePath"
        }.readText(Charsets.UTF_8)
    }
}
