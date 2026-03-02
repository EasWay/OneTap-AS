package com.tapstream.downloader.download

import java.util.UUID

/**
 * Represents the state of a download managed by WorkManager
 */
sealed class DownloadState {
    data class Idle(val message: String = "Ready to download") : DownloadState()
    
    data class Scheduled(
        val workId: UUID,
        val filename: String
    ) : DownloadState()
    
    data class InProgress(
        val workId: UUID,
        val filename: String,
        val progress: Int,
        val downloaded: Long,
        val total: Long
    ) : DownloadState()
    
    data class Completed(
        val workId: UUID,
        val filename: String,
        val filePath: String,
        val fileSize: Long
    ) : DownloadState()
    
    data class Failed(
        val workId: UUID?,
        val filename: String,
        val error: String,
        val canRetry: Boolean = true
    ) : DownloadState()
    
    data class Cancelled(
        val workId: UUID,
        val filename: String
    ) : DownloadState()
}

/**
 * Download metadata for tracking
 */
data class DownloadMetadata(
    val workId: UUID,
    val filename: String,
    val videoUrl: String,
    val downloadUrl: String,
    val scheduledAt: Long = System.currentTimeMillis()
)
