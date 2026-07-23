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
            install(ContentNegotiation) {
                json(get())
            }
            install(WebSockets) {
                pingIntervalMillis = 30_000
                maxFrameSize = MAX_WEBSOCKET_FRAME_BYTES
            }
        }
    }
    
    single { ProviderRegistry(androidContext(), get()) }
}

val appModule = module {
    single { FileManager(androidContext()) }
    single { MathJaxSvgRenderer(androidContext()) }
}

val allModules = listOf(
    networkModule,
    appModule,
)
