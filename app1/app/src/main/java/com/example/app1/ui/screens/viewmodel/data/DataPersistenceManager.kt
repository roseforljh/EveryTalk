package com.example.app1.ui.screens.viewmodel.data

import android.util.Log
import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.models.ApiConfig
import com.example.app1.data.models.Message
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DataPersistenceManager(
    private val dataSource: SharedPreferencesDataSource,
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope
) {

    fun loadInitialData(onLoadingComplete: (initialConfigPresent: Boolean, initialHistoryPresent: Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            // ... (前面的加载逻辑) ...
            val loadedConfigs: List<ApiConfig> = dataSource.loadApiConfigs()
            stateHolder._apiConfigs.value = loadedConfigs
            val initialConfigPresent = loadedConfigs.isNotEmpty()
            Log.d("DataPersistenceManager", "Loaded API configs. Count: ${loadedConfigs.size}")

            val selectedConfigId: String? = dataSource.loadSelectedConfigId()
            Log.d("DataPersistenceManager", "Loaded selected config ID: $selectedConfigId")

            var selectedConfig: ApiConfig? = null
            if (selectedConfigId != null) {
                selectedConfig = loadedConfigs.find { it.id == selectedConfigId }
                if (selectedConfig == null) {
                    Log.w(
                        "DataPersistenceManager",
                        "Saved selected config ID '$selectedConfigId' not found in loaded list. Clearing saved selection."
                    )
                    dataSource.saveSelectedConfigId(null)
                }
            }

            var selectionUpdated = false
            if (selectedConfig == null && loadedConfigs.isNotEmpty()) {
                selectedConfig = loadedConfigs.first()
                // 使用 model 和 provider
                Log.i(
                    "DataPersistenceManager",
                    "No valid selection found, defaulting to first config: Model='${selectedConfig.model}', Provider='${selectedConfig.provider}' (ID: ${selectedConfig.id})."
                )
                dataSource.saveSelectedConfigId(selectedConfig.id)
                selectionUpdated = true
            } else if (selectedConfig == null) { // Covers case where loadedConfigs is empty OR selectedConfigId was invalid
                Log.i(
                    "DataPersistenceManager",
                    "No configs loaded or no valid selection persisted, selected config remains null."
                )
                if (selectedConfigId != null) { // If there was a previously saved ID that is now invalid because no config matches or list is empty
                    dataSource.saveSelectedConfigId(null) // Clear the stale ID
                    selectionUpdated =
                        true // The selection state effectively changed by removing the invalid ID
                    Log.d(
                        "DataPersistenceManager",
                        "Cleared stale selectedConfigId: $selectedConfigId as no matching config was found or config list is empty."
                    )
                }
            }


            if (selectionUpdated || stateHolder._selectedApiConfig.value?.id != selectedConfig?.id) {
                stateHolder._selectedApiConfig.value = selectedConfig
            }

            // 使用 model 和 provider
            if (selectedConfig != null) Log.d(
                "DataPersistenceManager",
                "Final initial selected config: Model='${selectedConfig.model}', Provider='${selectedConfig.provider}' (ID: ${selectedConfig.id})."
            )
            else Log.d("DataPersistenceManager", "Final initial selected config is null.")

            // ... (加载聊天记录等后续逻辑) ...

            val loadedHistory: List<List<Message>> = dataSource.loadChatHistory()
            stateHolder._historicalConversations.value = loadedHistory
            val initialHistoryPresent = loadedHistory.isNotEmpty()
            Log.d(
                "DataPersistenceManager",
                "Initial history loaded (${loadedHistory.size} conversations)."
            )

            stateHolder._loadedHistoryIndex.value = null

            withContext(Dispatchers.Main) {
                onLoadingComplete(initialConfigPresent, initialHistoryPresent)
            }
        }
    }

    // ... (其他方法，包括 clearAllChatHistory, saveApiConfigs, saveChatHistory, saveSelectedConfigIdentifier, clearAllApiConfigData, clearAllApplicationData) ...
    // clearAllApplicationData 函数如果未使用，可以保留或删除，取决于你的需求。

    fun clearAllChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            dataSource.clearChatHistory()
            Log.d("DataPersistenceManager", "All chat history cleared from persistence.")
        }
    }

    fun saveApiConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfigs = stateHolder._apiConfigs.value
            dataSource.saveApiConfigs(currentConfigs)
            Log.d("DataPersistenceManager", "API configs list saved. Count: ${currentConfigs.size}")
        }
    }

    fun saveChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentHistory = stateHolder._historicalConversations.value
            Log.d(
                "DataPersistenceManager",
                "Saving chat history (${currentHistory.size} conversations)..."
            )
            dataSource.saveChatHistory(currentHistory)
        }
    }

    fun saveSelectedConfigIdentifier(configId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            dataSource.saveSelectedConfigId(configId)
            Log.d("DataPersistenceManager", "Saved selected config identifier: $configId")
        }
    }

    fun clearAllApiConfigData() {
        viewModelScope.launch(Dispatchers.IO) {
            dataSource.clearApiConfigs()
            dataSource.saveSelectedConfigId(null)
            Log.d("DataPersistenceManager", "Cleared all API config data (list and selection).")
        }
    }

    fun clearAllApplicationData() { // 这个函数如果没用到，IDE会提示
        viewModelScope.launch(Dispatchers.IO) {
            dataSource.clearAllData()
            Log.d("DataPersistenceManager", "Cleared ALL application data from persistence.")
        }
    }
}