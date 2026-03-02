package com.tapstream.downloader.domain.model

/**
 * Domain model for video information
 */
data class VideoInfo(
    val url: String,
    val filename: String,
    val title: String? = null,
    val platform: String,
    val downloadUrl: String,
    val isImage: Boolean = false,
    val isAudio: Boolean = false,
    val isDirect: Boolean = false
)

/**
 * Domain model for batch download request
 */
data class BatchDownloadInfo(
    val videos: List<VideoInfo>
)
