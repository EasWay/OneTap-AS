package com.tapstream.downloader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class with Hilt dependency injection and WorkManager configuration
 * 
 * @HiltAndroidApp triggers Hilt's code generation including:
 * - Base class for the application
 * - Component hierarchy
 * - Dependency graph
 */
@HiltAndroidApp
class OneTapApplication : Application(), Configuration.Provider {
    
    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    
    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("OneTap_App", "Application initialized with Hilt DI and WorkManager")
    }
    
    /**
     * Provide WorkManager configuration with Hilt worker factory
     * This enables dependency injection in Workers
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
