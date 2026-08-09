package com.android.everytalk.util.locale

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UiMessageLocalizerTest {
    private val englishContext: Context by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        context.createConfigurationContext(configuration)
    }

    @Test
    fun `legacy exact message follows active locale`() {
        assertEquals(
            "Conversation renamed",
            englishContext.localizeUiMessage("对话已重命名"),
        )
    }

    @Test
    fun `legacy counted message uses localized plural`() {
        assertEquals(
            "Fetched 2 models",
            englishContext.localizeUiMessage("获取到 2 个模型"),
        )
    }

    @Test
    fun `unknown backend message is preserved`() {
        assertEquals(
            "provider says no",
            englishContext.localizeUiMessage("provider says no"),
        )
    }

    @Test
    fun `known provider error follows active locale`() {
        assertEquals(
            "OpenAI: The API key is invalid or expired",
            englishContext.localizeUiMessage("OpenAI: API 密钥无效或已过期"),
        )
    }

    @Test
    fun `legacy error bubble localizes wrapper and reason`() {
        assertEquals(
            "⚠️ Network communication error: OpenAI: Connection timed out. Check your network",
            englishContext.localizeUiMessage("⚠️ 网络通讯故障: OpenAI: 连接超时，请检查网络"),
        )
    }

    @Test
    fun `compression failure localizes known reason`() {
        assertEquals(
            "Context compression failed: Compression could not reduce the content further",
            englishContext.localizeUiMessage("上下文压缩失败：压缩结果未能继续缩小"),
        )
    }
}
