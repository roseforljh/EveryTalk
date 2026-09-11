package com.android.everytalk.data.computer

import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.CloudflareResourceEntity
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** D1 migration 的本地幂等协调器；不会在结果未知时重复提交写 SQL。 */
class D1MigrationManager(
    private val dao: ComputerDao,
    private val resources: CloudflareResourceClient,
) : AutoCloseable {
    suspend fun execute(
        computerId: String,
        accountId: String,
        databaseId: String,
        sql: String,
    ): D1MigrationResult {
        require(computerId.isNotBlank() && listOf(accountId, databaseId).all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }) { "D1 migration 目标无效" }
        require(sql.isNotBlank() && sql.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "D1 migration 超过限制" }
        val hash = sha256("$accountId\n$databaseId\n$sql")
        val ref = "d1-migration:$accountId:$databaseId:$hash"
        // 先持久化 UNKNOWN 再发送。即使系统在网络请求中杀掉 App，下次也不会盲目重放 SQL。
        // 主键由 Account、数据库和内容确定，同账号多个 Computer 也共享这一防重边界。
        val claimed = dao.insertCloudflareResourceIfAbsent(CloudflareResourceEntity(
            ref, computerId, accountId, "D1_MIGRATION_UNKNOWN", hash, "D1 migration $hash", System.currentTimeMillis(),
        )) != -1L
        if (!claimed) {
            val existing = dao.getCloudflareResource(ref)
            // 完成态由 completeD1Migration 写成 D1_MIGRATION；只有完成态
            // 才能回答“已应用”。UNKNOWN 占位必须继续阻止重复执行。
            if (existing?.kind == "D1_MIGRATION" && existing.accountId == accountId && existing.resourceId == hash) {
                return D1MigrationResult(hash, alreadyApplied = true)
            }
            if (existing?.kind == "D1_MIGRATION_FAILED") {
                throw CloudflareApiException("MIGRATION_FAILED", "该 migration 已明确失败，请检查 SQL 和授权；未自动重发")
            }
            throw CloudflareApiException("RESULT_UNKNOWN", "相同 migration 已提交或结果待核实，禁止自动重复执行")
        }
        try {
            resources.runD1Migration(accountId, databaseId, sql)
            check(dao.completeD1Migration(ref, "D1_MIGRATION") == 1) { "D1 migration 账本状态已变化" }
        } catch (cancelled: CancellationException) {
            // 取消不等于云端回滚；原样传播取消并留下 UNKNOWN 供恢复检查。
            throw cancelled
        } catch (error: CloudflareApiException) {
            // 只把请求接纳之前的明确拒绝写成 FAILED。批量 SQL 的单条失败可能伴随
            // 前面的语句已经执行，D1_QUERY_FAILED 和外层业务错误仍须保留 UNKNOWN。
            if (error.code in DEFINITIVE_FAILURE_CODES) {
                dao.completeD1Migration(ref, "D1_MIGRATION_FAILED")
                throw error
            }
            throw CloudflareApiException("RESULT_UNKNOWN", "D1 migration 结果未完整确认，请核实数据库状态")
        } catch (_: Exception) {
            // 多语句请求可能部分执行，成功响应之后本地写账也可能失败，都不能允许直接重发。
            throw CloudflareApiException("RESULT_UNKNOWN", "D1 migration 结果未完整确认，请核实数据库状态")
        }
        return D1MigrationResult(hash, alreadyApplied = false)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    override fun close() = resources.close()

    private companion object {
        val DEFINITIVE_FAILURE_CODES = setOf(
            "AUTHORIZATION_REQUIRED", "PERMISSION_DENIED", "RESOURCE_NOT_FOUND",
            "HTTP_401", "HTTP_403", "HTTP_404", "RATE_LIMITED", "REDIRECT_REJECTED",
        )
    }
}

data class D1MigrationResult(val hash: String, val alreadyApplied: Boolean)
