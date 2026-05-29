package com.tapstream.downloader.download

import android.content.Context
import android.util.Log
import com.tapstream.downloader.di.DownloadHttpClient
import com.tapstream.downloader.utils.StreamUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advanced download manager with Dependency Injection
 * 
 * Now uses injected OkHttpClient from NetworkModule for:
 * - Shared connection pool
 * - TCP Keep-Alive benefits
 * - Reduced memory usage
 */
@Singleton
class AdvancedDownloadManager @Inject constructor(
    @DownloadHttpClient private val client: OkHttpClient,
    @ApplicationContext private val context: Context
) {
    
    private val tag = "AdvancedDownloadManager"
    
    /**
     * Download a file with progress tracking
     */
    fun downloadWithProgress(downloadUrl: String, filename: String, totalSize: Long? = null): Flow<DownloadProgress> = channelFlow {
        val progressChannel = Channel<DownloadProgress>(Channel.UNLIMITED)
        
        try {
            val id = System.currentTimeMillis().toString()
            send(DownloadProgress.Started(id, filename))
            
            val request = Request.Builder()
                .url(downloadUrl)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                send(DownloadProgress.Error(id, filename, "HTTP ${response.code}"))
                close()
                return@channelFlow
            }
            
            val body = response.body ?: run {
                send(DownloadProgress.Error(id, filename, "Empty response body"))
                close()
                return@channelFlow
            }
            
            var contentLength = body.contentLength()
            if (contentLength <= 0 && totalSize != null && totalSize > 0) {
                contentLength = totalSize
                Log.i(tag, "Using provided totalSize for progress: $contentLength")
            }
            val tempFile = File(context.cacheDir, filename)
            
            // Launch a coroutine to forward progress updates from the channel
            val progressJob = launch {
                for (progress in progressChannel) {
                    send(progress)
                }
            }
            
            try {
                // Use Okio for optimized stream copying with progress tracking
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        StreamUtils.copyWithSpeed(input, output, contentLength) { bytesRead, percentage, speed ->
                            // Send progress updates through the channel
                            progressChannel.trySend(DownloadProgress.Progress(
                                id = id,
                                filename = filename,
                                downloaded = bytesRead,
                                total = contentLength,
                                percentage = percentage,
                                speed = speed
                            ))
                        }
                    }
                }
                
                // Close the channel and wait for all progress updates to be sent
                progressChannel.close()
                progressJob.join()
                
                send(DownloadProgress.Completed(
                    id = id,
                    filename = filename,
                    filePath = tempFile.absolutePath,
                    fileSize = tempFile.length()
                ))
                
            } catch (e: Exception) {
                progressChannel.close()
                progressJob.cancel()
                throw e
            }
            
        } catch (e: Exception) {
            Log.e(tag, "Download failed: ${e.message}", e)
            send(DownloadProgress.Error(
                id = System.currentTimeMillis().toString(),
                filename = filename,
                error = e.message ?: "Unknown error"
            ))
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Queue batch downloads
     */
    fun queueBatchDownloads(requests: List<BatchDownloadRequest>): Flow<BatchDownloadResult> = flow {
        for (request in requests) {
            try {
                downloadWithProgress(request.downloadUrl, request.filename).collect { progress ->
                    when (progress) {
                        is DownloadProgress.Completed -> {
                            emit(BatchDownloadResult.Success(
                                id = request.id,
                                filename = request.filename,
                                filePath = progress.filePath
                            ))
                        }
                        is DownloadProgress.Error -> {
                            emit(BatchDownloadResult.Error(
                                id = request.id,
                                filename = request.filename,
                                error = progress.error
                            ))
                        }
                        else -> {
                            // Ignore Started and Progress for batch results
                        }
                    }
                }
            } catch (e: Exception) {
                emit(BatchDownloadResult.Error(
                    id = request.id,
                    filename = request.filename,
                    error = e.message ?: "Download failed"
                ))
            }
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        // Clean up any resources if needed
        Log.i(tag, "Cleanup completed")
    }
}
