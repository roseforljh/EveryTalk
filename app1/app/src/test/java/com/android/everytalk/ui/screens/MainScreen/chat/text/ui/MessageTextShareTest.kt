package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MessageTextShareTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        stopKoin()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        stopKoin()
    }

    @Test
    fun `分享消息只向系统发送净化后的文本`() = runTest(dispatcher) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        shareMessageText(
            context = activity,
            text = "正文\n\n![图](data:image/png;base64,QUJDRA==)",
            onFailure = { error("不应分享失败") },
        )

        val chooserIntent = shadowOf(activity).nextStartedActivity
        val targetIntent = chooserIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals("正文", targetIntent?.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `系统拒绝启动分享界面时报告失败`() = runTest(dispatcher) {
        val context = mockk<Context>()
        var failed = false
        every { context.startActivity(any()) } throws IllegalStateException("无法启动")

        shareMessageText(
            context = context,
            text = "正文",
            onFailure = { failed = true },
        )

        assertTrue(failed)
    }
}
