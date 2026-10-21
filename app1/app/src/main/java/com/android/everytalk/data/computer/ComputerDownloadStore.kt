package com.android.everytalk.data.computer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException

/** 用户可在系统“下载”中找到的副本；聊天仍持有私有附件，删除聊天不会删除此副本。 */
data class ComputerSavedDownload(val uri: Uri, val name: String) {
    val path: String get() = "${ComputerDownloadStore.RELATIVE_PATH}/$name"
}

/**
 * 仅接收已经下载并校验完成的私有文件，保存到固定公共目录。
 * Android 10 起通过 MediaStore 发布完整文件；旧系统先写临时文件，再以不覆盖方式移入。
 */
class ComputerDownloadStore(private val context: Context) {
    fun save(source: File, name: String, mimeType: String): ComputerSavedDownload {
        require(name.isNotBlank() && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "下载文件名无效"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveMediaStore(source, name, mimeType)
        else saveLegacy(source, name, mimeType)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveMediaStore(source: File, name: String, mimeType: String): ComputerSavedDownload {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建下载文件，请检查手机存储空间")
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("无法写入下载目录")
            output.use { target ->
                val count = source.inputStream().use { it.copyTo(target) }
                check(count == source.length()) { "下载副本不完整" }
            }
            // MediaStore 会为同名文件分配新名字，必须读取实际名称，不能向用户报告旧名称。
            val actualName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: throw IOException("无法核对下载文件名")
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) {
                "无法完成下载文件保存"
            }
            return ComputerSavedDownload(uri, actualName)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(source: File, name: String, mimeType: String): ComputerSavedDownload {
        check(!needsPermission(context)) { "保存到下载目录需要存储权限，请允许后重试" }
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "App/AppTalk")
        check(directory.canonicalFile == directory.absoluteFile) { "下载目录不能是符号链接" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建 Download/App/AppTalk 目录" }
        val temporary = File.createTempFile(".apptalk-", ".part", directory)
        try {
            FileOutputStream(temporary).use { output ->
                check(source.inputStream().use { it.copyTo(output) } == source.length()) { "下载副本不完整" }
                output.fd.sync()
            }
            val extension = name.substringAfterLast('.', "").takeIf { name.lastIndexOf('.') > 0 }
            val stem = if (extension != null) name.substringBeforeLast('.') else name
            for (index in 0..9999) {
                val candidate = if (index == 0) name else "$stem ($index)${extension?.let { ".$it" }.orEmpty()}"
                val target = File(directory, candidate)
                try {
                    // 不使用 REPLACE_EXISTING：其他任务或应用先创建了同名文件时，换名重试。
                    Files.move(temporary.toPath(), target.toPath())
                } catch (_: FileAlreadyExistsException) { continue }
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
                return ComputerSavedDownload(Uri.fromFile(target), candidate)
            }
            throw IOException("同名文件过多，请整理下载目录后重试")
        } finally { temporary.delete() }
    }

    /** 只回滚本次刚创建的副本，不扫描或删除用户已有文件。 */
    fun delete(saved: ComputerSavedDownload) {
        if (saved.uri.scheme == "content") context.contentResolver.delete(saved.uri, null, null)
        else check(File(requireNotNull(saved.uri.path)).delete()) { "下载副本清理失败" }
    }

    companion object {
        const val RELATIVE_PATH = "Download/App/AppTalk"

        fun needsPermission(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    }
}
