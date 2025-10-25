package com.example.everytalk.statecontroller.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 管理导出功能
 */
class ExportManager {
    private val _exportRequest = Channel<Pair<String, String>>(Channel.BUFFERED)
    val exportRequest: Flow<Pair<String, String>> = _exportRequest.receiveAsFlow()
    
    private val _settingsExportRequest = Channel<Pair<String, String>>(Channel.BUFFERED)
    val settingsExportRequest: Flow<Pair<String, String>> = _settingsExportRequest.receiveAsFlow()
    
    suspend fun requestExport(fileName: String, content: String) {
        _exportRequest.send(Pair(fileName, content))
    }
    
    suspend fun requestSettingsExport(fileName: String, content: String) {
        _settingsExportRequest.send(Pair(fileName, content))
    }
}
