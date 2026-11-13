package com.android.everytalk.data.agent

import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.TOOL_CALL_WRITING_STATUS
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentFirstEventTimeoutTest {
    @Test
    fun `工具编写开始后不会因等待完整参数触发首响应超时`() = runTest {
        val events = flow {
            emit(AppStreamEvent.ExecutionStatusUpdate(TOOL_CALL_WRITING_STATUS))
            delay(20)
            emit(AppStreamEvent.Finish("stop"))
        }.withFirstMeaningfulEventTimeout(timeoutMillis = 10).toList()

        assertEquals(2, events.size)
        assertTrue(events.last() is AppStreamEvent.Finish)
    }

    @Test
    fun `首个有效事件超时会终止僵死模型流`() = runTest {
        val events = flow<AppStreamEvent> { delay(100) }
            .withFirstMeaningfulEventTimeout(timeoutMillis = 10).toList()
        val error = events.single() as AppStreamEvent.Error
        assertEquals("first_event_timeout", error.code)
        assertEquals("retryable_network", error.type)
        assertTrue(error.message.contains("没有返回有效事件"))
        assertTrue(error.isRetryableNetworkError(null))
        assertEquals(10L, testScheduler.currentTime)
    }

    @Test
    fun `网络异常保留原原因不等待首事件超时`() = runTest {
        val events = flow<AppStreamEvent> { throw java.io.IOException("connection reset by peer") }
            .withFirstMeaningfulEventTimeout(timeoutMillis = 120_000).toList()
        val error = events.single() as AppStreamEvent.Error
        assertTrue(error.message.contains("connection reset by peer"))
        assertTrue(error.isRetryableNetworkError("connection_failed"))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `错误后连接未关闭也不改写为120秒超时`() = runTest {
        val original = AppStreamEvent.Error("网关连接中断", upstreamStatus = 502)
        val events = flow {
            emit(original)
            delay(130_000)
        }.withFirstMeaningfulEventTimeout().toList()
        assertEquals(listOf(original), events)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `未知模型错误默认重试而明确永久错误不重试`() {
        assertTrue(AppStreamEvent.Error("模型未返回正文或工具调用").isRetryableNetworkError(null))
        assertTrue(AppStreamEvent.Error("上游暂时无响应", upstreamStatus = 503).isRetryableNetworkError(null))
        listOf(
            AppStreamEvent.Error("鉴权失败", upstreamStatus = 401),
            AppStreamEvent.Error("Gemini HTTP 404", code = "http_404"),
            AppStreamEvent.Error("参数错误", upstreamStatus = 400),
            AppStreamEvent.Error("insufficient_quota", upstreamStatus = 429),
            AppStreamEvent.Error("数据库错误", type = AGENT_INTERNAL_ERROR_TYPE),
        ).forEach { assertEquals(it.toString(), false, it.isRetryableNetworkError(null)) }
    }

    @Test
    fun `用户取消不转成网络错误或重试`() = runTest {
        val cancellation = kotlinx.coroutines.CancellationException("用户停止")
        val error = runCatching {
            flow<AppStreamEvent> { throw cancellation }.withFirstMeaningfulEventTimeout().toList()
        }.exceptionOrNull()
        // 协程调试的栈恢复可能复制异常对象；取消类型和原因必须保留，不能生成 Error 事件。
        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertEquals(cancellation.message, error?.message)
    }

    @Test
    fun `首个有效事件到达后继续消费同一条流`() = runTest {
        val events = flow {
            emit(AppStreamEvent.StatusUpdate("连接完成"))
            emit(AppStreamEvent.Text("第一段"))
            delay(20)
            emit(AppStreamEvent.Text("第二段"))
        }.withFirstMeaningfulEventTimeout(timeoutMillis = 10).toList()

        assertEquals(listOf("第一段", "第二段"), events.filterIsInstance<AppStreamEvent.Text>().map { it.text })
    }
}
