package com.android.everytalk.data.computer

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException

/** Robolectric 不自带真实 MediaProvider；此提供器记录待发布状态，数据仍写入真实临时文件。 */
internal class DownloadTestProvider : ContentProvider() {
    val rows = linkedMapOf<Uri, Pair<ContentValues, File>>()
    var failPublish = false
    private var nextId = 0L
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val saved = ContentValues(requireNotNull(values))
        val requested = saved.getAsString(OpenableColumns.DISPLAY_NAME)
        var name = requested
        var index = 1
        while (rows.values.any { it.first.getAsString(OpenableColumns.DISPLAY_NAME) == name }) name = "${requested.substringBeforeLast('.')} (${index++}).${requested.substringAfterLast('.')}"
        saved.put(OpenableColumns.DISPLAY_NAME, name)
        val result = ContentUris.withAppendedId(uri, ++nextId)
        rows[result] = saved to File.createTempFile("download-", ".bin", requireNotNull(context).cacheDir)
        return result
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(
        rows.getValue(uri).second, if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
    )
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { rows[uri]?.let { addRow(arrayOf(it.first.getAsString(OpenableColumns.DISPLAY_NAME))) } }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (failPublish) throw IOException("模拟磁盘发布失败")
        rows.getValue(uri).first.putAll(values)
        return 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return if (rows.remove(uri)?.second?.delete() == true) 1 else 0
    }
    override fun getType(uri: Uri): String = "application/octet-stream"

    companion object {
        fun install(context: Context): DownloadTestProvider = DownloadTestProvider().also {
            it.attachInfo(context, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
            ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, it)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ComputerDownloadStoreTest {
    @Test
    fun `现代安卓完整写入固定下载目录且同名文件均保留`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = DownloadTestProvider.install(context)
        val source = File.createTempFile("source", ".bin", context.cacheDir).apply { writeBytes(byteArrayOf(0, 1, -1)) }
        val store = ComputerDownloadStore(context)
        val first = store.save(source, "报告.bin", "application/octet-stream")
        val second = store.save(source, "报告.bin", "application/octet-stream")
        assertEquals("Download/App/AppTalk/报告.bin", first.path)
        assertNotEquals(first.name, second.name)
        provider.rows.values.forEach { (metadata, file) ->
            assertEquals(ComputerDownloadStore.RELATIVE_PATH, metadata.getAsString(MediaStore.Downloads.RELATIVE_PATH))
            assertEquals(0, metadata.getAsInteger(MediaStore.Downloads.IS_PENDING))
            assertArrayEquals(source.readBytes(), file.readBytes())
        }
    }

    @Test
    fun `发布失败会移除半成品且保留原始文件`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = DownloadTestProvider.install(context).apply { failPublish = true }
        val source = File.createTempFile("source", ".txt", context.cacheDir).apply { writeText("完整内容") }
        assertThrows(IOException::class.java) { ComputerDownloadStore(context).save(source, "test.txt", "text/plain") }
        assertTrue(provider.rows.isEmpty())
        assertEquals("完整内容", source.readText())
    }

    @Test
    @Config(sdk = [28])
    fun `旧系统要求存储授权并避免覆盖已有下载`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(ComputerDownloadStore.needsPermission(context))
        val source = File.createTempFile("source", ".txt", context.cacheDir).apply { writeText("新内容") }
        val store = ComputerDownloadStore(context)
        assertThrows(IllegalStateException::class.java) { store.save(source, "legacy.txt", "text/plain") }
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val first = store.save(source, "legacy.txt", "text/plain")
        source.writeText("第二份")
        val second = store.save(source, "legacy.txt", "text/plain")
        assertEquals("新内容", File(requireNotNull(first.uri.path)).readText())
        assertEquals("第二份", File(requireNotNull(second.uri.path)).readText())
        assertNotEquals(first.uri, second.uri)
        assertEquals(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "App/AppTalk").absolutePath,
            File(requireNotNull(second.uri.path)).parent)
    }
}
