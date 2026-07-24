package com.android.everytalk.ui.screens.viewmodel
import com.android.everytalk.statecontroller.*

import android.content.Context
import android.util.Log
import com.android.everytalk.data.DataClass.ApiConfig
import java.io.File
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.database.RoomDataSource
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.ConversationScrollState
import com.android.everytalk.statecontroller.rethrowIfCancellation
import com.android.everytalk.statecontroller.safeApiConfigSummary
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.VoiceBackendConfig
import com.android.everytalk.ui.components.toRecoveredMarkdown
import com.android.everytalk.ui.components.MarkdownPart
import com.android.everytalk.util.ConversationNameHelper
import com.android.everytalk.util.message.findMarkdownImageReferences
import com.android.everytalk.util.message.replaceMarkdownImageSources
import com.android.everytalk.util.storage.FileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import coil3.ImageLoader
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Files

internal suspend fun DataPersistenceManager.deleteMediaFilesForMessagesInternal(conversations: List<List<Message>>) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting deletion of media files for ${conversations.size} conversations.")
            var deletedFilesCount = 0
            // 正文只是展示内容，不具备授权删除本地文件的语义。
            val candidates = collectMediaDeletionCandidates(conversations.flatten())
            val allowedMediaDirectories = listOf(
                File(context.filesDir, "chat_attachments"),
                File(context.filesDir, "chat_images"),
                File(context.filesDir, "chat_images_temp"),
                File(context.cacheDir, "preview_cache"),
                File(context.cacheDir, "share_images"),
            )
            val resolvedCandidates = candidates.localSources.map { source ->
                source to resolveOwnedMediaFile(source, allowedMediaDirectories)
            }
            val filesToDelete = resolvedCandidates
                .mapNotNull { (_, file) -> file }
                .toSet()

            val rejectedSources = resolvedCandidates.count { (_, file) -> file == null }
            if (rejectedSources > 0) {
                Log.w(TAG, "Skipped $rejectedSources media sources outside app-owned directories or invalid paths")
            }

            // 删除文件
            filesToDelete.forEach { file ->
                try {
                    if (file.delete()) {
                        Log.d(TAG, "Successfully deleted media file: ${file.path}")
                        deletedFilesCount++
                    } else {
                        Log.w(TAG, "Failed to delete media file: ${file.path}")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception deleting media file: ${file.path}", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting media file: ${file.path}", e)
                }
            }

            // 清理图片缓存
            candidates.localSources.forEach { source ->
                imageLoader.diskCache?.remove(source)
            }
            filesToDelete.forEach { file ->
                imageLoader.diskCache?.remove(file.path)
                imageLoader.diskCache?.remove(file.toURI().toString())
            }
            candidates.remoteUrls.forEach { url ->
                imageLoader.diskCache?.remove(url)
            }

            Log.d(TAG, "Finished media file deletion. Total files deleted: $deletedFilesCount")
        }
    }

    /**
     * 清理孤立的附件文件与临时缓存（已删除会话但文件仍存在的情况），并回收图片缓存
     *
     * 覆盖范围：
     * - filesDir/chat_attachments 中不再被引用的图片文件
     * - cacheDir/preview_cache 预览生成的临时文件
     * - cacheDir/share_images 分享生成的临时文件
     * - Coil 内存/磁盘缓存
     *
     * 调用时机：清空历史、大批删除后
     */
internal suspend fun DataPersistenceManager.cleanupOrphanedAttachmentsInternal(vacuumDatabase: Boolean = false) {
        withContext(Dispatchers.IO) {
            try {
                val chatAttachmentsDir = File(context.filesDir, "chat_attachments")
                val previewCacheDir = File(context.cacheDir, "preview_cache")
                val shareImagesDir = File(context.cacheDir, "share_images")

                // 1) 收集当前所有会话中被引用的图片路径
                val referencedPaths = mutableSetOf<String>()

                // 从文本历史会话收集
                val textHistoryResult = roomDataSource.loadChatHistoryResult()
                textHistoryResult.sessions.forEach { loadedSession ->
                    referencedPaths += collectReferencedAttachmentPaths(loadedSession.messages)
                }

                // 从图像生成历史会话收集
                val imageHistoryResult = roomDataSource.loadImageGenerationHistoryResult()
                imageHistoryResult.sessions.forEach { loadedSession ->
                    referencedPaths += collectReferencedAttachmentPaths(loadedSession.messages)
                }

                if (textHistoryResult.failedSessionIds.isNotEmpty() || imageHistoryResult.failedSessionIds.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "cleanupOrphanedAttachments: 历史加载不完整，跳过附件清理以避免误删",
                    )
                    return@withContext
                }
                
                // 从最后打开的会话收集
                referencedPaths += collectReferencedAttachmentPaths(roomDataSource.loadLastOpenChat())
                referencedPaths += collectReferencedAttachmentPaths(roomDataSource.loadLastOpenImageGenerationChat())
                
                Log.d(TAG, "cleanupOrphanedAttachments: Found ${referencedPaths.size} referenced files")

                // 2) 清理 chat_attachments 中不再被引用的文件
                var orphanedCount = 0
                if (chatAttachmentsDir.exists()) {
                    chatAttachmentsDir.listFiles()?.forEach { file ->
                        val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
                            ?: return@forEach
                        if (file.isFile && canonicalPath !in referencedPaths) {
                            try {
                                if (file.delete()) {
                                    orphanedCount++
                                    Log.d(TAG, "Deleted orphaned file: ${file.absolutePath}")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to delete orphaned file: ${file.absolutePath}", e)
                            }
                        }
                    }
                }
                Log.i(TAG, "cleanupOrphanedAttachments: Deleted $orphanedCount orphaned files from chat_attachments")

                // 3) 清空预览/分享产生的临时缓存
                fun clearCacheDir(dir: File, label: String): Int {
                    if (!dir.exists()) return 0
                    var count = 0
                    dir.listFiles()?.forEach { f ->
                        try {
                            if (f.isFile) {
                                if (f.delete()) count++
                            } else {
                                if (f.deleteRecursively()) count++
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete cache file in $label: ${f.absolutePath}", e)
                        }
                    }
                    Log.d(TAG, "Cleared $count files from $label")
                    return count
                }
                val clearedPreview = clearCacheDir(previewCacheDir, "preview_cache")
                val clearedShare = clearCacheDir(shareImagesDir, "share_images")

                // 4) 统一清理 Coil 内存/磁盘缓存
                runCatching {
                    imageLoader.memoryCache?.clear()
                    Log.d(TAG, "Coil memory cache cleared")
                }.onFailure { e -> Log.w(TAG, "Failed to clear Coil memory cache", e) }

                runCatching {
                    imageLoader.diskCache?.clear()
                    Log.d(TAG, "Coil disk cache cleared")
                }.onFailure { e -> Log.w(TAG, "Failed to clear Coil disk cache", e) }

                // 5) VACUUM 仅用于批量清空，避免单条删除触发全库重写。
                if (vacuumDatabase) {
                    runCatching {
                        roomDataSource.vacuumDatabase()
                        Log.d(TAG, "Database VACUUM completed")
                    }.onFailure { e -> Log.w(TAG, "Failed to VACUUM database", e) }
                }

                Log.i(TAG, "Cleanup completed. Deleted $orphanedCount orphaned files. Cleared preview=$clearedPreview, share=$clearedShare cache files.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error during orphaned file cleanup", e)
            }
        }
    }

    // ========= 置顶集合：文本与图像 =========
