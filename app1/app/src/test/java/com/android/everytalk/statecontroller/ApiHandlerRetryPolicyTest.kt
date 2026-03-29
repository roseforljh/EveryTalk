package com.android.everytalk.statecontroller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHandlerRetryPolicyTest {

    @Test
    fun `network retry never returns early without retry action`() {
        val shouldReturnEarly = shouldReturnEarlyForNetworkRetry(
            allowRetry = true,
            isNetworkError = true,
            currentRetryCount = 0,
            maxRetryAttempts = 3,
            hasRetryAction = false,
        )

        assertFalse(shouldReturnEarly)
    }

    @Test
    fun `network retry can return early only when retry action exists`() {
        val shouldReturnEarly = shouldReturnEarlyForNetworkRetry(
            allowRetry = true,
            isNetworkError = true,
            currentRetryCount = 0,
            maxRetryAttempts = 3,
            hasRetryAction = true,
        )

        assertTrue(shouldReturnEarly)
    }

    @Test
    fun `non network errors never return early`() {
        val shouldReturnEarly = shouldReturnEarlyForNetworkRetry(
            allowRetry = true,
            isNetworkError = false,
            currentRetryCount = 0,
            maxRetryAttempts = 3,
            hasRetryAction = true,
        )

        assertFalse(shouldReturnEarly)
    }
}
