package com.android.everytalk.data.skill

import android.content.Context
import com.android.everytalk.data.computer.ComputerCredentialStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SkillSecretMetadata(val skillId: String, val name: String, val updatedAt: Long)

/** 密钥正文只进入 Android Keystore 加密文件，偏好设置只保存变量名和更新时间。 */
class SkillSecretStore(context: Context) {
    private val appContext = context.applicationContext
    private val credentials = ComputerCredentialStore(appContext)
    private val preferences = appContext.getSharedPreferences("skill_secret_metadata", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(skillId: String): List<SkillSecretMetadata> = withContext(Dispatchers.IO) {
        readMetadata().filter { it.skillId == skillId }.sortedBy(SkillSecretMetadata::name)
    }

    suspend fun save(skillId: String, name: String, value: CharArray) = withContext(Dispatchers.IO) {
        requireSecretName(name)
        require(value.isNotEmpty()) { "密钥不能为空" }
        val copy = value.copyOf()
        try {
            credentials.saveWorkspaceSecret(secretId(skillId, name), copy)
            val next = readMetadata().filterNot { it.skillId == skillId && it.name == name } +
                SkillSecretMetadata(skillId, name, System.currentTimeMillis())
            writeMetadata(next)
        } finally {
            copy.fill('\u0000')
        }
    }

    suspend fun load(skillId: String, name: String): CharArray? = withContext(Dispatchers.IO) {
        if (readMetadata().none { it.skillId == skillId && it.name == name }) return@withContext null
        runCatching { credentials.loadWorkspaceSecret(secretId(skillId, name)) }.getOrNull()
    }

    suspend fun delete(skillId: String, name: String) = withContext(Dispatchers.IO) {
        credentials.deleteWorkspaceSecret(secretId(skillId, name))
        writeMetadata(readMetadata().filterNot { it.skillId == skillId && it.name == name })
    }

    private fun readMetadata(): List<SkillSecretMetadata> = preferences.getString(METADATA_KEY, null)
        ?.let { runCatching { json.decodeFromString(ListSerializer(SkillSecretMetadata.serializer()), it) }.getOrNull() }
        .orEmpty()

    private fun writeMetadata(items: List<SkillSecretMetadata>) {
        check(preferences.edit().putString(METADATA_KEY, json.encodeToString(ListSerializer(SkillSecretMetadata.serializer()), items)).commit()) {
            "Skill 密钥元数据保存失败"
        }
    }

    private fun secretId(skillId: String, name: String): String = "skill_" + MessageDigest.getInstance("SHA-256")
        .digest("$skillId\u0000$name".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object { const val METADATA_KEY = "items" }
}

/** 一次性密钥只存在当前进程内，Run 结束或进程退出后自然销毁。 */
object SkillSecretSessionStore {
    private val byRun = ConcurrentHashMap<String, ConcurrentHashMap<String, CharArray>>()

    fun put(runId: String, name: String, value: CharArray) {
        requireSecretName(name)
        val values = byRun.computeIfAbsent(runId) { ConcurrentHashMap() }
        values.put(name, value.copyOf())?.fill('\u0000')
    }

    fun loadSelected(runId: String?, names: Collection<String>): Map<String, CharArray> {
        if (runId == null || names.isEmpty()) return emptyMap()
        val values = byRun[runId] ?: return emptyMap()
        return names.mapNotNull { name -> values[name]?.let { name to it.copyOf() } }.toMap()
    }

    fun contains(runId: String, name: String): Boolean = byRun[runId]?.containsKey(name) == true

    fun clear(runId: String) {
        byRun.remove(runId)?.values?.forEach { it.fill('\u0000') }
    }
}

private fun requireSecretName(name: String) {
    require(name.isNotBlank() && name.length <= 128 && name.first().let { it == '_' || it.isLetter() }) { "密钥变量名无效" }
    require(name.all { it == '_' || it.isLetterOrDigit() }) { "密钥变量名无效" }
}
