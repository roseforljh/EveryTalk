package com.example.app1.ui.screens.viewmodel.data

import com.example.app1.data.local.SharedPreferencesDataSource
// --- 修改这个导入语句 ---
import com.example.app1.data.models.ApiConfig // <--- 指向正确的 network 包
// --- 其他导入 ---
import com.example.app1.data.models.Message // 假设 Message 在 models 包下
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manages loading and saving data using SharedPreferencesDataSource.
 */
class DataPersistenceManager(
    private val dataSource: SharedPreferencesDataSource,
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope
) {

    fun loadInitialData(onLoadingComplete: (initialConfigPresent: Boolean, initialHistoryPresent: Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            // Load Configs
            val loadedConfigs: List<ApiConfig> = dataSource.loadApiConfigs()
            stateHolder._apiConfigs.value = loadedConfigs
            val selectedConfigId: String? = dataSource.loadSelectedConfigId()
            // 现在编译器应该能正确找到 network 包下的 ApiConfig 的 id 了
            val selectedConfig = loadedConfigs.find { it.id == selectedConfigId } ?: loadedConfigs.firstOrNull()
            stateHolder._selectedApiConfig.value = selectedConfig
            println("DataPersistenceManager: Initial API configs loaded (${loadedConfigs.size}). Selected ID: $selectedConfigId")

            // Load History
            val loadedHistory: List<List<Message>> = dataSource.loadChatHistory()
            stateHolder._historicalConversations.value = loadedHistory
            println("DataPersistenceManager: Initial history loaded (${loadedHistory.size} conversations).")

            onLoadingComplete(selectedConfig != null || loadedConfigs.isNotEmpty(), loadedHistory.isNotEmpty())
        }
    }

    fun saveApiConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfigs = stateHolder._apiConfigs.value
            dataSource.saveApiConfigs(currentConfigs)
            println("DataPersistenceManager: API configs saved.")
        }
    }

    fun saveChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentHistory = stateHolder._historicalConversations.value
            println("DataPersistenceManager: Saving chat history (${currentHistory.size} conversations)...")
            dataSource.saveChatHistory(currentHistory)
        }
    }
}