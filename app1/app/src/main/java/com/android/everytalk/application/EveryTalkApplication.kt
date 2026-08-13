package com.android.everytalk

import android.app.Application
import android.content.res.Configuration
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.data.database.AppDatabase
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

class EveryTalkApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        AppThemeController.applySavedTheme(this)

        startupScope.launch {
            ApiClient.initialize(this@EveryTalkApplication)
        }

        startupScope.launch(Dispatchers.IO) {
            AppDatabase.getDatabase(this@EveryTalkApplication)
                .agentDao()
                .recoverInterruptedAgentRuns()
        }

        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@EveryTalkApplication)
            modules(allModules)
        }

        startupScope.launch {
            DynamicIconSwitcher.syncIcon(this@EveryTalkApplication)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        startupScope.launch {
            DynamicIconSwitcher.syncIcon(this@EveryTalkApplication)
        }
    }
}
