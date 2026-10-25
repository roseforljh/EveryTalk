package com.android.everytalk.statecontroller

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.CustomModelParameter
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.data.skill.SkillRequestSnapshot
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MessageSenderTurnFinishedTest {

    private lateinit var application: Application
    private lateinit var inMemoryDb: AppDatabase
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        inMemoryDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getDatabase(any()) } returns inMemoryDb

        mockkConstructor(SkillRepository::class)
        coEvery { anyConstructed<SkillRepository>().createSnapshot(any()) } returns SkillRequestSnapshot()

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.v(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        inMemoryDb.close()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `模型参数校验失败提前退出时依然触发onTurnFinished和onSendRejected`() = testScope.runTest {
        val stateHolder = ViewModelStateHolder()
        // 设置包含空名称的非法自定义参数，触发 require(name.isNotEmpty()) 抛出 IllegalArgumentException
        val invalidConfig = ApiConfig(
            id = "cfg-test",
            name = "test",
            provider = "OpenAI",
            model = "gpt-4o",
            address = "https://api.openai.com",
            key = "sk-test",
            modelParameters = ModelParameters(
                customParameters = listOf(
                    CustomModelParameter(name = "", value = "val", enabled = true)
                ),
            ),
        )
        stateHolder._apiConfigs.value = listOf(invalidConfig)
        stateHolder._selectedApiConfig.value = invalidConfig

        val sender = MessageSender(
            application = application,
            viewModelScope = testScope,
            stateHolder = stateHolder,
            apiHandler = mockk(relaxed = true),
            historyManager = mockk<HistoryManager>(relaxed = true),
            showSnackbar = {},
            triggerScrollToBottom = {},
            uriToBase64Encoder = { null },
        )

        val rejectedCalled = AtomicBoolean(false)
        val turnFinishedCalled = CompletableDeferred<Unit>()

        sender.sendMessage(
            messageText = "Hello",
            onSendRejected = { rejectedCalled.set(true) },
            onTurnFinished = { turnFinishedCalled.complete(Unit) },
        )

        // 附件和技能准备可能切到 IO 线程；等待真实回调，而非假设 advanceUntilIdle 已等完 IO。
        turnFinishedCalled.await()

        assertTrue("发送被拒绝时应触发 onSendRejected", rejectedCalled.get())
        assertTrue("即使提前退出未交由 API，也必须触发 onTurnFinished 防止状态泄漏", turnFinishedCalled.isCompleted)
    }
    @Test
    fun `同一文本会话切换图片和文本模型只改变请求类型`() = testScope.runTest {
        val state = ViewModelStateHolder().apply {
            setCurrentConversationId("unified-session")
            _loadedHistoryIndex.value = 0
            messages.add(com.android.everytalk.data.DataClass.Message(id = "first", text = "原对话", sender = com.android.everytalk.data.DataClass.Sender.User))
        }
        val handler = mockk<ApiHandler>(relaxed = true)
        val requests = mutableListOf<com.android.everytalk.data.DataClass.ChatRequest>()
        var received = CompletableDeferred<Unit>()
        every { handler.streamChatResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            requests.add(firstArg())
            org.junit.Assert.assertFalse(arg<Boolean>(10))
            received.complete(Unit)
        }
        val sender = MessageSender(application, testScope, state, handler, mockk<HistoryManager>(relaxed = true), {}, {}, { null })
        // 当前模型保存的比例必须覆盖另一模型留下的全局比例，快捷菜单与请求使用同一参数。
        state._selectedImageRatio.value = com.android.everytalk.data.DataClass.ImageRatio.DEFAULT_RATIOS.first { it.displayName == "9:16" }
        state._selectedApiConfig.value = com.android.everytalk.data.DataClass.builtinImageModels.first().copy(
            numInferenceSteps = 12,
            modelParameters = ModelParameters(imageAspectRatio = "16:9", imageQuality = "high"),
        )
        sender.sendMessage("画一只猫")
        received.await()
        org.junit.Assert.assertEquals("unified-session", requests[0].conversationId)
        org.junit.Assert.assertEquals("unified-session", requests[0].imageGenRequest?.conversationId)
        org.junit.Assert.assertEquals("z-image-turbo-modal", requests[0].imageGenRequest?.model)
        org.junit.Assert.assertEquals("16:9", requests[0].imageGenRequest?.aspectRatio)
        org.junit.Assert.assertEquals("1024x1024", requests[0].imageGenRequest?.imageSize)
        org.junit.Assert.assertEquals("high", requests[0].imageGenRequest?.quality)
        org.junit.Assert.assertEquals(12, requests[0].imageGenRequest?.numInferenceSteps)
        assertTrue(state.imageGenerationMessages.isEmpty())
        assertTrue(state.messages.any { it.text == "画一只猫" })

        received = CompletableDeferred()
        state._selectedApiConfig.value = ApiConfig(
            id = "gpt-image", name = "GPT 图片", model = "gpt-image-2", provider = "OpenAI", address = "https://api.example.com", key = "key",
            modelParameters = ModelParameters(outputModalities = setOf("image"), imageAspectRatio = "16:9"),
        )
        sender.sendMessage("画一幅风景")
        received.await()
        org.junit.Assert.assertEquals("2048x1152", requests[1].imageGenRequest?.imageSize)
        org.junit.Assert.assertEquals("16:9", requests[1].imageGenRequest?.aspectRatio)

        received = CompletableDeferred()
        state._selectedApiConfig.value = ApiConfig(
            id = "text", name = "文本", model = "gpt-4o", provider = "OpenAI", address = "https://api.example.com", key = "key",
            temperature = 1.8f, topP = 0.2f,
            modelParameters = ModelParameters(customParameters = listOf(
                CustomModelParameter("temperature", "1.9", com.android.everytalk.data.DataClass.CustomParameterType.NUMBER),
                CustomModelParameter("top_p", "0.1", com.android.everytalk.data.DataClass.CustomParameterType.NUMBER),
            )),
        )
        sender.sendMessage("解释这张图片")
        received.await()
        org.junit.Assert.assertEquals("unified-session", requests[2].conversationId)
        org.junit.Assert.assertNull(requests[2].imageGenRequest)
        org.junit.Assert.assertNull(requests[2].generationConfig?.temperature)
        org.junit.Assert.assertNull(requests[2].generationConfig?.topP)
        org.junit.Assert.assertNotNull(requests[2].generationConfig?.maxOutputTokens)
        assertTrue(requests[2].customModelParameters.orEmpty().isEmpty())
        assertTrue(state.imageGenerationMessages.isEmpty())
        assertTrue(requests[2].messages.any { it.id == state.messages.first().id })
    }

}
