package com.android.everytalk.di

import com.android.everytalk.data.network.MAX_WEBSOCKET_FRAME_BYTES
import com.android.everytalk.provider.ProviderRegistry
import com.android.everytalk.ui.components.math.MathJaxSvgRenderer
import com.android.everytalk.util.storage.FileManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val networkModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
    
    single {
        HttpClient(OkHttp) {
            engine {
                // ponytail: 普通聊天长 SSE 先固定 HTTP/1.1，绕开 Android HTTP/2/TLS 长连接的记录损坏；恢复 HTTP/2 需有 A/B 证据。
                config { protocols(listOf(okhttp3.Protocol.HTTP_1_1)) }
            }
            install(ContentNegotiation) {
                json(get())
            }
            install(WebSockets) {
                pingIntervalMillis = 30_000
                maxFrameSize = MAX_WEBSOCKET_FRAME_BYTES
            }
        }
    }
    
    single { ProviderRegistry(get()) }
}

val appModule = module {
    single { FileManager(androidContext()) }
    single { MathJaxSvgRenderer(androidContext()) }
}

val allModules = listOf(
    networkModule,
    appModule,
)
