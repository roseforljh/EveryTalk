package com.android.everytalk.util.storage

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        val temporaryBytes = directorySize(File(appContext.filesDir, "chat_images_temp"))

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

    /** 清理安全的垃圾文件，并返回实际释放的字节数。 */
    suspend fun clearJunk(): Long = withContext(Dispatchers.IO) {
        val targets = listOfNotNull(
            appContext.cacheDir,
            appContext.codeCacheDir,
            appContext.externalCacheDir,
            File(appContext.filesDir, "chat_images_temp"),
        ).distinctFiles()
        val before = targets.sumOf(::directorySize)
        targets.forEach(::deleteDirectoryContents)
        (before - targets.sumOf(::directorySize)).coerceAtLeast(0L)
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
