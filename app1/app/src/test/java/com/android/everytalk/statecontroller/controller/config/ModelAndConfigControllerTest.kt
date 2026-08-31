package com.android.everytalk.statecontroller.controller.config

import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.DataClass.effectiveModelChannel
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
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        coEvery { ApiClient.getModelCatalog("first", "key-a", "OpenAI兼容") } coAnswers {
            firstStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                firstCancelled.complete(Unit)
            }
        }
        val modelB = ModelCapabilityCandidate(
            modelId = "model-b",
            protocol = ModelParameterProtocol.GEMINI,
            contextWindowTokens = 1_000_000,
            maxOutputTokens = 64_000,
            source = ModelCapabilitySource.LIVE_ENDPOINT,
        )
        coEvery { ApiClient.getModelCatalog("second", "key-b", "Gemini") } returns listOf(modelB)

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
        assertEquals(modelB, modelFetchManager.capabilityFor("model-b"))
        coVerify(exactly = 1) { ApiClient.getModelCatalog("second", "key-b", "Gemini") }
    }

    @Test
    fun `clear prevents an old request from publishing`() = runTest(UnconfinedTestDispatcher()) {
        mockkObject(ApiClient)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { ApiClient.getModelCatalog(any(), any(), any()) } coAnswers {
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
    fun `自动获取模型参数采用当前端点能力且不直接持久化`() = runTest(UnconfinedTestDispatcher()) {
        mockkObject(ApiClient)
        val capability = ModelCapabilityCandidate(
            modelId = "model-a",
            protocol = ModelParameterProtocol.GEMINI,
            endpointIdentity = "https://api.example.com",
            contextWindowTokens = 1_000_000,
            maxOutputTokens = 64_000,
            supportsReasoning = false,
            source = ModelCapabilitySource.LIVE_ENDPOINT,
        )
        coEvery {
            ApiClient.getModelCapabilities(
                "https://api.example.com",
                "secret",
                "Gemini",
                "model-a",
                "Gemini",
            )
        } returns listOf(capability)
        val configManager = mockk<ConfigManager>(relaxed = true)
        val controller = controller(
            scope = this,
            stateHolder = ViewModelStateHolder(),
            configManager = configManager,
        )
        val config = ApiConfig(
            address = "https://api.example.com",
            key = "secret",
            model = "model-a",
            provider = "Gemini",
            name = "model-a",
            channel = "Gemini",
        )

        val loaded = controller.loadModelParameters(config).getOrThrow()

        assertEquals(64_000, loaded.maxTokens)
        assertEquals(1_000_000, loaded.modelParameters.maxContextTokens)
        assertEquals(false, loaded.modelParameters.resolvedCapability?.supportsReasoning)
        assertEquals(
            ModelCapabilitySource.LIVE_ENDPOINT,
            loaded.modelParameters.resolvedCapability?.maxOutputSource,
        )
        verify(exactly = 0) { configManager.updateConfig(any(), any()) }
        coVerify(exactly = 1) {
            ApiClient.getModelCapabilities(
                "https://api.example.com",
                "secret",
                "Gemini",
                "model-a",
                "Gemini",
            )
        }
    }

    @Test
    fun `新模型配置采用端点报告的 token 限制`() = runTest(UnconfinedTestDispatcher()) {
        val stateHolder = ViewModelStateHolder()
        val modelFetchManager = ModelFetchManager().apply {
            setFetchedCatalog(
                listOf(
                    ModelCapabilityCandidate(
                        modelId = "model-a",
                        protocol = ModelParameterProtocol.GEMINI,
                        endpointIdentity = "https://api.example.com/v1",
                        contextWindowTokens = 1_000_000,
                        maxOutputTokens = 64_000,
                        source = ModelCapabilitySource.LIVE_ENDPOINT,
                    )
                )
            )
        }
        val configManager = mockk<ConfigManager>(relaxed = true)
        val controller = controller(
            scope = this,
            stateHolder = stateHolder,
            modelFetchManager = modelFetchManager,
            configManager = configManager,
        )

        controller.createMultipleConfigs(
            provider = "Gemini",
            address = "https://api.example.com/v1/",
            key = "secret",
            modelNames = listOf("model-a"),
            channel = "Gemini",
        )

        val configSlot = slot<ApiConfig>()
        verify { configManager.addConfig(capture(configSlot), false) }
        assertEquals(64_000, configSlot.captured.maxTokens)
        assertEquals(1_000_000, configSlot.captured.modelParameters.maxContextTokens)
        assertEquals(
            ModelCapabilitySource.LIVE_ENDPOINT,
            configSlot.captured.modelParameters.resolvedCapability?.contextWindowSource,
        )
    }

    @Test
    fun `目录只有模型名时添加配置会自动补拉模型参数`() = runTest(UnconfinedTestDispatcher()) {
        mockkObject(ApiClient)
        val detail = ModelCapabilityCandidate(
            modelId = "model-a",
            protocol = ModelParameterProtocol.GEMINI,
            endpointIdentity = "https://api.example.com",
            contextWindowTokens = 256_000,
            maxOutputTokens = 16_000,
            source = ModelCapabilitySource.LIVE_ENDPOINT,
        )
        coEvery {
            ApiClient.getModelCapabilities(
                "https://api.example.com",
                "secret",
                "Gemini",
                "model-a",
                "Gemini",
            )
        } returns listOf(detail)

        val modelFetchManager = ModelFetchManager().apply {
            setFetchedCatalog(
                listOf(
                    ModelCapabilityCandidate(
                        modelId = "model-a",
                        protocol = ModelParameterProtocol.GEMINI,
                        source = ModelCapabilitySource.LIVE_ENDPOINT,
                    )
                )
            )
        }
        val configManager = mockk<ConfigManager>(relaxed = true)
        val controller = controller(
            scope = this,
            stateHolder = ViewModelStateHolder(),
            modelFetchManager = modelFetchManager,
            configManager = configManager,
        )

        controller.createMultipleConfigs(
            provider = "Gemini",
            address = "https://api.example.com",
            key = "secret",
            modelNames = listOf("model-a"),
            channel = "Gemini",
        )

        val configSlot = slot<ApiConfig>()
        verify { configManager.addConfig(capture(configSlot), false) }
        assertEquals(16_000, configSlot.captured.maxTokens)
        assertEquals(256_000, configSlot.captured.modelParameters.maxContextTokens)
        coVerify(exactly = 1) {
            ApiClient.getModelCapabilities(
                "https://api.example.com",
                "secret",
                "Gemini",
                "model-a",
                "Gemini",
            )
        }
    }

    @Test
    fun `配置组新增模型采用端点报告的 token 限制`() = runTest(UnconfinedTestDispatcher()) {
        val modelFetchManager = ModelFetchManager().apply {
            setFetchedCatalog(
                listOf(
                    ModelCapabilityCandidate(
                        modelId = "model-new",
                        protocol = ModelParameterProtocol.GEMINI,
                        endpointIdentity = "https://api.example.com/v1",
                        contextWindowTokens = 2_000_000,
                        maxOutputTokens = 32_000,
                        source = ModelCapabilitySource.LIVE_ENDPOINT,
                    )
                )
            )
        }
        val configManager = mockk<ConfigManager>(relaxed = true)
        val controller = controller(
            scope = this,
            stateHolder = ViewModelStateHolder(),
            modelFetchManager = modelFetchManager,
            configManager = configManager,
        )
        val representative = ApiConfig(
            address = "https://api.example.com/v1/",
            key = "secret",
            model = "model-old",
            provider = "Gemini",
            name = "model-old",
            channel = "Gemini",
        )

        controller.addModelToConfigGroup(representative, "model-new")

        val configSlot = slot<ApiConfig>()
        verify { configManager.addConfig(capture(configSlot), false) }
        assertEquals(32_000, configSlot.captured.maxTokens)
        assertEquals(2_000_000, configSlot.captured.modelParameters.maxContextTokens)
        assertEquals(
            ModelCapabilitySource.LIVE_ENDPOINT,
            configSlot.captured.modelParameters.resolvedCapability?.maxOutputSource,
        )
    }

    @Test
    fun `刷新配置组的新模型采用调用前快照的端点能力`() = runTest {
        val existing = imageConfig(
            id = "id-existing",
            model = "model-existing",
            name = "existing",
        ).copy(maxTokens = 7_777)
        val stateHolder = ViewModelStateHolder().apply {
            _imageGenApiConfigs.value = listOf(existing)
        }
        val modelFetchManager = ModelFetchManager().apply {
            setFetchedCatalog(
                listOf(
                    ModelCapabilityCandidate(
                        modelId = "model-new",
                        protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                        endpointIdentity = "https://image.example",
                        contextWindowTokens = 512_000,
                        maxOutputTokens = 24_000,
                        source = ModelCapabilitySource.LIVE_ENDPOINT,
                    )
                )
            )
        }
        val controller = controller(
            scope = this,
            stateHolder = stateHolder,
            modelFetchManager = modelFetchManager,
        )

        controller.appendModelsToConfigGroup(
            PendingConfigParams(
                provider = existing.provider,
                address = existing.address,
                key = existing.key,
                channel = existing.channel,
                isImageGen = true,
                isRefresh = true,
            ),
            listOf("model-existing", "model-new"),
        )
        modelFetchManager.setFetchedModels(emptyList())
        advanceUntilIdle()

        val refreshed = stateHolder._imageGenApiConfigs.value
        assertEquals(7_777, refreshed.single { it.model == "model-existing" }.maxTokens)
        val newConfig = refreshed.single { it.model == "model-new" }
        assertEquals(24_000, newConfig.maxTokens)
        assertEquals(512_000, newConfig.modelParameters.maxContextTokens)
        assertEquals(
            ModelCapabilitySource.LIVE_ENDPOINT,
            newConfig.modelParameters.resolvedCapability?.contextWindowSource,
        )
    }

    @Test
    fun `refresh only appends new models and keeps local removed models`() = runTest(UnconfinedTestDispatcher()) {
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

        controller.appendModelsToConfigGroup(
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
        val retainedA = refreshed.single { it.model == "model-a" }
        val retainedB = refreshed.single { it.model == "model-b" }
        val newC = refreshed.single { it.model == "model-c" }
        assertEquals(oldA, retainedA)
        assertEquals(oldB, retainedB)
        assertNotEquals(oldA.id, newC.id)
        assertEquals(oldA.temperature, newC.temperature)
        assertEquals(oldA.imageSize, newC.imageSize)
        assertEquals(oldA.numInferenceSteps, newC.numInferenceSteps)
        assertEquals(oldA.guidanceScale, newC.guidanceScale)
        assertEquals(oldB, stateHolder._selectedImageGenApiConfig.value)
        assertEquals(
            mapOf(
                "text-history" to oldA.id,
                "image-history" to oldB.id,
                "unrelated" to unrelated.id,
            ),
            stateHolder.conversationApiConfigIds.value,
        )
        coVerify(exactly = 0) { persistenceManager.saveConversationApiConfigIds(any()) }
    }

    @Test
    fun `config group id ignores each model protocol`() {
        val base = imageConfig(id = "id", model = "model", name = "model")

        assertEquals(
            modelConfigGroupId(base),
            modelConfigGroupId(base.copy(id = "new-id", model = "new-model", imageSize = "other")),
        )
        assertNotEquals(modelConfigGroupId(base), modelConfigGroupId(base.copy(provider = "other")))
        assertNotEquals(modelConfigGroupId(base), modelConfigGroupId(base.copy(address = "https://other")))
        assertEquals(modelConfigGroupId(base), modelConfigGroupId(base.copy(channel = "Gemini")))
        assertEquals(
            modelConfigGroupId(base),
            modelConfigGroupId(
                base.copy(
                    modelParameters = ModelParameters(apiProtocolOverride = ModelParameterProtocol.CODEX),
                )
            ),
        )
        assertNotEquals(modelConfigGroupId(base), modelConfigGroupId(base.copy(key = "other-key")))
        assertNotEquals(modelConfigGroupId(base), modelConfigGroupId(base.copy(modalityType = ModalityType.TEXT)))
    }

    @Test
    fun `model protocol override is used only for this model`() {
        val base = imageConfig(id = "id", model = "model", name = "model").copy(channel = "OpenAI兼容")

        assertEquals("OpenAI兼容", base.effectiveModelChannel())
        assertEquals(
            "Codex",
            base.copy(
                modelParameters = base.modelParameters.copy(
                    apiProtocolOverride = ModelParameterProtocol.CODEX,
                )
            ).effectiveModelChannel(),
        )
    }

    private fun controller(
        scope: CoroutineScope,
        stateHolder: ViewModelStateHolder,
        modelFetchManager: ModelFetchManager = ModelFetchManager(),
        persistenceManager: DataPersistenceManager = mockk(relaxed = true),
        configManager: ConfigManager = mockk(relaxed = true),
    ) = ModelAndConfigController(
        stateHolder = stateHolder,
        persistenceManager = persistenceManager,
        modelFetchManager = modelFetchManager,
        configManager = configManager,
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
