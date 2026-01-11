package com.android.everytalk.statecontroller

import com.android.everytalk.util.debug.PerformanceMonitor
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingBufferTest {
    
    private lateinit var buffer: StreamingBuffer
    private lateinit var testScope: TestScope
    private val testDispatcher = StandardTestDispatcher()
    
    private val collectedChunks = mutableListOf<String>()
    
    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.v(any(), any()) } returns 0
        
        mockkObject(PerformanceMonitor)
        justRun { PerformanceMonitor.recordBufferFlush(any(), any(), any()) }
        
        testScope = TestScope(testDispatcher)
        collectedChunks.clear()
    }
    
    @After
    fun tearDown() {
        unmockkAll()
    }
    
    private fun createBuffer(
        batchThreshold: Int = 100
    ): StreamingBuffer {
        return StreamingBuffer(
            messageId = "test-message-1",
            batchThreshold = batchThreshold,
            onUpdate = { chunk -> collectedChunks.add(chunk) },
            coroutineScope = testScope,
            enableAdaptiveThrottling = false,
            enableBatchMerging = false
        )
    }
    
    @Test
    fun `first append triggers flush due to initial time delta`() = testScope.runTest {
        buffer = createBuffer(batchThreshold = 100)
        buffer.append("Hello")
        
        assertEquals(1, collectedChunks.size)
        assertEquals("Hello", collectedChunks[0])
    }
    
    @Test
    fun `flush forces output of buffered content`() = testScope.runTest {
        buffer = createBuffer(batchThreshold = 100)
        buffer.append("Hello")
        collectedChunks.clear()
        
        buffer.append(" World")
        buffer.flush()
        
        assertTrue(collectedChunks.any { it.contains("World") })
    }
    
    @Test
    fun `batch threshold triggers flush when exceeded`() = testScope.runTest {
        buffer = createBuffer(batchThreshold = 5)
        buffer.append("12")
        collectedChunks.clear()
        
        buffer.append("34567")
        
        assertTrue(collectedChunks.isNotEmpty())
        val allContent = collectedChunks.joinToString("")
        assertTrue(allContent.contains("34567"))
    }
    
    @Test
    fun `clear removes buffered content`() = testScope.runTest {
        buffer = createBuffer(batchThreshold = 100)
        buffer.append("A")
        collectedChunks.clear()
        
        buffer.clear()
        buffer.flush()
        
        assertTrue(collectedChunks.isEmpty() || collectedChunks.all { it.isEmpty() })
    }
    
    @Test
    fun `multiple appends accumulate correctly`() = testScope.runTest {
        buffer = createBuffer(batchThreshold = 100)
        buffer.append("A")
        buffer.append("B")
        buffer.append("C")
        buffer.flush()
        
        val allContent = collectedChunks.joinToString("")
        assertTrue(allContent.contains("A"))
        assertTrue(allContent.contains("B"))
        assertTrue(allContent.contains("C"))
    }
}
