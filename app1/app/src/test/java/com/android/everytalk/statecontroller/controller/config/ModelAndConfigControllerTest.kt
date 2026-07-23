package com.android.everytalk.statecontroller.controller.config

import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.statecontroller.PendingConfigParams
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.viewmodel.ModelFetchManager
import com.android.everytalk.ui.screens.viewmodel.ConfigManager
import com.android.everytalk.ui.screens.viewmodel.DataPersistenceManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelAndConfigControllerTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `latest model request wins and keeps channel`() = runTest(UnconfinedTestDispatcher()) {
        mockkObject(ApiClient)
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        coEvery { ApiClient.getModels("first", "key-a", "OpenAI兼容") } coAnswers {
            firstStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                firstCancelled.complete(Unit)
            }
        }
        coEvery { ApiClient.getModels("second", "key-b", "Gemini") } returns listOf("model-b")

        val stateHolder = ViewModelStateHolder()
        val modelFetchManager = ModelFetchManager()
        val controller = controller(this, stateHolder, modelFetchManager)
        val firstResults = mutableListOf<Result<List<String>>>()
        val secondResult = CompletableDeferred<Result<List<String>>>()

        controller.fetchModels("first", "key-a", "OpenAI兼容") { firstResults += it }
        firstStarted.await()
        controller.fetchModels("second", "key-b", "Gemini") { secondResult.complete(it) }

        assertEquals(listOf("model-b"), secondResult.await().getOrThrow())
        firstCancelled.await()
        assertTrue(firstResults.isEmpty())
        assertEquals(listOf("model-b"), modelFetchManager.fetchedModels.value)
        coVerify(exactly = 1) { ApiClient.getModels("second", "key-b", "Gemini") }
    }

    @Test
    fun `clear prevents an old request from publishing`() = runTest(UnconfinedTestDispatcher()) {
        mockkObject(ApiClient)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { ApiClient.getModels(any(), any(), any()) } coAnswers {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }

        val stateHolder = ViewModelStateHolder()
        val modelFetchManager = ModelFetchManager()
        val controller = controller(this, stateHolder, modelFetchManager)
        val results = mutableListOf<Result<List<String>>>()

        controller.fetchModels("first", "key", "Gemini") { results += it }
        started.await()
        controller.clearFetchedModels()
        cancelled.await()

        assertTrue(results.isEmpty())
        assertTrue(modelFetchManager.fetchedModels.value.isEmpty())
        assertTrue(modelFetchManager.isRefreshingModels.value.isEmpty())
        assertFalse(stateHolder._showModelSelectionDialog.value)
    }

    @Test
    fun `refresh preserves matching ids and migrates removed bindings`() = runTest(UnconfinedTestDispatcher()) {
        val oldA = imageConfig(
            id = "id-a",
            model = "model-a",
            name = "自定义 A",
            temperature = 0.2f,
            imageSize = "1024x1024",
            numInferenceSteps = 12,
            guidanceScale = 4.5f,
        )
        val oldB = imageConfig(
            id = "id-b",
            model = "model-b",
            name = "自定义 B",
            temperature = 0.8f,
            imageSize = "2048x2048",
            numInferenceSteps = 18,
            guidanceScale = 7.5f,
        )
        val unrelated = oldA.copy(id = "id-other", address = "https://other.example", model = "other")
        val stateHolder = ViewModelStateHolder().apply {
            _imageGenApiConfigs.value = listOf(oldA, oldB, unrelated)
            _selectedImageGenApiConfig.value = oldB
            conversationApiConfigIds.value = mapOf(
                "text-history" to oldA.id,
                "image-history" to oldB.id,
                "unrelated" to unrelated.id,
            )
        }
        val persistenceManager = mockk<DataPersistenceManager>(relaxed = true)
        val controller = controller(
            scope = this,
            stateHolder = stateHolder,
            persistenceManager = persistenceManager,
        )

        controller.replaceModelsForConfigGroup(
            PendingConfigParams(
                provider = oldA.provider,
                address = oldA.address,
                key = oldA.key,
                channel = oldA.channel,
                isImageGen = true,
                imageSize = "不应覆盖旧配置",
                isRefresh = true,
            ),
            listOf("model-b", "model-c", "model-b", " "),
        )

        val refreshed = stateHolder._imageGenApiConfigs.value.filter { it.address == oldA.address }
        val retainedB = refreshed.single { it.model == "model-b" }
        val newC = refreshed.single { it.model == "model-c" }
        assertEquals(oldB, retainedB)
        assertNotEquals(oldA.id, newC.id)
        assertEquals(oldA.temperature, newC.temperature)
        assertEquals(oldA.imageSize, newC.imageSize)
        assertEquals(oldA.numInferenceSteps, newC.numInferenceSteps)
        assertEquals(oldA.guidanceScale, newC.guidanceScale)
        assertEquals(oldB, stateHolder._selectedImageGenApiConfig.value)
        assertEquals(
            mapOf(
                "text-history" to oldB.id,
                "image-history" to oldB.id,
                "unrelated" to unrelated.id,
            ),
            stateHolder.conversationApiConfigIds.value,
        )
        assertNull(refreshed.singleOrNull { it.model == "model-a" })
        coVerify(exactly = 1) {
            persistenceManager.saveConversationApiConfigIds(stateHolder.conversationApiConfigIds.value)
        }
    }

    @Test
    fun `config group id uses every identity field`() {
        val base = imageConfig(id = "id", model = "model", name = "model")

        assertEquals(
            modelConfigGroupId(base),
            modelConfigGroupId(base.copy(id = "new-id", model = "new-model", imageSize = "other")),
        )
        assertNotEquals(modelConfigGroupId(base), modelConfigGroupId(base.copy(provider = "other")))
        assertNotEquals(modelConfigGroupId(base), modelConfigGroupId(base.copy(address = "https://other")))
        assertNotEquals(modelConfigGroupId(base), modelConfigGroupId(base.copy(channel = "Gemini")))
        assertNotEquals(modelConfigGroupId(base), modelConfigGroupId(base.copy(key = "other-key")))
        assertNotEquals(modelConfigGroupId(base), modelConfigGroupId(base.copy(modalityType = ModalityType.TEXT)))
    }

    private fun controller(
        scope: CoroutineScope,
        stateHolder: ViewModelStateHolder,
        modelFetchManager: ModelFetchManager = ModelFetchManager(),
        persistenceManager: DataPersistenceManager = mockk(relaxed = true),
    ) = ModelAndConfigController(
        stateHolder = stateHolder,
        persistenceManager = persistenceManager,
        modelFetchManager = modelFetchManager,
        configManager = mockk<ConfigManager>(relaxed = true),
        scope = scope,
        showSnackbar = {},
    )

    private fun imageConfig(
        id: String,
        model: String,
        name: String,
        temperature: Float = 0f,
        imageSize: String? = null,
        numInferenceSteps: Int? = null,
        guidanceScale: Float? = null,
    ) = ApiConfig(
        id = id,
        address = "https://image.example",
        key = "secret",
        model = model,
        provider = "provider",
        name = name,
        channel = "OpenAI兼容",
        modalityType = ModalityType.IMAGE,
        temperature = temperature,
        imageSize = imageSize,
        numInferenceSteps = numInferenceSteps,
        guidanceScale = guidanceScale,
        toolsJson = "[{\"type\":\"function\"}]",
        enableCodeExecution = true,
    )
}
