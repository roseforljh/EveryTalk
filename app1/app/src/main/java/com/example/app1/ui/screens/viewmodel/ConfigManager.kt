package com.example.app1.ui.screens.viewmodel

import com.example.app1.data.models.ApiConfig
import com.example.app1.ui.screens.USER_CANCEL_MESSAGE
import com.example.app1.ui.screens.viewmodel.data.DataPersistenceManager
import com.example.app1.ui.screens.viewmodel.state.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manages API Configuration operations: add, update, delete, clear, select.
 */
class ConfigManager(
    private val stateHolder: ViewModelStateHolder,
    private val persistenceManager: DataPersistenceManager,
    private val apiHandler: ApiHandler, // Needed to cancel API calls on config changes
    private val viewModelScope: CoroutineScope
) {

    fun addConfig(configToAdd: ApiConfig) {
        // Check for content duplicates (ignoring ID)
        val contentExists = stateHolder._apiConfigs.value.any { existingConfig ->
            existingConfig.address.trim().equals(configToAdd.address.trim(), ignoreCase = true) &&
                    existingConfig.key.trim() == configToAdd.key.trim() &&
                    existingConfig.model.trim()
                        .equals(configToAdd.model.trim(), ignoreCase = true) &&
                    existingConfig.provider.trim()
                        .equals(configToAdd.provider.trim(), ignoreCase = true)
        }

        if (!contentExists) {
            // Ensure unique ID if an ID collision somehow occurs
            val finalConfig = if (stateHolder._apiConfigs.value.any { it.id == configToAdd.id })
                configToAdd.copy(id = UUID.randomUUID().toString())
            else configToAdd

            stateHolder._apiConfigs.update { it + finalConfig }
            // Auto-select if it's the first config added
            if (stateHolder._selectedApiConfig.value == null) {
                stateHolder._selectedApiConfig.value = finalConfig
            }
            persistenceManager.saveApiConfigs()
            viewModelScope.launch { stateHolder._snackbarMessage.emit("Config '${finalConfig.model}' saved") }
            println("ConfigManager: Added config '${finalConfig.model}'.")
        } else {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("Configuration already exists") }
            println("ConfigManager: Config add attempt failed - duplicate content.")
        }
    }

    fun updateConfig(configToUpdate: ApiConfig) {
        var updated = false
        stateHolder._apiConfigs.update { currentConfigs ->
            val index = currentConfigs.indexOfFirst { it.id == configToUpdate.id }
            if (index != -1) {
                // Only update if content actually changed
                if (currentConfigs[index] != configToUpdate) {
                    val mutableConfigs = currentConfigs.toMutableList()
                    mutableConfigs[index] = configToUpdate
                    // If the updated config was the selected one, update the selected state too
                    if (stateHolder._selectedApiConfig.value?.id == configToUpdate.id) {
                        stateHolder._selectedApiConfig.value = configToUpdate
                    }
                    updated = true
                    println("ConfigManager: Updated config ID ${configToUpdate.id}.")
                    mutableConfigs // Return modified list
                } else {
                    println("ConfigManager: Config update skipped - content identical.")
                    currentConfigs // Return original list
                }
            } else {
                viewModelScope.launch { stateHolder._snackbarMessage.emit("Update failed: Config not found") }
                println("ConfigManager: Config update failed - ID ${configToUpdate.id} not found.")
                currentConfigs // Return original list
            }
        }
        if (updated) {
            persistenceManager.saveApiConfigs()
        }
    }

    fun deleteConfig(configToDelete: ApiConfig) {
        val wasSelected = stateHolder._selectedApiConfig.value?.id == configToDelete.id
        var deletedName: String? = null
        var newSelectedConfig: ApiConfig? = null

        stateHolder._apiConfigs.update { currentConfigs ->
            val index = currentConfigs.indexOfFirst { it.id == configToDelete.id }
            if (index != -1) {
                deletedName = currentConfigs[index].model // Get name for snackbar message
                val mutableConfigs = currentConfigs.toMutableList()
                mutableConfigs.removeAt(index)
                println("ConfigManager: Deleted config ID ${configToDelete.id}.")

                // If the deleted config was selected, cancel API calls and select the first available config
                if (wasSelected) {
                    apiHandler.cancelCurrentApiJob("Selected config deleted")
                    newSelectedConfig = mutableConfigs.firstOrNull()
                    stateHolder._selectedApiConfig.value =
                        newSelectedConfig // Update selected state
                    println("ConfigManager: Deleted selected config. New selection: ${newSelectedConfig?.model}")
                }
                mutableConfigs // Return modified list
            } else {
                viewModelScope.launch { stateHolder._snackbarMessage.emit("Delete failed: Config not found") }
                println("ConfigManager: Config delete failed - ID ${configToDelete.id} not found.")
                currentConfigs // Return original list
            }
        }

        if (deletedName != null) {
            persistenceManager.saveApiConfigs() // Persist the deletion
            viewModelScope.launch { // Show snackbar feedback
                if (wasSelected) {
                    stateHolder._snackbarMessage.emit("Selected config '$deletedName' deleted")
                    delay(250) // Short delay for sequential messages
                    if (newSelectedConfig == null) {
                        stateHolder._snackbarMessage.emit("Please add or select a new API config")
                    } else {
                        stateHolder._snackbarMessage.emit("Auto-selected: ${newSelectedConfig.model} (${newSelectedConfig.provider})")
                    }
                } else {
                    stateHolder._snackbarMessage.emit("Config '$deletedName' deleted")
                }
            }
        }
    }

    fun clearAllConfigs() {
        if (stateHolder._apiConfigs.value.isNotEmpty()) {
            apiHandler.cancelCurrentApiJob("Clearing all configs") // Cancel API calls
            stateHolder._apiConfigs.value = emptyList()
            stateHolder._selectedApiConfig.value = null
            persistenceManager.saveApiConfigs() // Persist the clearing
            println("ConfigManager: Cleared all configs.")
            viewModelScope.launch {
                stateHolder._snackbarMessage.emit("All configurations cleared")
                delay(250)
                stateHolder._snackbarMessage.emit("Please add an API configuration")
            }
        } else {
            viewModelScope.launch { stateHolder._snackbarMessage.emit("No configurations to clear") }
            println("ConfigManager: No configs to clear.")
        }
    }

    fun selectConfig(config: ApiConfig) {
        if (stateHolder._selectedApiConfig.value?.id != config.id) {
            apiHandler.cancelCurrentApiJob("Switching selected config") // Cancel API calls
            stateHolder._selectedApiConfig.value = config
            persistenceManager.saveApiConfigs() // Persist the selection change
            stateHolder._showSettingsDialog.value = false // Assume this closes the dialog
            println("ConfigManager: Selected config: ${config.model} (${config.provider}).")
            viewModelScope.launch { stateHolder._snackbarMessage.emit("Selected: ${config.model} (${config.provider})") }
        } else {
            stateHolder._showSettingsDialog.value =
                false // Close dialog even if selection didn't change
            println("ConfigManager: Config already selected.")
        }
    }
}