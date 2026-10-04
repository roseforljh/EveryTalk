package com.android.everytalk.data.network

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * pi 与 models.dev 共用的目录缓存。并发调用只刷新一次；刷新失败继续使用旧目录，
 * 解析失败的响应不会覆盖可用文件。缓存来源及时间由各目录解析器保留。
 */
internal class RemoteModelCatalogCache<T : Any>(
    private val cacheFile: File,
    private val ttlMillis: Long,
    private val nowEpochMillis: () -> Long,
    private val parse: (String, Long) -> T?,
) {
    private data class MemoryEntry<T>(val index: T?, val checkedAt: Long)
    private val mutex = Mutex()
    private var memoryEntry: MemoryEntry<T>? = null

    suspend fun load(
        fetchRemote: suspend () -> String,
        allowCache: Boolean = true,
    ): T? = mutex.withLock {
        val now = nowEpochMillis()
        // 强制获取用于用户主动刷新模型参数。此路径只解析本次网络响应，
        // 不读旧文件、不读进程内缓存，也不把本次结果写回缓存，避免旧目录影响恢复结果。
        if (!allowCache) {
            val body = try {
                fetchRemote()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@withLock null
            }
            return@withLock parse(body, now)
        }
        memoryEntry?.takeIf { it.checkedAt >= now - ttlMillis }?.let { return@withLock it.index }
        val diskText = runCatching { cacheFile.takeIf(File::isFile)?.readText(Charsets.UTF_8) }.getOrNull()
        val diskTimestamp = cacheFile.lastModified().coerceAtLeast(0L)
        val diskIndex = diskText?.let { parse(it, diskTimestamp) }
        if (diskIndex != null && diskTimestamp >= now - ttlMillis) {
            memoryEntry = MemoryEntry(diskIndex, diskTimestamp)
            return@withLock diskIndex
        }
        val refreshed = try {
            val body = fetchRemote()
            parse(body, now)?.also { writeAtomically(body) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val result = refreshed ?: memoryEntry?.index ?: diskIndex
        // 短暂失败后一分钟再试，避免连续获取模型时反复请求不可用目录。
        val checkedAt = if (refreshed != null) now else now - ttlMillis + minOf(ttlMillis, 60_000L)
        memoryEntry = MemoryEntry(result, checkedAt)
        result
    }

    private fun writeAtomically(body: String) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temporary = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            temporary.writeText(body, Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temporary.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
