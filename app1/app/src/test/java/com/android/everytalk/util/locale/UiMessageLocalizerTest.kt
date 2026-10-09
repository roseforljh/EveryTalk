package com.android.everytalk.util.locale

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import com.android.everytalk.R
import android.app.LocaleManager
import android.os.LocaleList
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

    @Test
    fun `Skills账户授权和后台提示使用英文且保留动态参数`() {
        assertEquals("The Skill package is empty", englishContext.localizeUiMessage("Skill 包为空"))
        assertEquals("Signed in successfully", englishContext.localizeUiMessage("登录成功"))
        assertEquals("SSH password", englishContext.localizeUiMessage("SSH 密码"))
        assertEquals("Task completed", englishContext.localizeUiMessage("任务完成"))
        assertEquals("Skill archive download failed: HTTP 403", englishContext.localizeUiMessage("Skill 压缩包下载失败：HTTP 403"))
        assertEquals("The Skill file changed: scripts/中文.py", englishContext.localizeUiMessage("Skill 文件已变化：scripts/中文.py"))
        assertEquals("Deleted 2 unavailable models", englishContext.localizeUiMessage("已删除 2 个已下架模型"))
    }

    @Test
    fun `中文提示与英文单复数按资源实际加载`() {
        val chineseContext = englishContext.createConfigurationContext(
            Configuration(englishContext.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) },
        )
        assertEquals("登录成功", chineseContext.localizeUiMessage("登录成功"))
        assertEquals("1 Skill", englishContext.resources.getQuantityString(R.plurals.skill_count, 1, 1))
        assertEquals("2 Skills", englishContext.resources.getQuantityString(R.plurals.skill_count, 2, 2))
        assertEquals("2 个 Skill", chineseContext.resources.getQuantityString(R.plurals.skill_count, 2, 2))
    }

    @Test
    fun `后台Context的语言服从应用设置且切换后立即生效`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(LocaleManager::class.java)
        val previous = manager.applicationLocales
        try {
            manager.applicationLocales = LocaleList(Locale.ENGLISH)
            assertEquals("Signed in successfully", context.localizeUiMessage("登录成功"))
            manager.applicationLocales = LocaleList(Locale.SIMPLIFIED_CHINESE)
            assertEquals("登录成功", context.localizeUiMessage("登录成功"))
        } finally {
            manager.applicationLocales = previous
        }
    }

    @Test
    fun `未知中文服务端内容不被误翻译`() {
        val message = "服务端原始说明：这是用户自己填写的内容"
        assertEquals(message, englishContext.localizeUiMessage(message))
        assertEquals("The Skill package is empty", englishContext.localizeUiMessage("Skill 包为空"))
    }

    @Test
    @Config(sdk = [32])
    fun `旧Android后台提示立即服从AppCompat语言选择`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previous = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        try {
            AppLanguageController.setLanguage(AppLanguage.ENGLISH)
            assertEquals("Signed in successfully", context.localizeUiMessage("登录成功"))
            AppLanguageController.setLanguage(AppLanguage.SIMPLIFIED_CHINESE)
            assertEquals("登录成功", context.localizeUiMessage("登录成功"))
        } finally {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(previous)
        }
    }
}
