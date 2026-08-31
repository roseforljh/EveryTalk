package com.android.everytalk.data.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** 当前 AgentRun 的安全暂停控制状态。 */
enum class AgentRunControlState {
    RUNNING,
    PAUSE_REQUESTED,
    PAUSED,
}

/** UI 使用可见消息定位 Run，AgentLoop 绑定后补齐真实 runId。 */
data class AgentRunControlSnapshot(
    val runId: String?,
    val visibleAssistantMessageId: String,
    val state: AgentRunControlState,
)

/**
 * 应用级 Agent 安全暂停门。
 *
 * Pause 只登记请求。AgentLoop 到达安全边界后调用 [awaitIfPaused]，该方法把状态推进到
 * PAUSED 并挂起当前协程，直到同一个 Run 收到 Resume。整个过程不会取消模型或 Tool。
 */
class AgentRunPauseController {
    private data class Slot(
        val visibleAssistantMessageId: String,
        var runId: String? = null,
        val state: MutableStateFlow<AgentRunControlState> = MutableStateFlow(AgentRunControlState.RUNNING),
    )

    private val lock = Any()
    private val slotsByMessage = linkedMapOf<String, Slot>()
    private val slotsByRun = mutableMapOf<String, Slot>()
    private val _snapshots = MutableStateFlow<Map<String, AgentRunControlSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, AgentRunControlSnapshot>> = _snapshots.asStateFlow()

    /** Coordinator 启动应用级 Job 时先登记，覆盖 Run 尚未落库的短窗口。 */
    fun register(visibleAssistantMessageId: String) {
        synchronized(lock) {
            slotsByMessage.getOrPut(visibleAssistantMessageId) { Slot(visibleAssistantMessageId) }
            publishLocked()
        }
    }

    /** AgentLoop 创建或恢复 Run 后绑定稳定 runId。 */
    fun bind(runId: String, visibleAssistantMessageId: String) {
        synchronized(lock) {
            val slot = slotsByMessage.getOrPut(visibleAssistantMessageId) { Slot(visibleAssistantMessageId) }
            slot.runId?.let(slotsByRun::remove)
            slot.runId = runId
            slotsByRun[runId] = slot
            publishLocked()
        }
    }

    /** 只把当前 Run 标记为请求暂停，当前 LLM 或 Tool 继续正常收尾。 */
    fun requestPause(visibleAssistantMessageId: String): Boolean = synchronized(lock) {
        val slot = slotsByMessage[visibleAssistantMessageId] ?: return@synchronized false
        if (slot.state.value != AgentRunControlState.RUNNING) return@synchronized false
        slot.state.value = AgentRunControlState.PAUSE_REQUESTED
        publishLocked()
        true
    }

    /** 解除同一个 Run 的 gate，原 AgentLoop 协程会从原暂停点继续。 */
    fun resume(visibleAssistantMessageId: String): Boolean = synchronized(lock) {
        val slot = slotsByMessage[visibleAssistantMessageId] ?: return@synchronized false
        if (slot.state.value == AgentRunControlState.RUNNING) return@synchronized false
        slot.state.value = AgentRunControlState.RUNNING
        publishLocked()
        true
    }

    /**
     * AgentLoop 的统一安全检查点。
     *
     * PAUSE_REQUESTED 在这里原子推进为 PAUSED，然后使用 StateFlow.first 挂起，没有轮询。
     */
    suspend fun awaitIfPaused(runId: String) {
        val state = synchronized(lock) {
            val slot = slotsByRun[runId] ?: return
            if (slot.state.value == AgentRunControlState.PAUSE_REQUESTED) {
                slot.state.value = AgentRunControlState.PAUSED
                publishLocked()
            }
            slot.state
        }
        if (state.value == AgentRunControlState.PAUSED) {
            state.first { it == AgentRunControlState.RUNNING }
        }
    }

    /** Run 终止或 Abort 后清理控制槽；先唤醒 gate，避免遗留挂起者。 */
    fun finish(visibleAssistantMessageId: String) {
        synchronized(lock) {
            val slot = slotsByMessage.remove(visibleAssistantMessageId) ?: return
            slot.state.value = AgentRunControlState.RUNNING
            slot.runId?.let(slotsByRun::remove)
            publishLocked()
        }
    }

    private fun publishLocked() {
        _snapshots.value = slotsByMessage.mapValues { (_, slot) ->
            AgentRunControlSnapshot(
                runId = slot.runId,
                visibleAssistantMessageId = slot.visibleAssistantMessageId,
                state = slot.state.value,
            )
        }
    }
}
