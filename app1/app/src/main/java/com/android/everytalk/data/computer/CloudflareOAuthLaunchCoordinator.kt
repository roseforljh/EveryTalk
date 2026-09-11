package com.android.everytalk.data.computer

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 设置页 OAuth 启动协调器。
 * 只负责生成授权 URL 和打开浏览器；Token 交换必须由收到回调的设置流程完成。
 */
class CloudflareOAuthLaunchCoordinator(
    private val context: Context,
    private val flow: CloudflareSettingsOAuthFlow,
) {
    fun launch(targetBinding: String): Result<CloudflareOAuthStart> = runCatching {
        val start = flow.start(targetBinding)
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(start.authorizationUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        start
    }
}
