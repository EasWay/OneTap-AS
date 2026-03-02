package com.tapstream.downloader.domain.model

/**
 * Domain model for video download results
 * Sealed class for type-safe result handling
 */
sealed class VideoDownloadResult {
    data class Success(
        val filename: String,
        val message: String = "Download completed successfully"
    ) : VideoDownloadResult()
    
    data class BatchInitiated(
        val urlCount: Int
    ) : VideoDownloadResult()
    
    data class Duplicate(
        val filename: String,
        val timeSince: String
    ) : VideoDownloadResult()
    
    data class Error(
        val message: String,
        val errorType: ErrorType = ErrorType.UNKNOWN
    ) : VideoDownloadResult()
    
    data class UnsupportedContent(
        val message: String
    ) : VideoDownloadResult()
}

enum class ErrorType {
    NETWORK,
    TIMEOUT,
    SERVER,
    INVALID_URL,
    EMPTY_CLIPBOARD,
    UNSUPPORTED_CONTENT,
    STORAGE,
    UNKNOWN
}
