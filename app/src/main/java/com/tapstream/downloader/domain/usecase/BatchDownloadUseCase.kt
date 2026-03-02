package com.tapstream.downloader.domain.usecase

import android.content.Context
import com.tapstream.downloader.download.BatchDownloadResult
import com.tapstream.downloader.repository.VideoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case for batch downloading multiple videos
 */
class BatchDownloadUseCase @Inject constructor(
    private val videoRepository: VideoRepository,
    @ApplicationContext private val context: Context
) {
    suspend operator fun invoke(
        videoUrls: List<String>,
        progressCallback: ((String, com.tapstream.downloader.download.DownloadProgress) -> Unit)? = null
    ): Flow<BatchDownloadResult> {
        return videoRepository.downloadMultipleVideos(context, videoUrls, progressCallback)
    }
}
