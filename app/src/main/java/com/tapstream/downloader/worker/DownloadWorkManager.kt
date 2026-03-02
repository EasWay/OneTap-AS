package com.tapstream.downloader.worker

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for scheduling and monitoring download workers
 * 
 * Features:
 * - Unique work names to prevent duplicate downloads
 * - Network constraints for reliability
 * - Exponential backoff retry policy
 * - Progress monitoring via LiveData
 */
@Singleton
class DownloadWorkManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)
    
    /**
     * Schedule a download with WorkManager
     * 
     * @param downloadUrl Direct download URL
     * @param filename Output filename
     * @param videoUrl Original video URL (for reference)
     * @return Work ID for monitoring
     */
    fun scheduleDownload(
        downloadUrl: String,
        filename: String,
        videoUrl: String = ""
    ): UUID {
        val inputData = workDataOf(
            DownloadWorker.KEY_DOWNLOAD_URL to downloadUrl,
            DownloadWorker.KEY_FILENAME to filename,
            DownloadWorker.KEY_VIDEO_URL to videoUrl
        )
        
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false) // Allow downloads even on low battery
            .build()
        
        val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10, // Initial backoff delay
                TimeUnit.SECONDS
            )
            .addTag("download")
            .addTag(filename)
            .build()
        
        // Use unique work name to prevent duplicate downloads
        val workName = "download_$filename"
        workManager.enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.KEEP, // Keep existing work if already scheduled
            downloadRequest
        )
        
        return downloadRequest.id
    }
    
    /**
     * Get work info as LiveData for observing progress
     */
    fun getWorkInfoLiveData(workId: UUID): LiveData<WorkInfo> {
        return workManager.getWorkInfoByIdLiveData(workId)
    }
    
    /**
     * Get work info synchronously
     */
    suspend fun getWorkInfo(workId: UUID): WorkInfo? {
        return try {
            workManager.getWorkInfoById(workId).get()
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Cancel a download by work ID
     */
    fun cancelDownload(workId: UUID) {
        workManager.cancelWorkById(workId)
    }
    
    /**
     * Cancel all downloads
     */
    fun cancelAllDownloads() {
        workManager.cancelAllWorkByTag("download")
    }
    
    /**
     * Get all download works
     */
    fun getAllDownloadWorks(): LiveData<List<WorkInfo>> {
        return workManager.getWorkInfosByTagLiveData("download")
    }
    
    /**
     * Check if a download is already in progress for a filename
     */
    suspend fun isDownloadInProgress(filename: String): Boolean {
        val workInfos = workManager.getWorkInfosByTag(filename).get()
        return workInfos.any { 
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED 
        }
    }
    
    /**
     * Prune completed work (cleanup old work records)
     */
    fun pruneCompletedWork() {
        workManager.pruneWork()
    }
}
