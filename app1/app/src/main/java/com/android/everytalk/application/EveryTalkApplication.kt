package com.android.everytalk

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.di.allModules
import com.android.everytalk.util.DynamicIconSwitcher
import com.android.everytalk.util.theme.AppThemeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class EveryTalkApplication : Application(), SingletonImageLoader.Factory {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 同一图片的并发加载共用下载结果；保留默认缓存与解码器，取消等待者不会挂住其余请求。 */
    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(
                concurrentRequestStrategy = { DeDupeConcurrentRequestStrategy() },
            ))
        }
        .build()

    override fun onCreate() {
        super.onCreate()

        AppThemeController.applySavedTheme(this)

        startupScope.launch {
            ApiClient.initialize(this@EveryTalkApplication)
        }

        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@EveryTalkApplication)
            modules(allModules)
        }

        startupScope.launch {
            DynamicIconSwitcher.syncIcon(this@EveryTalkApplication)
        }
        startupScope.launch {
            com.android.everytalk.service.SyncScheduler.start(this@EveryTalkApplication)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        startupScope.launch {
            DynamicIconSwitcher.syncIcon(this@EveryTalkApplication)
        }
    }
}
