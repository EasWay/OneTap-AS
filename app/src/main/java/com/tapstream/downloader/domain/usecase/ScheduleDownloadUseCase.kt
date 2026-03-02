package com.tapstream.downloader.domain.usecase

import com.tapstream.downloader.worker.DownloadWorkManager
import java.util.UUID
import javax.inject.Inject

/**
 * Use case for scheduling reliable background downloads with WorkManager
 * 
 * Benefits over direct downloads:
 * - Survives app suspension and device reboot
 * - Automatic retry with exponential backoff
 * - Download resume capability
 * - Better battery optimization
 */
class ScheduleDownloadUseCase @Inject constructor(
    private val downloadWorkManager: DownloadWorkManager
) {
    /**
     * Schedule a download to be executed by WorkManager
     * 
     * @param downloadUrl Direct download URL
     * @param filename Output filename
     * @param videoUrl Original video URL (optional, for reference)
     * @return Work ID for monitoring progress
     */
    operator fun invoke(
        downloadUrl: String,
        filename: String,
        videoUrl: String = ""
    ): UUID {
        return downloadWorkManager.scheduleDownload(
            downloadUrl = downloadUrl,
            filename = filename,
            videoUrl = videoUrl
        )
    }
    
    /**
     * Check if a download is already in progress
     */
    suspend fun isDownloadInProgress(filename: String): Boolean {
        return downloadWorkManager.isDownloadInProgress(filename)
    }
    
    /**
     * Cancel a specific download
     */
    fun cancelDownload(workId: UUID) {
        downloadWorkManager.cancelDownload(workId)
    }
}
