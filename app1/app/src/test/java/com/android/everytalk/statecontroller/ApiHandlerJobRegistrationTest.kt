package com.android.everytalk.statecontroller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHandlerJobRegistrationTest {
    @Test
    fun `立即完成的恢复任务也能识别自己并清除处理中状态`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var registeredJob: Job? = null
        var cleanupRan = false

        val job = scope.launchRegisteredJob(
            register = { registeredJob = it },
        ) {
            val thisJob = coroutineContext[Job]
            try {
                // 远端任务已经完成时，恢复流可能不发生任何挂起便直接结束。
            } finally {
                if (registeredJob == thisJob) {
                    registeredJob = null
                    cleanupRan = true
                }
            }
        }
        runBlocking { job.join() }

        assertTrue("恢复任务结束后必须执行当前任务清理", cleanupRan)
        assertNull("处理中 Job 必须清空", registeredJob)
        scope.cancel()
    }
}
