package com.android.everytalk

import android.app.Application
import com.android.everytalk.di.allModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class EveryTalkApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@EveryTalkApplication)
            modules(allModules)
        }
    }
}
