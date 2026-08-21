package com.android.everytalk.statecontroller.viewmodel

import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 管理导出功能
 */
class ExportManager {
    private val _exportRequest = Channel<Pair<String, String>>(Channel.BUFFERED)
    val exportRequest: Flow<Pair<String, String>> = _exportRequest.receiveAsFlow()
    
    private val _settingsExportRequest = Channel<SettingsExportRequest>(Channel.BUFFERED)
    val settingsExportRequest: Flow<SettingsExportRequest> = _settingsExportRequest.receiveAsFlow()
    
    suspend fun requestExport(fileName: String, content: String) {
        _exportRequest.send(Pair(fileName, content))
    }
    
    suspend fun requestSettingsExport(fileName: String, file: File) {
        _settingsExportRequest.send(SettingsExportRequest(fileName, file))
    }
}

/** 设置备份先落到缓存文件，避免巨型 JSON 字符串长期占用堆内存。 */
data class SettingsExportRequest(
    val fileName: String,
    val file: File,
)
