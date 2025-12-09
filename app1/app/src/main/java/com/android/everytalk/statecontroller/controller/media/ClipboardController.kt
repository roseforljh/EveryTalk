package com.android.everytalk.statecontroller.controller.media

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import com.android.everytalk.statecontroller.viewmodel.ExportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 轻职责控制器：剪贴板与对话文本导出
 * - 复制文本到系统剪贴板
 * - 触发导出请求（文件名固定为 conversation_export.md）
 */
class ClipboardController(
    private val application: Application,
    private val exportManager: ExportManager,
    private val scope: CoroutineScope,
    private val showSnackbar: (String) -> Unit
) {

    fun copyToClipboard(text: String) {
        val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("Copied Text", text)
        clipboard.setPrimaryClip(clip)
        showSnackbar("已复制到剪贴板")
    }

    fun exportMessageText(text: String) {
        scope.launch {
            val fileName = "conversation_export.md"
            exportManager.requestExport(fileName, text)
        }
    }
}