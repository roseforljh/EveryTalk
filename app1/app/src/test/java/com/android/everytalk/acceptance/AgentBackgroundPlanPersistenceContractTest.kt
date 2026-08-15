package com.android.everytalk.acceptance

import com.android.everytalk.data.database.entities.ComputerExecutionEntity
import kotlin.reflect.full.primaryConstructor
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定进程死亡后恢复、取消和幂等续写需要永久保存的事实。 */
class AgentBackgroundPlanPersistenceContractTest {
    private val entityFields = ComputerExecutionEntity::class.primaryConstructor
        ?.parameters
        ?.mapNotNull { it.name }
        ?.toSet()
        .orEmpty()
    private val databaseSource = AgentBackgroundPlanTestFiles.source("data/database/AppDatabase.kt")
    private val daoSource = AgentBackgroundPlanTestFiles.source("data/database/daos/ComputerDao.kt")

    @Test
    fun `Execution必须直接保存AgentRun关联`() {
        assertTrue(
            "ComputerExecutionEntity 缺少 runId/agentRunId，无法精确停止和续接原 Run",
            "runId" in entityFields || "agentRunId" in entityFields,
        )
    }

    @Test
    fun `Execution必须保存增量日志游标和最近事件时间`() {
        assertTrue("缺少 stdoutCursor/stdoutOffset", "stdoutCursor" in entityFields || "stdoutOffset" in entityFields)
        assertTrue("缺少 stderrCursor/stderrOffset", "stderrCursor" in entityFields || "stderrOffset" in entityFields)
        assertTrue("缺少最近事件时间", "lastEventAt" in entityFields || "lastObservedAt" in entityFields)
    }

    @Test
    fun `Execution必须保存取消请求与最终取消结果`() {
        assertTrue("缺少取消请求时间", "cancelRequestedAt" in entityFields || "cancellationRequestedAt" in entityFields)
        assertTrue(
            "缺少 cancelResult 或 cancelCompletedAt",
            "cancelResult" in entityFields || "cancelCompletedAt" in entityFields,
        )
    }

    @Test
    fun `Execution必须保存结果是否已经接回AgentRun`() {
        assertTrue(
            "缺少 resultAttachedAt 或 resultAttachedToRun，重复恢复可能让 AI 回复两次",
            "resultAttachedAt" in entityFields || "resultAttachedToRun" in entityFields,
        )
    }

    @Test
    fun `数据库必须新增十九到二十迁移且注册`() {
        val versionDeclaration = databaseSource.substringAfter("version =", "")
            .substringBefore(",", "")
            .trim()
            .toIntOrNull()
        assertTrue("AppDatabase 版本必须升级到至少 20", versionDeclaration != null && versionDeclaration >= 20)
        assertTrue("缺少 MIGRATION_19_20", databaseSource.contains("MIGRATION_19_20"))
        val registration = databaseSource.substringAfter("addMigrations(", "").substringBefore(")", "")
        assertTrue("MIGRATION_19_20 没有注册到数据库构建器", registration.contains("MIGRATION_19_20"))
    }

    @Test
    fun `新迁移必须保留旧Execution并为新增字段提供安全默认值`() {
        val migrationTest = AgentBackgroundPlanTestFiles.optionalAppFile(
            "app/src/test/java/com/android/everytalk/data/database/AppDatabaseMigrationTest.kt",
        )?.readText(Charsets.UTF_8).orEmpty()
        assertTrue("Migration 测试必须真实执行 MIGRATION_19_20", migrationTest.contains("MIGRATION_19_20.migrate"))
        assertTrue("Migration 测试必须验证 Run 关联", migrationTest.contains("agentRunId") || migrationTest.contains("runId"))
        assertTrue(
            "Migration 测试必须验证日志游标",
            (migrationTest.contains("stdoutCursor") || migrationTest.contains("stdoutOffset")) &&
                (migrationTest.contains("stderrCursor") || migrationTest.contains("stderrOffset")),
        )
        assertTrue("Migration 测试必须验证结果接回标记", migrationTest.contains("resultAttached"))
    }

    @Test
    fun `DAO必须支持按Run读取全部活动Execution`() {
        assertTrue("DAO 缺少按 AgentRun 查询活动 Execution 的公开方法", daoSource.contains("ForAgentRun") || daoSource.contains("ByAgentRun"))
        assertTrue(
            "按 Run 查询必须使用持久 Run 关联",
            daoSource.contains("agentRunId = :agentRunId") || daoSource.contains("runId = :runId"),
        )
    }

    @Test
    fun `DAO必须提供原子结果接回声明防止重复续写`() {
        assertTrue(
            "需要 UPDATE ... WHERE resultAttachedAt IS NULL 形式的单次声明",
            daoSource.contains("resultAttachedAt IS NULL") || daoSource.contains("resultAttachedToRun = 0"),
        )
        assertNotNull(ComputerExecutionEntity::class.primaryConstructor)
    }
}
