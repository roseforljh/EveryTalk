package com.android.everytalk.data.agent

import com.android.everytalk.data.network.AppToolExecutor

/**
 * App 内唯一的 Tool Executor 注册点。
 *
 * Executor 由 AppViewModel 持有生命周期。AgentLoop 每次执行工具时再读取当前值，
 * 避免静态 Provider Client 各自保存一份回调并产生不同的工具循环行为。
 */
object AgentToolExecutorRegistry {
    private var owner: Any? = null
    private var executor: AppToolExecutor? = null

    @Synchronized
    fun register(owner: Any, executor: AppToolExecutor) {
        this.owner = owner
        this.executor = executor
    }

    @Synchronized
    fun current(): AppToolExecutor? = executor

    @Synchronized
    fun clear(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            executor = null
        }
    }
}
