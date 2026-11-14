package com.android.everytalk.application

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.EventListener
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.Fetcher
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import com.android.everytalk.EveryTalkApplication
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EveryTalkImageLoaderTest {
    @Test
    fun `相同图片并发只下载一次且取消等待者不阻塞其他请求`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = context.cacheDir.resolve("image-dedupe-${System.nanoTime()}")
        val requests = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fetches = CountDownLatch(3)
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGP4DwQACfsD/fteaysAAAAASUVORK5CYII=")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/image.png") { exchange ->
            requests.incrementAndGet()
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
            exchange.close()
        }
        server.start()
        // 直接沿用生产工厂，只隔离缓存并观察进入抓取的时机，不能替换掉待验证的网络策略。
        val loader = EveryTalkApplication().newImageLoader(context).newBuilder()
            .diskCache { DiskCache.Builder().directory(cache.toOkioPath()).build() }
            .eventListenerFactory {
                object : EventListener() {
                    override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
                        fetches.countDown()
                    }
                }
            }
            .build()
        val request = ImageRequest.Builder(context)
            .data("http://127.0.0.1:${server.address.port}/image.png")
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        try {
            withTimeout(10_000) {
                val first = async(Dispatchers.IO) { loader.execute(request) }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val cancelled = async(Dispatchers.IO) { loader.execute(request) }
                val waiting = async(Dispatchers.IO) { loader.execute(request) }
                assertTrue(fetches.await(5, TimeUnit.SECONDS))
                cancelled.cancelAndJoin()
                release.countDown()
                val results = listOf(first.await(), waiting.await())
                assertTrue(results.toString(), results.all { it is SuccessResult })
                assertEquals(setOf(DataSource.NETWORK, DataSource.DISK), results.map { (it as SuccessResult).dataSource }.toSet())
                assertEquals(1, requests.get())
                assertTrue(loader.execute(request) is SuccessResult)
                assertEquals(1, requests.get())
            }
        } finally {
            release.countDown()
            server.stop(0)
            loader.shutdown()
            cache.deleteRecursively()
        }
    }
}
