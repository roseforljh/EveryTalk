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
}
