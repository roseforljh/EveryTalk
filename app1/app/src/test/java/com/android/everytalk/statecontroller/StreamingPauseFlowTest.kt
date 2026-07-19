package com.android.everytalk.statecontroller

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingPauseFlowTest {

    @Test
    fun `暂停期间冻结最后一帧且恢复时只追平最新状态`() = runTest {
        val source = MutableStateFlow(0)
        val paused = MutableStateFlow(false)
        val values = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.freezeWhileStreamingPaused(paused).collect(values::add)
        }

        runCurrent()
        assertEquals(listOf(0), values)

        paused.value = true
        runCurrent()
        source.value = 1
        source.value = 2
        runCurrent()
        assertEquals(listOf(0), values)

        paused.value = false
        runCurrent()
        assertEquals(listOf(0, 2), values)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(0, 2), values)
        job.cancel()
    }
}
