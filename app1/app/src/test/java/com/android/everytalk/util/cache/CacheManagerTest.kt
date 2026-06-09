package com.android.everytalk.util.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheManagerTest {
    private val cacheManager = CacheManager.getInstance(ApplicationProvider.getApplicationContext<Context>())

    @After
    fun tearDown() = runBlocking {
        cacheManager.clearAllCaches()
        cacheManager.cleanup()
    }

    @Test
    fun `cleanup does not make singleton cache unusable`() = runBlocking {
        cacheManager.cacheApiResponse("before", "first")
        assertEquals("first", cacheManager.getCachedApiResponse("before"))

        cacheManager.cleanup()

        cacheManager.cacheApiResponse("after", "second")
        assertEquals("second", cacheManager.getCachedApiResponse("after"))
    }
}
