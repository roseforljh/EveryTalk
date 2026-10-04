package com.android.everytalk.statecontroller.controller.config

import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.DataClass.effectiveModelChannel
import com.android.everytalk.data.DataClass.withModelCapabilityDefaults
import com.android.everytalk.data.DataClass.withUserTokenLimits
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.statecontroller.PendingConfigParams
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.viewmodel.ModelFetchManager
import com.android.everytalk.ui.screens.viewmodel.ConfigManager
import com.android.everytalk.ui.screens.viewmodel.DataPersistenceManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
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
            ApiClient.getFreshModelCapabilities(
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
            ApiClient.getFreshModelCapabilities(
                "https://api.example.com",
                "secret",
                "Gemini",
                "model-a",
                "Gemini",
            )
        }
    }

    @Test
    fun `手动获取用实时值覆盖之前保存的错误手动参数`() = runTest(UnconfinedTestDispatcher()) {
        mockkObject(ApiClient)
        val config = ApiConfig(
            address = "https://api.example.com",
            key = "secret",
            model = "gemini-3.8-flash",
            provider = "Gemini",
            name = "Gemini 3.8 Flash",
            channel = "Gemini",
        ).withUserTokenLimits(ModelTokenLimits(4_000, 100_000))
        coEvery { ApiClient.getFreshModelCapabilities(any(), any(), any(), any(), any()) } returns listOf(
            ModelCapabilityCandidate(
                modelId = config.model,
                protocol = ModelParameterProtocol.GEMINI,
                endpointIdentity = config.address,
                contextWindowTokens = 1_000_000,
                maxOutputTokens = 64_000,
                source = ModelCapabilitySource.LIVE_ENDPOINT,
            )
        )

        val loaded = controller(this, ViewModelStateHolder()).loadModelParameters(config).getOrThrow()

        assertEquals(64_000, loaded.maxTokens)
        assertEquals(1_000_000, loaded.modelParameters.maxContextTokens)
        assertEquals(ModelCapabilitySource.LIVE_ENDPOINT, loaded.modelParameters.resolvedCapability?.maxOutputSource)
        assertEquals(ModelCapabilitySource.LIVE_ENDPOINT, loaded.modelParameters.resolvedCapability?.contextWindowSource)
        coVerify(exactly = 1) {
            ApiClient.getFreshModelCapabilities(config.address, config.key, "Gemini", config.model, config.provider)
        }
    }

    @Test
    fun `实时来源全失败时不把旧参数当成获取成功`() = runTest(UnconfinedTestDispatcher()) {
        mockkObject(ApiClient)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
        val config = ApiConfig(
            address = "https://api.example.com",
            key = "secret",
            model = "gemini-3.8-flash",
            provider = "Gemini",
            name = "Gemini 3.8 Flash",
            channel = "Gemini",
        ).withUserTokenLimits(ModelTokenLimits(4_000, 100_000))
        coEvery { ApiClient.getFreshModelCapabilities(any(), any(), any(), any(), any()) } returns emptyList()

        val result = controller(this, ViewModelStateHolder()).loadModelParameters(config)

        assertTrue(result.isFailure)
        assertEquals(4_000, config.maxTokens)
        assertEquals(100_000, config.modelParameters.maxContextTokens)
    }

    @Test
    fun `实时来源缺少最大输出时不使用估算值冒充还原结果`() = runTest(UnconfinedTestDispatcher()) {
        mockkObject(ApiClient)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
        val config = ApiConfig(
            address = "https://api.example.com",
            key = "secret",
            model = "gemini-3.8-flash",
            provider = "Gemini",
            name = "Gemini 3.8 Flash",
            channel = "Gemini",
        ).withUserTokenLimits(ModelTokenLimits(4_000, 100_000))
        coEvery { ApiClient.getFreshModelCapabilities(any(), any(), any(), any(), any()) } returns listOf(
            ModelCapabilityCandidate(
                modelId = config.model,
                protocol = ModelParameterProtocol.GEMINI,
                endpointIdentity = config.address,
                contextWindowTokens = 1_000_000,
                source = ModelCapabilitySource.LIVE_ENDPOINT,
            )
        )

        val result = controller(this, ViewModelStateHolder()).loadModelParameters(config)

        assertTrue(result.isFailure)
        assertEquals(4_000, config.maxTokens)
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
    fun `未经目录获取的手动添加会自动补拉模型参数`() = runTest(UnconfinedTestDispatcher()) {
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
            setFetchedModels(listOf("model-a"))
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
    fun `获取完成后新增直接保存各来源参数且清空弹窗不触发重复获取`() = runTest {
        mockkObject(ApiClient)
        val live = ModelCapabilityCandidate(
            modelId = "model-a", protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            contextWindowTokens = 256_000, source = ModelCapabilitySource.LIVE_ENDPOINT,
        )
        val community = live.copy(
            contextWindowTokens = 128_000, maxOutputTokens = 16_000,
            supportsReasoning = true, reasoningEfforts = setOf("high"),
            source = ModelCapabilitySource.COMMUNITY_CATALOG,
        )
        coEvery { ApiClient.getModelCatalog(any(), any(), any()) } returns listOf(live, community)
        val manager = ModelFetchManager()
        val configManager = mockk<ConfigManager>(relaxed = true)
        val controller = controller(this, ViewModelStateHolder(), manager, configManager = configManager)
        val result = CompletableDeferred<Result<List<String>>>()
        controller.fetchModels("https://example.com", "key", "OpenAI兼容") { result.complete(it) }
        assertEquals(listOf("model-a"), result.await().getOrThrow())
        assertEquals(listOf(live, community), manager.capabilitiesFor("model-a"))

        controller.createMultipleConfigs("自定义", "https://example.com", "key", listOf("model-a"))
        controller.clearFetchedModels()
        advanceUntilIdle()

        val saved = slot<ApiConfig>()
        verify(exactly = 1) { configManager.addConfig(capture(saved), false) }
        assertEquals(256_000, saved.captured.modelParameters.maxContextTokens)
        assertEquals(16_000, saved.captured.maxTokens)
        assertEquals(ModelCapabilitySource.COMMUNITY_CATALOG, saved.captured.modelParameters.resolvedCapability?.maxOutputSource)
        assertEquals(setOf("high"), saved.captured.modelParameters.resolvedCapability?.reasoningEfforts)
        coVerify(exactly = 0) { ApiClient.getModelCapabilities(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `刷新立即持久化旧模型参数并保留手动值和其他组`() = runTest {
        mockkObject(ApiClient)
        for (isImageGen in listOf(false, true)) {
            val capability = ModelCapabilityCandidate(
                modelId = "model-a", protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                contextWindowTokens = 256_000, maxOutputTokens = 32_000,
                supportsReasoning = true, source = ModelCapabilitySource.COMMUNITY_CATALOG,
            )
            val old = imageConfig("id-a", "model-a", "我的模型").copy(
                modalityType = if (isImageGen) ModalityType.IMAGE else ModalityType.TEXT,
            ).withModelCapabilityDefaults(listOf(capability.copy(contextWindowTokens = 128_000, maxOutputTokens = 8_000)))
            val manual = old.copy(id = "manual", model = "manual").withUserTokenLimits(ModelTokenLimits(4_000, 64_000))
            val legacy = old.copy(id = "legacy", model = "legacy", maxTokens = 2_000, modelParameters = ModelParameters())
            val unrelated = old.copy(id = "other", address = "https://other.example")
            val state = ViewModelStateHolder()
            val configs = if (isImageGen) state._imageGenApiConfigs else state._apiConfigs
            val selected = if (isImageGen) state._selectedImageGenApiConfig else state._selectedApiConfig
            configs.value = listOf(old, manual, legacy, unrelated)
            selected.value = old
            coEvery { ApiClient.getModelCatalog(old.address, old.key, old.channel) } returns
                listOf(capability, capability.copy(modelId = "manual"), capability.copy(modelId = "legacy"))
            val persistence = mockk<DataPersistenceManager>(relaxed = true)
            val controller = controller(this, state, persistenceManager = persistence)

            controller.refreshModelsForConfig(old)
            state._showModelSelectionDialog.first { it }
            advanceUntilIdle()

            val updated = configs.value.first()
            assertEquals(256_000, updated.modelParameters.maxContextTokens)
            assertEquals(32_000, updated.maxTokens)
            assertEquals(old.name, updated.name)
            assertEquals(old.temperature, updated.temperature)
            assertEquals(updated, selected.value)
            assertEquals(4_000, configs.value[1].maxTokens)
            assertEquals(64_000, configs.value[1].modelParameters.maxContextTokens)
            assertEquals(2_000, configs.value[2].maxTokens)
            assertEquals(256_000, configs.value[2].modelParameters.maxContextTokens)
            assertEquals(unrelated, configs.value.last())
            coVerify(exactly = 1) { persistence.saveApiConfigs(configs.value, isImageGen) }
        }
    }

    @Test
    fun `刷新清除旧内置规格且pi缺失时不会把旧值当缓存`() = runTest {
        mockkObject(ApiClient)
        for (source in listOf(ModelCapabilitySource.PI_CATALOG, ModelCapabilitySource.COMMUNITY_CATALOG, null)) {
            val old = imageConfig("id-gpt", "gpt-5.6-sol", "GPT").copy(
                maxTokens = 128_000,
                modelParameters = ModelParameters(
                    maxContextTokens = 1_050_000,
                    resolvedCapability = com.android.everytalk.data.DataClass.ResolvedModelCapability(
                        modelId = "gpt-5.6-sol", endpointIdentity = "https://image.example",
                        contextWindowTokens = 1_050_000, maxInputTokens = 922_000, maxOutputTokens = 128_000,
                        contextWindowSource = ModelCapabilitySource.OFFICIAL_CATALOG,
                        maxInputSource = ModelCapabilitySource.OFFICIAL_CATALOG,
                        maxOutputSource = ModelCapabilitySource.OFFICIAL_CATALOG,
                        inputModalities = setOf("text", "image"), outputModalities = setOf("text"),
                        modalitiesSource = ModelCapabilitySource.OFFICIAL_CATALOG,
                        supportsReasoning = true, reasoningEfforts = setOf("max"),
                        reasoningSource = ModelCapabilitySource.OFFICIAL_CATALOG,
                    ),
                ),
            )
            val state = ViewModelStateHolder().apply {
                _imageGenApiConfigs.value = listOf(old)
                _selectedImageGenApiConfig.value = old
            }
            coEvery { ApiClient.getModelCatalog(old.address, old.key, old.channel) } returns listOf(
                ModelCapabilityCandidate(modelId = old.model, protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                    contextWindowTokens = 272_000.takeIf { source != null },
                    maxOutputTokens = 128_000.takeIf { source != null },
                    source = source ?: ModelCapabilitySource.LIVE_ENDPOINT),
            )
            val persistence = mockk<DataPersistenceManager>(relaxed = true)
            controller(this, state, persistenceManager = persistence).refreshModelsForConfig(old)
            state._showModelSelectionDialog.first { it }
            advanceUntilIdle()
            val updated = state._imageGenApiConfigs.value.single()
            assertEquals(if (source == null) 128_000 else 272_000, updated.modelParameters.maxContextTokens)
            assertEquals(source ?: ModelCapabilitySource.FAMILY_FALLBACK, updated.modelParameters.resolvedCapability?.contextWindowSource)
            assertEquals(null, updated.modelParameters.resolvedCapability?.maxInputTokens)
            assertEquals(updated, state._selectedImageGenApiConfig.value)
            coVerify(exactly = 1) { persistence.saveApiConfigs(listOf(updated), true) }
        }
    }

    @Test
    fun `刷新按单模型协议补齐参数且额外目录不改变平台模型列表`() = runTest {
        mockkObject(ApiClient)
        val base = imageConfig("id-a", "model-a", "model-a")
        val codex = base.copy(
            id = "id-b", model = "model-b",
            modelParameters = ModelParameters(apiProtocolOverride = ModelParameterProtocol.CODEX),
        )
        val candidate = ModelCapabilityCandidate(
            modelId = "model-a", protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            contextWindowTokens = 128_000, maxOutputTokens = 8_000,
            source = ModelCapabilitySource.LIVE_ENDPOINT,
        )
        coEvery { ApiClient.getModelCatalog(base.address, base.key, base.channel) } returns
            listOf(candidate, candidate.copy(modelId = "model-b"))
        coEvery { ApiClient.getModelCatalog(base.address, base.key, "Codex") } returns listOf(
            candidate.copy(modelId = "model-b", protocol = ModelParameterProtocol.CODEX, maxOutputTokens = 32_000),
            candidate.copy(modelId = "extra", protocol = ModelParameterProtocol.CODEX),
        )
        val state = ViewModelStateHolder().apply { _imageGenApiConfigs.value = listOf(base, codex) }
        val manager = ModelFetchManager()
        val controller = controller(this, state, manager)

        controller.refreshModelsForConfig(base)
        state._showModelSelectionDialog.first { it }
        advanceUntilIdle()

        assertEquals(8_000, state._imageGenApiConfigs.value[0].maxTokens)
        assertEquals(32_000, state._imageGenApiConfigs.value[1].maxTokens)
        assertEquals(ModelParameterProtocol.CODEX, state._imageGenApiConfigs.value[1].modelParameters.apiProtocolOverride)
        assertEquals(listOf("model-a", "model-b"), manager.fetchedModels.value)
        coVerify(exactly = 1) { ApiClient.getModelCatalog(base.address, base.key, "Codex") }
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
