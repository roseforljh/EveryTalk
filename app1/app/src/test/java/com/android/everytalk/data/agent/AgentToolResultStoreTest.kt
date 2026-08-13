package com.android.everytalk.data.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentToolResultStoreTest {
    @Test
    fun `完整结果写入私有目录并可按Run清理`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AgentToolResultStore(context)

        val archive = store.archive("run-archive", "tool-archive", JsonPrimitive("完整结果"))

        assertNotNull(archive)
        val file = File(context.filesDir, requireNotNull(archive).relativePath)
        assertTrue(file.isFile)
        assertEquals(JsonPrimitive("完整结果").toString(), file.readText())
        assertTrue(archive.byteCount > 0L)
        assertEquals(64, archive.sha256.length)
        assertFalse(file.resolveSibling("${file.name}.tmp").exists())

        store.deleteRun("run-archive")
        assertFalse(file.exists())
    }
}
