package com.android.everytalk.ui.screens.viewmodel

import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.statecontroller.ApiHandler
import com.android.everytalk.statecontroller.ViewModelStateHolder
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ConfigManagerConversationBindingTest {

    @Test
    fun `删除单项时会话绑定迁移到同组剩余配置`() = runTest(UnconfinedTestDispatcher()) {
        val deleted = config("deleted", "model-a")
        val sameGroup = config("same-group", "model-b")
        val unrelated = config("unrelated", "model-c", address = "https://other.example")
        val stateHolder = ViewModelStateHolder().apply {
            _apiConfigs.value = listOf(deleted, sameGroup, unrelated)
            _selectedApiConfig.value = unrelated
            conversationApiConfigIds.value = mapOf("conversation" to deleted.id)
        }
        val persistenceManager = mockk<DataPersistenceManager>(relaxed = true)
        val manager = ConfigManager(
            stateHolder = stateHolder,
            persistenceManager = persistenceManager,
            apiHandler = mockk<ApiHandler>(relaxed = true),
            viewModelScope = this,
        )

        manager.deleteConfig(deleted)
        advanceUntilIdle()

        assertEquals(listOf(sameGroup, unrelated), stateHolder._apiConfigs.value)
        assertEquals(mapOf("conversation" to sameGroup.id), stateHolder.conversationApiConfigIds.value)
        coVerify { persistenceManager.saveConversationApiConfigIds(mapOf("conversation" to sameGroup.id)) }
    }

    @Test
    fun `删除整组时选中配置和会话绑定迁移到有效配置`() = runTest(UnconfinedTestDispatcher()) {
        val groupA = config("group-a", "model-a")
        val groupB = config("group-b", "model-b")
        val retained = config("retained", "model-c", address = "https://other.example")
        val stateHolder = ViewModelStateHolder().apply {
            _apiConfigs.value = listOf(groupA, groupB, retained)
            _selectedApiConfig.value = groupB
            conversationApiConfigIds.value = mapOf("conversation" to groupA.id)
        }
        val persistenceManager = mockk<DataPersistenceManager>(relaxed = true)
        val manager = ConfigManager(
            stateHolder = stateHolder,
            persistenceManager = persistenceManager,
            apiHandler = mockk<ApiHandler>(relaxed = true),
            viewModelScope = this,
        )

        manager.deleteConfigGroup(groupA)
        advanceUntilIdle()

        assertEquals(listOf(retained), stateHolder._apiConfigs.value)
        assertEquals(retained, stateHolder._selectedApiConfig.value)
        assertEquals(mapOf("conversation" to retained.id), stateHolder.conversationApiConfigIds.value)
        coVerify { persistenceManager.saveConversationApiConfigIds(mapOf("conversation" to retained.id)) }
    }

    @Test
    fun `删除勾选的已下架模型时保留同组其他配置`() = runTest(UnconfinedTestDispatcher()) {
        val removed = config("removed", "model-removed")
        val retained = config("retained", "model-retained")
        val unrelated = config("unrelated", "model-other", address = "https://other.example")
        val stateHolder = ViewModelStateHolder().apply {
            _apiConfigs.value = listOf(removed, retained, unrelated)
            _selectedApiConfig.value = removed
            conversationApiConfigIds.value = mapOf("conversation" to removed.id)
        }
        val persistenceManager = mockk<DataPersistenceManager>(relaxed = true)
        val manager = ConfigManager(
            stateHolder = stateHolder,
            persistenceManager = persistenceManager,
            apiHandler = mockk<ApiHandler>(relaxed = true),
            viewModelScope = this,
        )

        manager.deleteModelsFromConfigGroup(removed, listOf("MODEL-REMOVED"))
        advanceUntilIdle()

        assertEquals(listOf(retained, unrelated), stateHolder._apiConfigs.value)
        assertEquals(retained, stateHolder._selectedApiConfig.value)
        assertEquals(mapOf("conversation" to retained.id), stateHolder.conversationApiConfigIds.value)
        coVerify { persistenceManager.saveApiConfigs(listOf(retained, unrelated), false) }
    }

    @Test
    fun `清空文本配置不会清除图像配置及其会话绑定`() = runTest(UnconfinedTestDispatcher()) {
        val text = config("text", "text-model")
        val image = config("image", "image-model", modalityType = ModalityType.IMAGE)
        val stateHolder = ViewModelStateHolder().apply {
            _apiConfigs.value = listOf(text)
            _selectedApiConfig.value = text
            _imageGenApiConfigs.value = listOf(image)
            _selectedImageGenApiConfig.value = image
            conversationApiConfigIds.value = mapOf(
                "text-conversation" to text.id,
                "image-conversation" to image.id,
            )
        }
        val persistenceManager = mockk<DataPersistenceManager>(relaxed = true)
        val manager = ConfigManager(
            stateHolder = stateHolder,
            persistenceManager = persistenceManager,
            apiHandler = mockk<ApiHandler>(relaxed = true),
            viewModelScope = this,
        )

        manager.clearAllConfigs(isImageGen = false)
        advanceUntilIdle()

        assertTrue(stateHolder._apiConfigs.value.isEmpty())
        assertEquals(listOf(image), stateHolder._imageGenApiConfigs.value)
        assertEquals(mapOf("image-conversation" to image.id), stateHolder.conversationApiConfigIds.value)
        coVerify { persistenceManager.saveApiConfigs(emptyList(), false) }
        coVerify { persistenceManager.saveConversationApiConfigIds(mapOf("image-conversation" to image.id)) }
    }

    @Test
    fun `图片模型选择沿用主会话且默认配置不能单删或成组清空`() = runTest(UnconfinedTestDispatcher()) {
        val defaults = com.android.everytalk.data.DataClass.builtinImageModels
        val state = ViewModelStateHolder().apply {
            setCurrentConversationId("same-chat")
            _imageGenApiConfigs.value = defaults
        }
        val manager = ConfigManager(state, mockk(relaxed = true), mockk(relaxed = true), this)
        manager.selectConfig(defaults.first(), true)
        advanceUntilIdle()
        assertEquals(defaults.first(), state._selectedApiConfig.value)
        assertEquals(defaults.first().id, state.conversationApiConfigIds.value["same-chat"])
        manager.deleteConfig(defaults.first(), true)
        manager.deleteConfigGroup(defaults.first(), true)
        manager.clearAllConfigs(true)
        advanceUntilIdle()
        assertEquals(defaults, state._imageGenApiConfigs.value)
        assertEquals("same-chat", state._currentConversationId.value)
    }

    @Test
    fun `图片配置编辑删除和文本清空同步主聊天选择`() = runTest(UnconfinedTestDispatcher()) {
        val text = config("text", "text-model")
        val image = config("image", "image-model", modalityType = ModalityType.IMAGE)
        val fallback = com.android.everytalk.data.DataClass.builtinImageModels.first()
        val state = ViewModelStateHolder().apply {
            _apiConfigs.value = listOf(text)
            _imageGenApiConfigs.value = listOf(image, fallback)
            _selectedApiConfig.value = image
            _selectedImageGenApiConfig.value = image
            conversationApiConfigIds.value = mapOf("same-chat" to image.id)
        }
        val persistence = mockk<DataPersistenceManager>(relaxed = true)
        val manager = ConfigManager(state, persistence, mockk(relaxed = true), this)

        val edited = image.copy(name = "修改后的图片模型")
        manager.updateConfig(edited, true)
        advanceUntilIdle()
        assertEquals(edited, state._selectedApiConfig.value)
        manager.clearAllConfigs(false)
        advanceUntilIdle()
        assertEquals(edited, state._selectedApiConfig.value)

        manager.updateConfigGroup(edited, "new-provider", edited.address, edited.key, edited.channel, true)
        advanceUntilIdle()
        assertEquals("new-provider", state._selectedApiConfig.value?.provider)
        manager.deleteConfig(state._selectedApiConfig.value!!, true)
        advanceUntilIdle()
        assertEquals(fallback, state._selectedApiConfig.value)
        assertEquals(fallback.id, state.conversationApiConfigIds.value["same-chat"])
        coVerify { persistence.saveSelectedConfigIdentifier(fallback.id, false) }

        // 批量删除走另一条公共入口，也必须更新主聊天的当前模型。
        state._imageGenApiConfigs.value = listOf(image, fallback)
        state._selectedApiConfig.value = image
        state._selectedImageGenApiConfig.value = image
        manager.deleteConfigGroup(image, true)
        advanceUntilIdle()
        assertEquals(fallback, state._selectedApiConfig.value)
    }

    private fun config(
        id: String,
        model: String,
        address: String = "https://api.example",
        modalityType: ModalityType = ModalityType.TEXT,
    ) = ApiConfig(
        id = id,
        address = address,
        key = "key",
        model = model,
        provider = "provider",
        name = model,
        channel = "OpenAI兼容",
        modalityType = modalityType,
    )
}
