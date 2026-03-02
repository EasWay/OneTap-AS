package com.tapstream.downloader.worker

import androidx.hilt.work.HiltWorkerFactory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Entry point for accessing HiltWorkerFactory
 * Required for WorkManager integration with Hilt
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface HiltWorkerFactoryEntryPoint {
    fun workerFactory(): HiltWorkerFactory
}
