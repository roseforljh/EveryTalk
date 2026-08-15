package com.android.everytalk.data.agent

import com.android.everytalk.data.network.AppToolExecutor
import com.android.everytalk.data.computer.ComputerToolApprovalProvider

/**
 * App 内唯一的 Tool Executor 注册点。
 *
 * Executor 由 AppViewModel 持有生命周期。AgentLoop 每次执行工具时再读取当前值，
 * 避免静态 Provider Client 各自保存一份回调并产生不同的工具循环行为。
 */
object AgentToolExecutorRegistry {
    private var owner: Any? = null
    private var executor: AppToolExecutor? = null
    private var approvalProvider: ComputerToolApprovalProvider? = null

    @Synchronized
    fun register(
        owner: Any,
        executor: AppToolExecutor,
        approvalProvider: ComputerToolApprovalProvider? = null,
    ) {
        this.owner = owner
        this.executor = executor
        this.approvalProvider = approvalProvider
    }

    /** 前台服务进程恢复时只补空缺，不覆盖 AppViewModel 已注册的完整执行器。 */
    @Synchronized
    fun registerIfAbsent(
        owner: Any,
        executor: AppToolExecutor,
        approvalProvider: ComputerToolApprovalProvider? = null,
    ) {
        if (this.executor != null) return
        this.owner = owner
        this.executor = executor
        this.approvalProvider = approvalProvider
    }

    @Synchronized
    fun current(): AppToolExecutor? = executor

    @Synchronized
    fun currentApprovalProvider(): ComputerToolApprovalProvider? = approvalProvider

    @Synchronized
    fun clear(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            executor = null
            approvalProvider = null
        }
    }
}
