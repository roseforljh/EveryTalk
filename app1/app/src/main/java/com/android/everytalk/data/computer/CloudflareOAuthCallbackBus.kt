package com.android.everytalk.data.computer

import android.net.Uri
import com.android.everytalk.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** 进程内 OAuth 回调通道；授权码只在内存中转发，不写日志或 Room。 */
object CloudflareOAuthCallbackBus {
    private val _callbacks = MutableStateFlow<Uri?>(null)
    val callbacks: SharedFlow<Uri?> = _callbacks.asStateFlow()

    fun publish(uri: Uri) {
        // 支持 everytalk:// 和正式配置的 HTTPS 回调；真正的 state/目标归属
        // 仍由各个 OAuth Flow 校验，不能只凭 URL 形状接收授权结果。
        val configured = BuildConfig.CLOUDFLARE_OAUTH_REDIRECT_URI.trimEnd('/')
        val actual = uri.toString().substringBefore('?').trimEnd('/')
        if (actual == configured && uri.getQueryParameter("state") != null) {
            _callbacks.value = uri
        }
    }

    fun consume(uri: Uri) {
        _callbacks.compareAndSet(uri, null)
    }
}
