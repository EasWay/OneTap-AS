package com.tapstream.downloader.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tapstream.downloader.di.DownloadHttpClient
import com.tapstream.downloader.utils.DownloadNotificationManager
import com.tapstream.downloader.utils.StreamUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * WorkManager-based download worker for reliable background downloads
 * 
 * Features:
 * - Survives app suspension and device reboot
 * - Automatic retry with exponential backoff
 * - Download resume capability
 * - Foreground service for long-running downloads
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @DownloadHttpClient private val client: OkHttpClient
) : CoroutineWorker(appContext, workerParams) {

    private val tag = "DownloadWorker"
    private val notificationManager = DownloadNotificationManager(applicationContext)

    companion object {
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_FILENAME = "filename"
        const val KEY_VIDEO_URL = "video_url"
        const val KEY_RETRY_COUNT = "retry_count"
        
        const val KEY_OUTPUT_FILE_PATH = "output_file_path"
        const val KEY_OUTPUT_FILE_SIZE = "output_file_size"
        const val KEY_OUTPUT_ERROR = "output_error"
        
        const val PROGRESS_CURRENT = "progress_current"
        const val PROGRESS_MAX = "progress_max"
        const val PROGRESS_PERCENTAGE = "progress_percentage"
    }

    override suspend fun doWork(): Result {
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL) ?: return Result.failure(
            workDataOf(KEY_OUTPUT_ERROR to "Missing download URL")
        )
        val filename = inputData.getString(KEY_FILENAME) ?: "video_${System.currentTimeMillis()}.mp4"
        val videoUrl = inputData.getString(KEY_VIDEO_URL) ?: ""

        Log.i(tag, "Starting download: $filename")
        
        return try {
            // Set foreground for long-running download
            setForeground(createForegroundInfo())
            
            val outputFile = downloadFile(downloadUrl, filename)
            
            Log.i(tag, "Download completed: ${outputFile.absolutePath}")
            notificationManager.showDownloadComplete()
            
            Result.success(workDataOf(
                KEY_OUTPUT_FILE_PATH to outputFile.absolutePath,
                KEY_OUTPUT_FILE_SIZE to outputFile.length()
            ))
            
        } catch (e: Exception) {
            Log.e(tag, "Download failed: ${e.message}", e)
            notificationManager.showDownloadError(e.message ?: "Unknown error")
            
            // Retry on failure
            if (runAttemptCount < 3) {
                Log.i(tag, "Retrying download (attempt ${runAttemptCount + 1})")
                Result.retry()
            } else {
                Result.failure(workDataOf(
                    KEY_OUTPUT_ERROR to (e.message ?: "Download failed after retries")
                ))
            }
        }
    }

    private suspend fun downloadFile(downloadUrl: String, filename: String): File {
        val tempFile = File(applicationContext.cacheDir, filename)
        val partFile = File(applicationContext.cacheDir, "$filename.part")
        
        // Check if partial download exists
        val startByte = if (partFile.exists()) partFile.length() else 0L
        
        val requestBuilder = Request.Builder().url(downloadUrl)
        
        // Add Range header for resume capability
        if (startByte > 0) {
            requestBuilder.addHeader("Range", "bytes=$startByte-")
            Log.i(tag, "Resuming download from byte $startByte")
        }
        
        val request = requestBuilder.build()
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful && response.code != 206) {
            throw Exception("HTTP ${response.code}: ${response.message}")
        }
        
        val body = response.body ?: throw Exception("Empty response body")
        val contentLength = body.contentLength()
        val totalLength = if (response.code == 206) startByte + contentLength else contentLength
        
        Log.i(tag, "Download size: $totalLength bytes (resuming from $startByte)")
        
        // Download with progress tracking
        body.byteStream().use { input ->
            FileOutputStream(partFile, startByte > 0).use { output ->
                StreamUtils.copyWithSpeed(input, output, totalLength, startByte) { bytesRead, percentage, speed ->
                    // Update progress
                    setProgressAsync(workDataOf(
                        PROGRESS_CURRENT to bytesRead,
                        PROGRESS_MAX to totalLength,
                        PROGRESS_PERCENTAGE to percentage.toInt()
                    ))
                    
                    notificationManager.updateProgress(percentage.toInt(), totalLength, bytesRead)
                }
            }
        }
        
        // Rename part file to final file
        if (partFile.renameTo(tempFile)) {
            Log.i(tag, "Download completed successfully")
            return tempFile
        } else {
            throw Exception("Failed to finalize download file")
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        notificationManager.showDownloadStarted()
        val notification = notificationManager.createProgressNotification(
            title = "Downloading video",
            text = "Download in progress...",
            progress = 0
        )
        
        return ForegroundInfo(2001, notification)
    }
}
