package com.tapstream.downloader.domain.usecase

import android.content.Context
import com.tapstream.downloader.utils.DownloadHistoryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Use case for checking duplicate downloads
 */
class CheckDuplicateUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {
    operator fun invoke(videoUrl: String, filename: String): DuplicateCheckResult {
        val historyManager = DownloadHistoryManager(context)
        
        // Check by URL first (most reliable)
        if (historyManager.isAlreadyDownloadedByUrlHash(videoUrl)) {
            val downloadInfo = historyManager.getDownloadInfoByFilename(filename)
            val timeSince = downloadInfo?.let { 
                historyManager.getTimeSinceDownload(it.downloadedAt) 
            } ?: "recently"
            return DuplicateCheckResult.Duplicate(timeSince)
        }
        
        // Check by filename for non-generic names
        val isGenericFilename = filename.lowercase().let { name ->
            name.contains("facebook reel") || name.contains("tiktok") || 
            name == "video.mp4" || name == "instagram.mp4" || 
            name.startsWith("media_") || name.startsWith("- ")
        }
        
        if (!isGenericFilename && historyManager.isAlreadyDownloadedByFilename(filename)) {
            val downloadInfo = historyManager.getDownloadInfoByFilename(filename)
            val timeSince = downloadInfo?.let { 
                historyManager.getTimeSinceDownload(it.downloadedAt) 
            } ?: "recently"
            return DuplicateCheckResult.Duplicate(timeSince)
        }
        
        return DuplicateCheckResult.NotDuplicate
    }
}

sealed class DuplicateCheckResult {
    data class Duplicate(val timeSince: String) : DuplicateCheckResult()
    object NotDuplicate : DuplicateCheckResult()
}
