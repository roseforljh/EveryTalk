package com.android.everytalk.data.memory

import android.content.Context
import androidx.room.withTransaction
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.MemoryEntity
import com.android.everytalk.data.database.entities.SystemSettingEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

data class MemorySettings(
    val enabled: Boolean = false,
    /** 用户改变授权或删除资料时递增，晚到的模型结果不得写回。 */
    val version: Long = 0,
) {
    /** 自动保存始终跟随全局开关；兼容旧同步数据时不再读取独立开关。 */
    val autoCaptureEnabled: Boolean get() = enabled
}

data class MemoryState(val settings: MemorySettings, val entries: List<MemoryEntity>) {
    val characters: Int get() = entries.sumOf { it.content.memoryCharacters() }
    val document: String get() = entries.joinToString("\n\n") { it.content }
}

/** 来源由请求运行时绑定，模型不能伪造会话和用户消息 ID。 */
data class MemoryWriteContext(
    val version: Long,
    val conversationId: String,
    val userMessageId: String,
    val origin: MemoryOrigin,
)

/**
 * 全会话共用的读写边界：重复内容和授权版本在同一 Room 事务内校验。
 * 管理页的手动编辑不依赖模型使用开关；模型写入必须带当前授权。
 */
class MemoryRepository(private val database: AppDatabase) {
    constructor(context: Context) : this(AppDatabase.getDatabase(context.applicationContext))
    private val dao = database.memoryDao()
    private val settingsDao = database.settingsDao()

    fun observeState(): Flow<MemoryState> = combine(
        dao.observeAll(), settingsDao.observeMemorySettings(),
    ) { entries, values -> MemoryState(decodeSettings(values.associate { it.key to it.value }), entries) }.distinctUntilChanged()

    suspend fun state(): MemoryState = database.withTransaction { MemoryState(settings(), dao.getAll()) }

    suspend fun settings(): MemorySettings = decodeSettings(SETTING_KEYS.associateWith { settingsDao.getValue(it) })

    suspend fun sourceTitle(conversationId: String): String? = database.chatDao().getSession(conversationId)?.let { it.title ?: it.id }

    /** 开关和版本原子提交，关闭后后台任务不能通过旧授权继续写入。 */
    suspend fun setSettings(enabled: Boolean) = database.withTransaction {
        val old = settings()
        if (old.enabled != enabled) {
            put(ENABLED_KEY, enabled.toString())
            // 保留旧同步字段供旧版本客户端读取，但它不再是独立设置。
            put(AUTO_CAPTURE_KEY, enabled.toString())
            advanceVersion(old)
            com.android.everytalk.data.sync.SyncSettingsProjector(database).refresh()
        }
    }

    suspend fun save(
        content: String,
        category: MemoryCategory = MemoryCategory.OTHER,
        id: String? = null,
        context: MemoryWriteContext? = null,
        enabled: Boolean? = null,
    ): MemoryEntity = database.withTransaction {
        val currentSettings = settings()
        checkPermission(context, currentSettings)
        require(context?.origin != MemoryOrigin.AUTO || id == null) { "自动提取不能覆盖已有记忆" }
        val normalized = MemoryPolicy.validate(content)
        val entries = dao.getAll()
        val old = id?.let { requested -> entries.find { it.id == requested } ?: error("这条记忆已被删除") }
        val duplicate = entries.find { it.id != id && it.content == normalized }
        if (duplicate != null) {
            require(id == null) { "已有相同内容的记忆" }
            return@withTransaction duplicate
        }
        val now = maxOf(System.currentTimeMillis(), (old?.clientModifiedAt ?: 0L) + 1)
        val savedId = old?.id ?: UUID.randomUUID().toString()
        val saved = MemoryEntity(
            id = savedId, category = category.name, content = normalized,
            origin = context?.origin?.name ?: MemoryOrigin.MANUAL.name,
            sourceConversationId = context?.conversationId ?: old?.sourceConversationId,
            sourceMessageId = context?.userMessageId ?: old?.sourceMessageId,
            createdAt = old?.createdAt ?: now, updatedAt = now, enabled = enabled ?: old?.enabled ?: true,
            uuid = old?.uuid ?: com.android.everytalk.data.database.entities.localSyncUuid("memories", savedId),
        )
        if (old == null) dao.insert(saved) else check(dao.update(saved) == 1)
        if (context == null) advanceVersion(currentSettings)
        saved
    }

    suspend fun setEntryEnabled(id: String, enabled: Boolean) = database.withTransaction {
        val old = dao.get(id) ?: error("这条记忆已被删除")
        val now = maxOf(System.currentTimeMillis(), old.clientModifiedAt + 1)
        dao.update(old.copy(enabled = enabled, updatedAt = now, syncUpdatedAt = now,
            syncStatus = com.android.everytalk.data.database.entities.SyncStatus.PENDING))
        advanceVersion(settings())
    }

    suspend fun delete(id: String, context: MemoryWriteContext? = null) = database.withTransaction {
        require(context?.origin != MemoryOrigin.AUTO) { "自动提取不能删除已有记忆" }
        val currentSettings = settings()
        checkPermission(context, currentSettings)
        check(dao.delete(id) == 1) { "这条记忆已被删除" }
        advanceVersion(currentSettings)
    }

    suspend fun clear() = database.withTransaction { dao.clear(); advanceVersion(settings()) }

    /**
     * 把编辑区全文原子保存；空白表示删除用户看到的全部记忆。
     * baseline 是开始编辑时的条目快照：后来 AI 新增的条目会保留，已被其他任务修改的旧条目
     * 则拒绝覆盖。先验证再删除，任何失败都会回滚，旧提取任务也不能在保存后恢复已删内容。
     */
    suspend fun saveDocument(content: String, baseline: List<MemoryEntity>): MemoryState = database.withTransaction {
        val normalized = MemoryPolicy.normalize(content)
        if (normalized.isNotEmpty()) MemoryPolicy.validate(normalized)
        val current = dao.getAll()
        val currentById = current.associateBy { it.id }
        check(baseline.all { previous ->
            currentById[previous.id]?.let { it.content == previous.content && it.enabled == previous.enabled } == true
        }) { "记忆已被更新，本次未覆盖。请保留输入内容，重新进入页面核对后保存。" }
        val settings = settings()
        if (normalized == baseline.joinToString("\n\n") { it.content }) {
            advanceVersion(settings)
            return@withTransaction state()
        }
        baseline.forEach { dao.delete(it.id) }
        if (normalized.isNotEmpty()) save(normalized)
        advanceVersion(settings)
        state()
    }

    /** 小集合字面搜索；百分号、下划线按普通字符处理，查询不拼接 SQL。 */
    suspend fun search(query: String, forModel: Boolean = false): List<MemoryEntity> = database.withTransaction {
        if (forModel) check(settings().enabled) { "记忆功能已关闭" }
        dao.getAll().filter { (!forModel || it.enabled) && it.content.contains(query.trim(), ignoreCase = true) }
    }

    /** 持久化认领，一次成功回合最多提取一次，失败不循环重试。 */
    suspend fun claimCapture(context: MemoryWriteContext): Boolean = database.withTransaction {
        checkPermission(context, settings())
        val key = "memory_capture_${context.userMessageId}"
        if (settingsDao.getValue(key) != null) return@withTransaction false
        put(key, context.version.toString())
        true
    }

    private fun checkPermission(context: MemoryWriteContext?, settings: MemorySettings) {
        if (context == null) return
        require(context.origin != MemoryOrigin.MANUAL) { "模型不能声明手动管理来源" }
        check(settings.enabled) { "记忆功能已关闭" }
        check(context.version == settings.version) { "记忆已变更，本次旧任务不能继续写入" }
        if (context.origin == MemoryOrigin.AUTO) check(settings.autoCaptureEnabled) { "自动保存已关闭" }
    }

    private suspend fun advanceVersion(old: MemorySettings) = put(VERSION_KEY, (old.version + 1).toString())
    private suspend fun put(key: String, value: String) = settingsDao.insertSetting(SystemSettingEntity(key, value))

    companion object {
        const val ENABLED_KEY = "memory_enabled"
        const val AUTO_CAPTURE_KEY = "memory_auto_capture_enabled"
        const val VERSION_KEY = "memory_task_version"
        val SETTING_KEYS = listOf(ENABLED_KEY, AUTO_CAPTURE_KEY, VERSION_KEY)
        private fun decodeSettings(values: Map<String, String?>): MemorySettings = MemorySettings(
            enabled = values[ENABLED_KEY] == "true",
            version = values[VERSION_KEY]?.toLongOrNull() ?: 0,
        )
    }
}
