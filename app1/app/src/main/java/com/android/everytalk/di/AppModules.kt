package com.android.everytalk.di

import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.repository.MessageRepository
import com.android.everytalk.provider.ProviderRegistry
import com.android.everytalk.service.ChatService
import com.android.everytalk.service.ChatServiceImpl
import com.android.everytalk.statecontroller.StreamingMessageStateManager
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.util.cache.CacheManager
import com.android.everytalk.util.storage.FileManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
        }
    }
    
    single { ProviderRegistry(get()) }
}

val dataModule = module {
    single { AppDatabase.getDatabase(androidContext()) }
    single { get<AppDatabase>().apiConfigDao() }
    single { get<AppDatabase>().voiceConfigDao() }
    single { get<AppDatabase>().chatDao() }
    single { get<AppDatabase>().settingsDao() }
    single { MessageRepository(get()) }
}

val cacheModule = module {
    single { CacheManager.getInstance(androidContext()) }
    single { FileManager(androidContext()) }
}

val serviceModule = module {
    single<ChatService> { ChatServiceImpl(androidContext()) }
    single { StreamingMessageStateManager() }
}

val stateModule = module {
    single { ViewModelStateHolder() }
}

val allModules = listOf(
    networkModule,
    dataModule,
    cacheModule,
    serviceModule,
    stateModule
)
