package com.android.everytalk.util.storage

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import com.android.everytalk.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** 数据管理页展示的占用类型。 */
enum class StorageDetailType {
    CONVERSATIONS,
    ATTACHMENTS,
    SKILLS,
    TOOL_RESULTS,
    TEMPORARY_FILES,
    OTHER_DATA,
}

/** 一项用户能看懂的数据占用。 */
data class StorageDetail(
    val type: StorageDetailType,
    val bytes: Long,
    val cleanable: Boolean = false,
)

/** App 当前真实可见的空间占用快照。 */
data class AppStorageSnapshot(
    val applicationBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    val details: List<StorageDetail>,
) {
    init {
        require(details.sumOf(StorageDetail::bytes) == dataBytes) {
            "数据明细必须与数据总占用完全一致"
        }
    }

    val totalBytes: Long = applicationBytes + dataBytes + cacheBytes
    val cleanableBytes: Long = cacheBytes + details
        .filter(StorageDetail::cleanable)
        .sumOf(StorageDetail::bytes)
}

/**
 * 统计并清理 EveryTalk 自己的文件。
 *
 * 统计只读取 App 私有目录。清理只处理系统缓存目录和拍照产生的临时文件，
 * 会话、附件、技能、工具结果和连接凭据都不会被删除。
 */
class AppStorageManager(context: Context) {
    private val appContext = context.applicationContext

    suspend fun scan(): AppStorageSnapshot = withContext(Dispatchers.IO) {
        val dataRoot = appContext.dataDir
        val cacheRoots = listOfNotNull(appContext.cacheDir, appContext.externalCacheDir).distinctFiles()
        val codeCacheBytes = directorySize(appContext.codeCacheDir)
        val fallbackCacheBytes = cacheRoots.sumOf(::directorySize) + codeCacheBytes

        val applicationInfo = appContext.applicationInfo
        val fallbackApplicationBytes = (listOf(applicationInfo.sourceDir) + applicationInfo.splitSourceDirs.orEmpty())
            .map(::File)
            .distinctFiles()
            .sumOf(::directorySize)

        val databaseBytes = directorySize(File(dataRoot, "databases"))
        val attachmentBytes = listOf(
            File(appContext.filesDir, FileManager.CHAT_ATTACHMENTS_DIR),
            File(appContext.filesDir, "chat_images"),
        ).sumOf(::directorySize)
        val skillBytes = directorySize(File(appContext.filesDir, "skills"))
        val toolResultBytes = directorySize(File(appContext.filesDir, "agent_tool_results"))
        val reclaimableSyncBytes = reclaimableSyncCacheFiles(appContext).sumOf(::directorySize)
        val temporaryBytes = directorySize(File(appContext.filesDir, "chat_images_temp")) + reclaimableSyncBytes

        // dataDir 里同时包含 cache 与 code_cache，这两项已经单独归类，需要扣掉。
        val fallbackDataBytes = (directorySize(dataRoot) - directorySize(appContext.cacheDir) - codeCacheBytes)
            .coerceAtLeast(0L)
        val knownDataBytes = databaseBytes + attachmentBytes + skillBytes + toolResultBytes + temporaryBytes
        // Android 系统设置页也使用 StorageStats。优先复用同一口径，页面里的“数据”才能与系统数值对上。
        val systemTotals = querySystemStorageTotals()?.takeIf { it.dataBytes >= knownDataBytes }
        val applicationBytes = systemTotals?.applicationBytes ?: fallbackApplicationBytes
        val dataBytes = systemTotals?.dataBytes ?: fallbackDataBytes.coerceAtLeast(knownDataBytes)
        val cacheBytes = systemTotals?.cacheBytes ?: fallbackCacheBytes
        val otherDataBytes = (dataBytes - knownDataBytes).coerceAtLeast(0L)

        AppStorageSnapshot(
            applicationBytes = applicationBytes,
            dataBytes = dataBytes,
            cacheBytes = cacheBytes,
            details = listOf(
                StorageDetail(StorageDetailType.CONVERSATIONS, databaseBytes),
                StorageDetail(StorageDetailType.ATTACHMENTS, attachmentBytes),
                StorageDetail(StorageDetailType.SKILLS, skillBytes),
                StorageDetail(StorageDetailType.TOOL_RESULTS, toolResultBytes),
                StorageDetail(StorageDetailType.TEMPORARY_FILES, temporaryBytes, cleanable = true),
                StorageDetail(StorageDetailType.OTHER_DATA, otherDataBytes),
            ),
        )
    }

    /** 清理安全的垃圾文件、已落地同步分块缓存与数据库孤儿碎片，并返回实际释放的字节数。 */
    suspend fun clearJunk(): Long = withContext(Dispatchers.IO) {
        val dbDir = File(appContext.dataDir, "databases")
        val toolDir = File(appContext.filesDir, "agent_tool_results")
        val syncDir = File(appContext.filesDir, "sync-resources")
        val beforeExtra = directorySize(dbDir) + directorySize(toolDir) + directorySize(syncDir)

        val clearedCache = clearTargets(
            listOfNotNull(
                appContext.cacheDir,
                appContext.codeCacheDir,
                appContext.externalCacheDir,
                File(appContext.filesDir, "chat_images_temp"),
            ),
        )
        reclaimSyncAndToolStorage(appContext)
        val afterExtra = directorySize(dbDir) + directorySize(toolDir) + directorySize(syncDir)
        clearedCache + (beforeExtra - afterExtra).coerceAtLeast(0L)
    }

    /**
     * 清理用户明确选择的数据类型。
     * 会话与 Skills 需要同步业务状态，由各自现有业务层处理，不在这里直接删库。
     */
    suspend fun clearCategory(type: StorageDetailType): Long = withContext(Dispatchers.IO) {
        if (type == StorageDetailType.TEMPORARY_FILES) {
            val syncFiles = reclaimableSyncCacheFiles(appContext)
            val syncBefore = syncFiles.sumOf(::directorySize)
            syncFiles.forEach { file -> runCatching { file.delete() } }
            val syncReleased = (syncBefore - syncFiles.sumOf(::directorySize)).coerceAtLeast(0L)
            return@withContext syncReleased + clearTargets(listOf(File(appContext.filesDir, "chat_images_temp")))
        }
        val targets = when (type) {
            StorageDetailType.ATTACHMENTS -> listOf(
                File(appContext.filesDir, FileManager.CHAT_ATTACHMENTS_DIR),
                File(appContext.filesDir, "chat_images"),
            )
            StorageDetailType.TOOL_RESULTS -> listOf(File(appContext.filesDir, "agent_tool_results"))
            else -> emptyList()
        }
        clearTargets(targets)
    }

    private fun clearTargets(rawTargets: List<File>): Long {
        val targets = rawTargets.distinctFiles()
        val before = targets.sumOf(::directorySize)
        targets.forEach(::deleteDirectoryContents)
        return (before - targets.sumOf(::directorySize)).coerceAtLeast(0L)
    }

    /** 获取与 Android“存储占用”设置页相同口径的应用、数据、缓存三项统计。 */
    private fun querySystemStorageTotals(): SystemStorageTotals? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching {
            val manager = appContext.getSystemService(StorageStatsManager::class.java)
            val stats = manager.queryStatsForUid(StorageManager.UUID_DEFAULT, appContext.applicationInfo.uid)
            SystemStorageTotals(
                applicationBytes = stats.appBytes,
                dataBytes = stats.dataBytes,
                cacheBytes = stats.cacheBytes,
            )
        }.getOrNull()
    }
}

private data class SystemStorageTotals(
    val applicationBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
)

/** 递归统计目录大小。单个文件读取失败时按 0 处理，不能拖垮整个扫描。 */
internal fun directorySize(file: File?): Long {
    if (file == null || !file.exists()) return 0L
    if (file.isFile) return runCatching(file::length).getOrDefault(0L)
    return file.listFiles().orEmpty().sumOf(::directorySize)
}

/** 只清空目录内容，保留目录本身，避免正在运行的组件失去约定目录。 */
internal fun deleteDirectoryContents(directory: File) {
    directory.listFiles().orEmpty().forEach { child ->
        runCatching { child.deleteRecursively() }
    }
}

private fun List<File>.distinctFiles(): List<File> = distinctBy { file ->
    runCatching(file::getCanonicalPath).getOrElse { file.absolutePath }
}

/**
 * 扫描 sync-resources 中已落地且不再被活跃消息附件引用的加密分块与拼接临时文件。
 * 若当前存在待上传确认或 FILE_NOT_READY 待修复项，则跳过对应账号目录，确保断点续传安全。
 */
internal fun reclaimableSyncCacheFiles(context: Context): List<File> {
    val syncRoot = File(context.filesDir, "sync-resources")
    if (!syncRoot.isDirectory) return emptyList()
    return runCatching {
        val db = AppDatabase.getDatabase(context).openHelper.readableDatabase
        val hasPendingQueue = db.query(
            "SELECT 1 FROM system_settings WHERE key LIKE 'sync_resource_queued:%' OR key LIKE 'sync_compaction_queued:%' OR (key LIKE 'sync_rejected:%' AND value = 'FILE_NOT_READY') LIMIT 1",
        ).use { it.moveToFirst() }
        if (hasPendingQueue) return emptyList()

        // 收集所有未删除消息中仍在引用的 sync-resources 本地媒体文件路径或文件名。
        val referencedNames = hashSetOf<String>()
        db.query(
            "SELECT attachments, imageUrls FROM messages WHERE deleted = 0 AND (attachments LIKE '%sync-resources%' OR imageUrls LIKE '%sync-resources%')",
        ).use { cursor ->
            val regex = Regex("""sync-resources/[^"\\]+/([^"\\/]+)""")
            while (cursor.moveToNext()) {
                for (col in 0..1) {
                    val raw = cursor.getString(col) ?: continue
                    regex.findAll(raw).forEach { match -> referencedNames += match.groupValues[1] }
                }
            }
        }

        syncRoot.listFiles().orEmpty()
            .filter(File::isDirectory)
            .flatMap { accountDir ->
                accountDir.listFiles().orEmpty().filter { child ->
                    child.isFile && !child.name.startsWith("skill-") && child.name !in referencedNames
                }
            }
    }.getOrDefault(emptyList())
}

/**
 * 回收同步临时缓存、未截断或孤儿工具归档，并清理软删除会话残留的 Agent 表记录与 SQLite 空闲页。
 */
internal fun reclaimSyncAndToolStorage(context: Context) {
    reclaimableSyncCacheFiles(context).forEach { file -> runCatching { file.delete() } }
    runCatching {
        val writable = AppDatabase.getDatabase(context).openHelper.writableDatabase
        // 清理已软删除会话遗留的本地 Agent 运行记录（级联删除 agent_entries / agent_requests 等）。
        writable.execSQL("DELETE FROM computer_executions WHERE runId IN (SELECT id FROM agent_runs WHERE sessionId NOT IN (SELECT id FROM chat_sessions WHERE deleted = 0))")
        writable.execSQL("DELETE FROM agent_runs WHERE sessionId NOT IN (SELECT id FROM chat_sessions WHERE deleted = 0)")
        writable.execSQL("DELETE FROM agent_compactions WHERE sessionId NOT IN (SELECT id FROM chat_sessions WHERE deleted = 0)")
        writable.execSQL("DELETE FROM provider_continuation_states WHERE sessionId NOT IN (SELECT id FROM chat_sessions WHERE deleted = 0)")

        // 仅保留仍被截断工具结果引用的本地归档文件，清理旧版无条件归档产生的上万个短结果小文件。
        val activeToolFiles = hashSetOf<String>()
        val activeRunHashes = hashSetOf<String>()
        writable.query("SELECT id FROM agent_runs").use { cursor ->
            while (cursor.moveToNext()) {
                activeRunHashes += sha256Hex(cursor.getString(0))
            }
        }
        writable.query(
            "SELECT payloadJson FROM agent_entries WHERE kind = 'TOOL_RESULT' AND payloadJson LIKE '%fullResultPath%' AND payloadJson NOT LIKE '%\"truncated\":false%'",
        ).use { cursor ->
            val pathRegex = Regex(""""fullResultPath"\s*:\s*"([^"]+)"""")
            while (cursor.moveToNext()) {
                val payload = cursor.getString(0) ?: continue
                pathRegex.find(payload)?.groupValues?.getOrNull(1)?.let { activeToolFiles += it }
            }
        }

        val toolRoot = File(context.filesDir, "agent_tool_results")
        if (toolRoot.isDirectory) {
            toolRoot.listFiles().orEmpty().forEach { runDir ->
                if (!runDir.isDirectory || ( runDir.name !in activeRunHashes && runDir.name != sha256Hex("continuation") )) {
                    runCatching { runDir.deleteRecursively() }
                } else {
                    runDir.listFiles().orEmpty().forEach { file ->
                        val relPath = "agent_tool_results/${runDir.name}/${file.name}"
                        if (relPath !in activeToolFiles) {
                            runCatching { file.delete() }
                        }
                    }
                    if (runDir.listFiles().isNullOrEmpty()) {
                        runCatching { runDir.delete() }
                    }
                }
            }
        }

        // 触发 WAL 截断检查点并执行 VACUUM，将 SQLite 空闲页立即归还给手机文件系统。
        writable.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        writable.execSQL("VACUUM")
    }
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
