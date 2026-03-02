package com.tapstream.downloader.download

/**
 * Data class representing a batch download request
 * @param id Unique identifier for this download
 * @param downloadUrl URL to download from
 * @param filename Name for the downloaded file
 */
data class BatchDownloadRequest(
    val id: String,
    val downloadUrl: String,
    val filename: String
)
