package com.android.everytalk.acceptance

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.android.everytalk.service.ComputerConnectionService
import com.android.everytalk.util.AgentNotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.util.UUID

/** 验证通知权限硬门槛和通知只承担状态展示、会话跳转。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentBackgroundPlanNotificationContractTest {
    private val serviceSource = AgentBackgroundPlanTestFiles.source("service/ComputerConnectionService.kt")
    private val sendFlow = AgentBackgroundPlanTestFiles.source("statecontroller/message/MessageSenderSendFlow.kt")
    private val toggleFlow = AgentBackgroundPlanTestFiles.source("statecontroller/viewmodel/AppViewModelActions.kt")
    private val allSources = AgentBackgroundPlanTestFiles.allProductionKotlin()

    @Before
    fun clearKoinBeforeTest() {
        stopKoin()
    }

    @After
    fun clearKoinAfterTest() {
        stopKoin()
    }

    @Test
    fun `任务成功后必须撤销同一Execution的旧断线通知`() {
        val context = notificationContext()
        val executionId = UUID.randomUUID().toString()

        notify(context, executionId, "CONNECTION_LOST", "SSH 连接断开")
        notify(context, executionId, "SUCCEEDED", "任务完成")

        val notifications = notifications(context)
        assertEquals("终态到达后只能保留终态通知", 1, notifications.size)
        assertEquals("任务完成", notifications.single().extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun `任务已经成功时必须丢弃迟到的断线事件`() {
        val context = notificationContext()
        val executionId = UUID.randomUUID().toString()

        notify(context, executionId, "SUCCEEDED", "任务完成")
        notify(context, executionId, "CONNECTION_LOST", "SSH 连接断开")

        val notifications = notifications(context)
        assertEquals("成功后的迟到断线事件不能生成第二条通知", 1, notifications.size)
        assertEquals("任务完成", notifications.single().extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun `从未通知断线时禁止单独发送重新连接通知`() {
        val context = notificationContext()

        notify(context, UUID.randomUUID().toString(), "RECONNECTED", "SSH 连接已恢复")

        assertTrue("没有 CONNECTION_LOST 状态时，RECONNECTED 属于假通知", notifications(context).isEmpty())
    }

    @Test
    fun `自动重试期间同一Execution只发送一次断线通知`() {
        val context = notificationContext()
        val executionId = UUID.randomUUID().toString()

        notify(context, executionId, "CONNECTION_LOST", "SSH 连接断开")
        notify(context, executionId, "CONNECTION_LOST", "SSH 连接断开")

        assertEquals("同一任务的重复重连不能刷出多条通知", 1, notifications(context).size)
    }

    @Test
    fun `服务重建会清除旧断线通知`() {
        val context = notificationContext()
        notify(context, UUID.randomUUID().toString(), "CONNECTION_LOST", "SSH 连接断开")

        AgentNotificationManager.clearConnectionFailureNotifications(context)

        assertTrue("旧断线通知必须被清掉", notifications(context).isEmpty())
    }

    @Test
    fun `通知权限检查必须集中处理运行时权限全局开关和渠道`() {
        val gateFiles = allSources.filter { (_, text) ->
            text.contains("areNotificationsEnabled") && text.contains("getNotificationChannel")
        }
        assertTrue("缺少统一通知可用性检查：运行时权限、全局开关、渠道三者必须同时判断", gateFiles.isNotEmpty())
        val gate = gateFiles.joinToString("\n") { it.second }
        assertTrue(gate.contains("POST_NOTIFICATIONS"))
        assertTrue(gate.contains("IMPORTANCE_NONE"))
    }

    @Test
    fun `开启发送和模型恢复三个入口都必须调用统一权限门槛`() {
        val gateName = listOf("canUseAgentNotifications", "isAgentNotificationAvailable", "requireAgentNotifications")
            .firstOrNull { candidate -> allSources.any { it.second.contains(candidate) } }
        assertNotNull("请建立一个统一通知门槛函数，禁止三个入口各写一套判断", gateName)
        val marker = requireNotNull(gateName)
        assertTrue("开启 Agent 前没有检查通知门槛", toggleFlow.contains(marker))
        assertTrue("发送 Agent 消息前没有检查通知门槛", sendFlow.contains(marker))
        val recoverySources = allSources
            .filter { (_, text) -> text.contains("MODEL_CONTINUATION_PENDING") || text.contains("resumeActiveTasks") }
            .joinToString("\n") { it.second }
        assertTrue("模型恢复前没有检查通知门槛", recoverySources.contains(marker))
    }

    @Test
    fun `前台服务通知没有任何操作按钮`() {
        assertTrue("服务通知不能 addAction", !serviceSource.contains(".addAction("))
        val controller = Robolectric.buildService(ComputerConnectionService::class.java).create()
        val service = controller.get()
        val manager = service.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).allNotifications.single()
        assertTrue(notification.actions.isNullOrEmpty())
    }

    @Test
    fun `前台服务通知必须能点击进入对应会话`() {
        assertTrue("通知缺少 setContentIntent", serviceSource.contains("setContentIntent"))
        assertTrue("通知跳转必须携带 conversationId", serviceSource.contains("conversationId"))
    }

    @Test
    fun `通知必须显示活动任务数量但不能显示命令和输出`() {
        assertTrue("通知没有活动任务数量", serviceSource.contains("activeTaskCount") || serviceSource.contains("taskCount"))
        assertTrue("通知必须保持 onlyAlertOnce", serviceSource.contains("setOnlyAlertOnce(true)"))
        assertTrue("通知不得拼接 command", !serviceSource.contains("execution.command"))
        assertTrue("通知不得拼接 stdout", !serviceSource.contains("stdout"))
        assertTrue("通知不得拼接 stderr", !serviceSource.contains("stderr"))
    }

    @Test
    fun `任务事件通知必须覆盖完成失败审批断线和恢复并做去重`() {
        val notificationSources = allSources
            .filter { (file, text) -> file.name.contains("Notification") || text.contains("NotificationCompat.Builder") }
            .joinToString("\n") { it.second }
        listOf("SUCCEEDED", "FAILED", "WAITING_APPROVAL", "CONNECTION_LOST", "RECONNECTED").forEach { event ->
            assertTrue("通知系统缺少 $event 事件", notificationSources.contains(event))
        }
        assertTrue(
            "相同事件必须按 executionId 与事件类型去重",
            notificationSources.contains("executionId") &&
                (notificationSources.contains("lastNotified") || notificationSources.contains("dedup")),
        )
    }

    @Test
    fun `通知权限撤销后保留远端任务但暂停模型续写`() {
        val recoverySources = allSources
            .filter { (_, text) -> text.contains("MODEL_CONTINUATION_PENDING") }
            .joinToString("\n") { it.second }
        assertTrue("权限不可用时必须保留 MODEL_CONTINUATION_PENDING", recoverySources.contains("notification") || recoverySources.contains("Notification"))
        assertTrue("已有 VPS 任务不能因通知权限撤销而调用 cancel", !recoverySources.contains("cancelRemoteExecution"))
    }

    @Test
    fun `通知只能跳转不能处理停止取消通过和拒绝`() {
        val notificationBuilder = serviceSource.substringAfter("private fun buildNotification", "")
        assertTrue("通知 Builder 禁止 addAction", !notificationBuilder.contains(".addAction("))
        assertTrue("通知点击只能使用 setContentIntent", notificationBuilder.contains("setContentIntent"))
    }

    private fun notificationContext(): Context {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        application.getSystemService(NotificationManager::class.java).cancelAll()
        AgentNotificationManager.ensureEventChannel(application)
        return application
    }

    private fun notifications(context: Context): List<Notification> = shadowOf(
        context.getSystemService(NotificationManager::class.java),
    ).allNotifications.toList()

    private fun notify(context: Context, executionId: String, eventType: String, title: String) {
        AgentNotificationManager.notifyTaskEvent(
            context = context,
            conversationId = "conversation-1",
            executionId = executionId,
            eventType = eventType,
            title = title,
            message = title,
        )
    }
}
