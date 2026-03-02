package com.tapstream.downloader.download

/**
 * Sealed class representing the result of a batch download operation
 */
sealed class BatchDownloadResult {
    /**
     * Download completed successfully
     * @param id Unique identifier for this download
     * @param filename Name of the downloaded file
     * @param filePath Path to the downloaded file
     */
    data class Success(
        val id: String,
        val filename: String,
        val filePath: String
    ) : BatchDownloadResult()
    
    /**
     * Download failed with an error
     * @param id Unique identifier for this download
     * @param filename Name of the file that failed to download
     * @param error Error message describing what went wrong
     */
    data class Error(
        val id: String,
        val filename: String,
        val error: String
    ) : BatchDownloadResult()
}
