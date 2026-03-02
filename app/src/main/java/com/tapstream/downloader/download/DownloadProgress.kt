package com.tapstream.downloader.download

/**
 * Sealed class representing different states of a download operation
 */
sealed class DownloadProgress {
    /**
     * Download has started
     * @param id Unique identifier for this download
     * @param filename Name of the file being downloaded
     */
    data class Started(
        val id: String,
        val filename: String
    ) : DownloadProgress()
    
    /**
     * Download is in progress
     * @param id Unique identifier for this download
     * @param filename Name of the file being downloaded
     * @param downloaded Bytes downloaded so far
     * @param total Total bytes to download (0 if unknown)
     * @param percentage Progress percentage (0-100)
     * @param speed Download speed in bytes per second
     */
    data class Progress(
        val id: String,
        val filename: String,
        val downloaded: Long,
        val total: Long,
        val percentage: Double,
        val speed: Double
    ) : DownloadProgress()
    
    /**
     * Download completed successfully
     * @param id Unique identifier for this download
     * @param filename Name of the downloaded file
     * @param filePath Path to the downloaded file
     * @param fileSize Size of the downloaded file in bytes
     */
    data class Completed(
        val id: String,
        val filename: String,
        val filePath: String,
        val fileSize: Long
    ) : DownloadProgress()
    
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
    ) : DownloadProgress()
}
