package com.tapstream.downloader.domain.usecase

import android.content.Context
import com.tapstream.downloader.domain.model.ErrorType
import com.tapstream.downloader.domain.model.VideoDownloadResult
import com.tapstream.downloader.repository.VideoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Use case for downloading a single video
 * Encapsulates business logic for video downloads
 */
class DownloadVideoUseCase @Inject constructor(
    private val videoRepository: VideoRepository,
    @ApplicationContext private val context: Context
) {
    suspend operator fun invoke(videoUrl: String, retryCount: Int = 2): VideoDownloadResult {
        return try {
            val result = videoRepository.downloadVideo(context, videoUrl, retryCount)
            
            when {
                result == "BATCH_DOWNLOAD_INITIATED" -> {
                    val urls = videoUrl.split("|").filter { it.isNotBlank() }
                    VideoDownloadResult.BatchInitiated(urls.size)
                }
                result.startsWith("DUPLICATE_DETECTED:") -> {
                    val timeSince = result.substringAfter(":")
                    VideoDownloadResult.Duplicate("", timeSince)
                }
                result.contains("Success") || result.contains("Saved") -> {
                    VideoDownloadResult.Success(extractFilename(result))
                }
                result.contains("not supported", ignoreCase = true) -> {
                    VideoDownloadResult.UnsupportedContent(result)
                }
                else -> {
                    VideoDownloadResult.Error(result, mapErrorType(result))
                }
            }
        } catch (e: Exception) {
            VideoDownloadResult.Error(
                e.message ?: "Unknown error",
                ErrorType.UNKNOWN
            )
        }
    }
    
    private fun extractFilename(result: String): String {
        // Extract filename from success message if present
        return result.substringAfter(":", "video")
    }
    
    private fun mapErrorType(errorMessage: String): ErrorType {
        return when {
            errorMessage.contains("timeout", ignoreCase = true) -> ErrorType.TIMEOUT
            errorMessage.contains("connection", ignoreCase = true) -> ErrorType.NETWORK
            errorMessage.contains("server", ignoreCase = true) -> ErrorType.SERVER
            errorMessage.contains("no space left", ignoreCase = true) ||
            errorMessage.contains("enospc", ignoreCase = true) ||
            errorMessage.contains("not enough storage", ignoreCase = true) -> ErrorType.STORAGE
            errorMessage.contains("invalid", ignoreCase = true) -> ErrorType.INVALID_URL
            else -> ErrorType.UNKNOWN
        }
    }
}
