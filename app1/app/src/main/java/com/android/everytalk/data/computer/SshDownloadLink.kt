package com.android.everytalk.data.computer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Both executors send only this launcher and return link metadata over SSH. */
internal object SshDownloadLink {
    private val urlPattern = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com/[0-9a-f]{48}/[A-Za-z0-9_.~%-]+")
    private val script by lazy {
        checkNotNull(SshDownloadLink::class.java.getResourceAsStream("/computer/ssh-download.py"))
            .bufferedReader().use { it.readText().trimEnd() }
    }

    fun command(path: String, name: String?): String {
        fun quote(value: String): String {
            require(value.length <= 4096 && value.none { it in "\u0000\r\n" }) { "下载参数无效" }
            return "'${value.replace("'", "'\\''")}'"
        }
        return "python3 - ${quote(path)} ${quote(name.orEmpty())} 3600 <<'EVERYTALK_SSH_DOWNLOAD'\n$script\nEVERYTALK_SSH_DOWNLOAD"
    }

    fun parse(exitCode: Int?, stdout: String): JsonObject {
        val value = if (exitCode == 0) runCatching { Json.parseToJsonElement(stdout) as? JsonObject }.getOrNull() else null
        fun field(key: String) = value?.get(key) as? JsonPrimitive
        val error = field("error")?.takeIf { it.isString }?.content
        val link = field("file_link")?.takeIf { it.isString }?.content
        if (error != null || link == null || !urlPattern.matches(link) || field("name")?.isString != true ||
            (field("size")?.longOrNull ?: -1) < 0 || (field("expires_at")?.longOrNull ?: 0) <= 0
        ) throw ComputerException(ComputerErrorCodes.DOWNLOAD_INTERRUPTED,
            error ?: "所选服务器未能启动限时 HTTPS 下载（需要 Python 3 和 cloudflared）")
        return JsonObject(value!!.filterKeys { it in setOf("file_link", "name", "size", "expires_at") })
    }
}
