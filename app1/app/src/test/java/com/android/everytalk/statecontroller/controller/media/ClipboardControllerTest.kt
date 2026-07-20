package com.android.everytalk.statecontroller.controller.media

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.statecontroller.viewmodel.ExportManager
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClipboardControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var application: Application
    private lateinit var clipboard: ClipboardManager

    @Before
    fun setUp() {
        stopKoin()
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
        stopKoin()
    }

    @Test
    fun `复制消息时只写入净化后的文本`() {
        val snackbarMessages = mutableListOf<String>()
        val completed = CountDownLatch(1)
        val controller = ClipboardController(
            application = application,
            exportManager = mockk<ExportManager>(relaxed = true),
            scope = scope,
            showSnackbar = {
                snackbarMessages += it
                completed.countDown()
            },
        )

        controller.copyToClipboard("正文\n\n![图](data:image/png;base64,QUJDRA==)")

        assertEquals(true, completed.await(5, TimeUnit.SECONDS))
        assertEquals("正文", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(listOf("已复制到剪贴板"), snackbarMessages)
    }

    @Test
    fun `导出消息时移除图片且保留完整长正文`() = runBlocking {
        val exportManager = ExportManager()
        val controller = ClipboardController(
            application = application,
            exportManager = exportManager,
            scope = scope,
            showSnackbar = {},
        )
        val longText = "正文".repeat(200_000)

        controller.exportMessageText("$longText\n![图](data:image/png;base64,QUJDRA==)")
        val request = withTimeout(5_000) { exportManager.exportRequest.first() }

        assertEquals("conversation_export.md", request.first)
        assertEquals(longText, request.second)
    }
}
