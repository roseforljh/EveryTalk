package com.android.everytalk.acceptance

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.android.everytalk.service.ComputerConnectionService
import com.android.everytalk.service.agentNotificationElapsedText
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.util.AgentNotificationManager
import com.android.everytalk.util.locale.localizeUiMessage
import org.junit.Assert.assertEquals
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

/** 验证真实通知结果，不通过源码关键词推断任务行为。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AgentBackgroundPlanNotificationContractTest {
    @Before
    fun clearKoinBeforeTest() {
        stopKoin()
        AgentNotificationManager.onAppBackground()
    }

    @After
    fun clearKoinAfterTest() {
        AgentNotificationManager.onAppBackground()
        stopKoin()
    }

    @Test
    fun `前台不展示Agent事件且返回前台清除已有事件通知`() {
        val context = notificationContext()
        notify(context, UUID.randomUUID().toString(), "WAITING_APPROVAL", "Agent 需要你的确认")
        assertEquals(1, notifications(context).size)
        AgentNotificationManager.onAppForeground(context)
        assertTrue(notifications(context).isEmpty())
        notify(context, UUID.randomUUID().toString(), "WAITING_APPROVAL", "Agent 需要你的确认")
        assertTrue(notifications(context).isEmpty())
    }

    @Test
    fun `单个AgentRun只发一次终态通知且不同Run分别通知`() {
        val context = notificationContext()
        val firstRunId = UUID.randomUUID().toString()
        val secondRunId = UUID.randomUUID().toString()
        for (runId in listOf(firstRunId, firstRunId, secondRunId)) {
            AgentNotificationManager.notifyAgentRunTerminal(
                context = context, conversationId = "conversation-1", runId = runId,
                status = AgentRunStatus.COMPLETED,
            )
        }
        assertEquals(2, notifications(context).size)
    }

    @Test
    fun `任务成功后必须撤销同一Execution的旧等待确认通知`() {
        val context = notificationContext()
        val executionId = UUID.randomUUID().toString()
        notify(context, executionId, "WAITING_APPROVAL", "Agent 需要你的确认")
        notify(context, executionId, "SUCCEEDED", "任务完成")
        val notifications = notifications(context)
        assertEquals(1, notifications.size)
        assertEquals(context.localizeUiMessage("任务完成"), notifications.single().extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun `任务已经成功时必须丢弃迟到的过程事件`() {
        val context = notificationContext()
        val executionId = UUID.randomUUID().toString()
        notify(context, executionId, "SUCCEEDED", "任务完成")
        notify(context, executionId, "WAITING_APPROVAL", "Agent 需要你的确认")
        val notifications = notifications(context)
        assertEquals(1, notifications.size)
        assertEquals(context.localizeUiMessage("任务完成"), notifications.single().extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun `服务重建会清除旧断线通知`() {
        val context = notificationContext()
        notify(context, UUID.randomUUID().toString(), "CONNECTION_LOST", "SSH 连接断开")
        AgentNotificationManager.clearConnectionFailureNotifications(context)
        assertTrue(notifications(context).isEmpty())
    }

    @Test
    fun `前台通知没有操作按钮且点击只打开应用`() {
        // 仅附加 Service 的 Context 并调用真实通知构造函数，不启动与通知断言无关的数据库监听和恢复任务。
        val service = Robolectric.buildService(ComputerConnectionService::class.java).get()
        val state = ComputerConnectionService::class.java.getDeclaredField("notificationState")
            .apply { isAccessible = true }.get(service)
        val builder = ComputerConnectionService::class.java.declaredMethods.single { it.name == "buildNotification" }
            .apply { isAccessible = true }
        val notification = builder.invoke(service, state) as Notification
        assertTrue(notification.actions.isNullOrEmpty())
        assertEquals("com.android.everytalk.statecontroller.MainActivity",
            shadowOf(requireNotNull(notification.contentIntent)).savedIntent.component?.className)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `前台通知按已运行秒数显示时长而非钟表时间`() {
        assertEquals("1s", agentNotificationElapsedText(1_000L, 2_000L))
        assertEquals("2s", agentNotificationElapsedText(1_000L, 3_000L))
        assertEquals("0s", agentNotificationElapsedText(3_000L, 2_000L))
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
            context = context, conversationId = "conversation-1", executionId = executionId,
            eventType = eventType, title = title, message = title,
        )
    }
}
