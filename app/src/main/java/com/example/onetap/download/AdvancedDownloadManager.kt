package com.example.onetap.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Advanced download manager for handling video downloads with progress tracking
 */
class AdvancedDownloadManager(private val context: Context) {
    
    private val tag = "AdvancedDownloadManager"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    
    /**
     * Download a file with progress tracking
     */
    fun downloadWithProgress(downloadUrl: String, filename: String): Flow<DownloadProgress> = flow {
        try {
            val id = System.currentTimeMillis().toString()
            emit(DownloadProgress.Started(id, filename))
            
            val request = Request.Builder()
                .url(downloadUrl)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                emit(DownloadProgress.Error(id, filename, "HTTP ${response.code}"))
                return@flow
            }
            
            val body = response.body ?: run {
                emit(DownloadProgress.Error(id, filename, "Empty response body"))
                return@flow
            }
            
            val contentLength = body.contentLength()
            val tempFile = File(context.cacheDir, filename)
            
            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    var lastProgressTime = System.currentTimeMillis()
                    var lastBytesRead = 0L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - lastProgressTime
                        
                        if (timeDiff >= 500) {
                            val bytesDiff = totalBytesRead - lastBytesRead
                            val speed = (bytesDiff.toDouble() / timeDiff) * 1000
                            val percentage = if (contentLength > 0) {
                                (totalBytesRead.toDouble() / contentLength) * 100
                            } else {
                                0.0
                            }
                            
                            emit(DownloadProgress.Progress(
                                id = id,
                                filename = filename,
                                downloaded = totalBytesRead,
                                total = contentLength,
                                percentage = percentage,
                                speed = speed
                            ))
                            
                            lastProgressTime = currentTime
                            lastBytesRead = totalBytesRead
                        }
                    }
                    
                    output.flush()
                }
            }
            
            emit(DownloadProgress.Completed(
                id = id,
                filename = filename,
                filePath = tempFile.absolutePath,
                fileSize = tempFile.length()
            ))
            
        } catch (e: Exception) {
            Log.e(tag, "Download failed: ${e.message}", e)
            emit(DownloadProgress.Error(
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
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        // Clean up any resources if needed
        Log.i(tag, "Cleanup completed")
    }
}
