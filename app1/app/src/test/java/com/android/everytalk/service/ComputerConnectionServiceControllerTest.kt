package com.android.everytalk.service

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 验证短任务立即结束时，前台服务仍有机会先完成 startForeground。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComputerConnectionServiceControllerTest {
    @Test
    fun `releasing last token queues an idle stop instead of cancelling service creation`() {
        val context = RecordingServiceContext(ApplicationProvider.getApplicationContext())

        ComputerConnectionServiceController.acquire(context).close()

        assertEquals(0, context.directStopCount)
        assertEquals(2, context.foregroundStartActions.size)
    }

    /** 只记录服务调度方式，不创建真实 Android Service。 */
    private class RecordingServiceContext(base: Context) : ContextWrapper(base) {
        val foregroundStartActions = mutableListOf<String?>()
        var directStopCount = 0

        override fun getApplicationContext(): Context = this

        override fun startForegroundService(service: Intent): ComponentName {
            foregroundStartActions += service.action
            return service.component ?: ComponentName(packageName, ComputerConnectionService::class.java.name)
        }

        override fun stopService(name: Intent): Boolean {
            directStopCount += 1
            return true
        }
    }
}
