package com.example.app1.ui.screens.viewmodel.data

import com.example.app1.data.local.SharedPreferencesDataSource
import com.example.app1.data.models.ApiConfig // 确保指向正确的包
import com.example.app1.data.models.Message
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext // 导入 withContext

/**
 * Manages loading and saving data using SharedPreferencesDataSource.
 * Acts as an intermediary between ViewModel state and the DataSource.
 */
class DataPersistenceManager(
    private val dataSource: SharedPreferencesDataSource,
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope
) {

    /**
     * Loads initial data (API configs, selected config, chat history) from DataSource
     * and updates the ViewModelStateHolder.
     * Calls onLoadingComplete on the Main thread when finished.
     */
    fun loadInitialData(onLoadingComplete: (initialConfigPresent: Boolean, initialHistoryPresent: Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { // Perform loading on IO thread
            // 1. Load API Configs
            val loadedConfigs: List<ApiConfig> = dataSource.loadApiConfigs()
            stateHolder._apiConfigs.value = loadedConfigs // Update state holder
            val initialConfigPresent = loadedConfigs.isNotEmpty()

            // 2. Load Selected Config ID
            val selectedConfigId: String? = dataSource.loadSelectedConfigId()

            // 3. Find Selected Config in the loaded list
            var selectedConfig: ApiConfig? = null
            if (selectedConfigId != null) {
                selectedConfig = loadedConfigs.find { it.id == selectedConfigId } // 在加载的列表中查找
                if (selectedConfig == null) {
                    // Saved ID exists but config not found (maybe deleted) - clear the saved ID
                    println("DataPersistenceManager: Saved selected config ID '$selectedConfigId' not found in loaded list. Clearing saved selection.")
                    dataSource.saveSelectedConfigId(null) // 清除无效的 ID
                }
            }

            // 4. If no valid selection, default to the first config if available
            var selectionUpdated = false // 标记选择是否因为找不到或默认设置而改变
            if (selectedConfig == null && loadedConfigs.isNotEmpty()) {
                selectedConfig = loadedConfigs.first()
                // --- **修改点 1: 修正日志打印** ---
                println("DataPersistenceManager: No valid selection found, defaulting to first config: '${selectedConfig.model}' (ID: ${selectedConfig.id}).") // 使用 model 和 id
                // --- End 修改点 1 ---
                // Save this default selection immediately so it persists if the app closes now
                dataSource.saveSelectedConfigId(selectedConfig.id)
                selectionUpdated = true
            } else if (selectedConfig == null) {
                println("DataPersistenceManager: No configs loaded, selected config remains null.")
                // Ensure no stale ID is saved if list is empty
                if (selectedConfigId != null) {
                    dataSource.saveSelectedConfigId(null)
                    selectionUpdated = (selectedConfigId != null) // If we cleared an ID, selection 'changed'
                }
            }

            // 5. Update ViewModel State only if needed
            // 只有在选择确实发生变化时才更新 StateFlow，避免不必要的重组
            if (selectionUpdated || stateHolder._selectedApiConfig.value?.id != selectedConfig?.id) {
                stateHolder._selectedApiConfig.value = selectedConfig // 更新选中状态
            }
            // --- **修改点 2: 修正日志打印** ---
            if(selectedConfig != null) println("DataPersistenceManager: Final initial selected config: '${selectedConfig.model}' (ID: ${selectedConfig.id}).")
            else println("DataPersistenceManager: Final initial selected config is null.")
            // --- End 修改点 2 ---


            // 6. Load Chat History
            val loadedHistory: List<List<Message>> = dataSource.loadChatHistory()
            stateHolder._historicalConversations.value = loadedHistory // Update state holder
            val initialHistoryPresent = loadedHistory.isNotEmpty()
            println("DataPersistenceManager: Initial history loaded (${loadedHistory.size} conversations).")

            // 7. Set initial loaded history index (always null on fresh load)
            stateHolder._loadedHistoryIndex.value = null

            // 8. Call completion callback on the Main thread
            withContext(Dispatchers.Main) {
                onLoadingComplete(initialConfigPresent, initialHistoryPresent)
            }
        }
    }

    /**
     * Saves the current API config list from the StateHolder to SharedPreferences.
     */
    fun saveApiConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentConfigs = stateHolder._apiConfigs.value
            dataSource.saveApiConfigs(currentConfigs)
            println("DataPersistenceManager: API configs list saved.")
        }
    }

    /**
     * Saves the current chat history list from the StateHolder to SharedPreferences.
     */
    fun saveChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentHistory = stateHolder._historicalConversations.value
            println("DataPersistenceManager: Saving chat history (${currentHistory.size} conversations)...")
            dataSource.saveChatHistory(currentHistory)
        }
    }

    /**
     * Saves the currently selected API config ID to SharedPreferences.
     * @param configId The ID of the selected config, or null to clear selection.
     */
    fun saveSelectedConfigIdentifier(configId: String?) {
        viewModelScope.launch(Dispatchers.IO) { // Perform save on IO thread
            dataSource.saveSelectedConfigId(configId)
            println("DataPersistenceManager: Saved selected config identifier: $configId")
        }
    }

    /**
     * Clears all API configurations and the saved selected config ID from SharedPreferences.
     * Uses the DataSource's clearAllData for efficiency if available, otherwise clears individually.
     */
    fun clearAllApiConfigData() {
        viewModelScope.launch(Dispatchers.IO) {
            dataSource.clearAllData() // Prefer using a single clear operation if DataSource supports it
            println("DataPersistenceManager: Cleared all API config data (list and selection).")
            // Note: This example assumes clearAllData in DataSource clears *all* keys.
            // If not, you might need separate calls to clearApiConfigs and saveSelectedConfigId(null).
        }
    }
}