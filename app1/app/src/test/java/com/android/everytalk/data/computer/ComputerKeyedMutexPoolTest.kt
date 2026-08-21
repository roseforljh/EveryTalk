package com.android.everytalk.data.computer

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerKeyedMutexPoolTest {
    @Test
    fun `same resource keeps one lock and total lock count stays bounded`() {
        val pool = ComputerKeyedMutexPool(stripeCount = 8)

        assertSame(pool.forKey("workspace-1"), pool.forKey("workspace-1"))
        val distinctLocks = (0 until 10_000)
            .map { pool.forKey("resource-$it") }
            .toSet()

        assertTrue(distinctLocks.size <= 8)
    }
}
