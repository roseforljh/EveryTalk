package com.android.everytalk.data.computer

import kotlinx.coroutines.sync.Mutex

/**
 * 为 Computer 的同资源操作提供固定数量的互斥锁。
 *
 * 输入是 Workspace、文件或 Execution 的稳定 ID，输出是该 ID 固定对应的锁。
 * 固定分片避免“每个历史 ID 永久留一个 Mutex”的内存增长，同时保持同一资源严格串行。
 */
internal class ComputerKeyedMutexPool(stripeCount: Int = DEFAULT_STRIPE_COUNT) {
    private val locks = Array(stripeCount.also { require(it > 0) }) { Mutex() }

    fun forKey(key: String): Mutex = locks[Math.floorMod(key.hashCode(), locks.size)]

    private companion object {
        // ponytail: 64 个分片会让不同资源极低概率互相等待；若真实并发超过该规模再扩大。
        const val DEFAULT_STRIPE_COUNT = 64
    }
}
