package com.example.onetap.repository

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.onetap.api.DownloadRequest
import com.example.onetap.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class VideoRepository {
    private val TAG = "OneTap_VideoRepo"
    
    // Configure a client with longer timeouts for video downloads
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // Allow 60s for reading large streams
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun downloadVideo(context: Context, videoUrl: String): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "=== OPTIMIZED DOWNLOAD START ===")
                Log.d(TAG, "Step 1: Contacting Backend for: $videoUrl")

                // 1. Get the Download Link
                Log.d(TAG, "Making optimized API call to backend...")
                val apiResponse = ApiClient.videoDownloadApi.downloadVideo(DownloadRequest(videoUrl))
                
                if (!apiResponse.isSuccessful || apiResponse.body()?.downloadUrl == null) {
                    val error = apiResponse.errorBody()?.string() ?: "Server Error ${apiResponse.code()}"
                    Log.e(TAG, "Backend Failed: $error")
                    return@withContext "Error: Backend failed. $error"
                }

                val targetUrl = apiResponse.body()!!.downloadUrl!!
                Log.i(TAG, "Step 2: Got Target URL: $targetUrl")

                // 2. Download the actual file bytes
                return@withContext downloadAndSaveToGallery(context, targetUrl)
                
            } catch (e: Exception) {
                Log.e(TAG, "Process failed", e)
                "Failed: ${e.message}"
            }
        }
    }

    private fun downloadAndSaveToGallery(context: Context, fileUrl: String): String {
        val request = Request.Builder()
            .url(fileUrl)
            .build()

        try {
            Log.i(TAG, "=== OPTIMIZED STREAM START ===")
            Log.d(TAG, "Step 3: Starting optimized stream from: $fileUrl")
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return "Failed to download file. Server replied: ${response.code}"
            }

            val body = response.body
            val inputStream = body?.byteStream()
            if (inputStream == null) return "Error: Server returned an empty file."

            val contentLength = body.contentLength()
            Log.d(TAG, "HTTP response received - Success: true, Code: ${response.code}")
            Log.d(TAG, "Content length: $contentLength bytes")

            val fileName = "OneTap_${System.currentTimeMillis()}.mp4"
            Log.d(TAG, "Step 4: Optimized save to Gallery as $fileName")

            // Save using MediaStore with optimized buffering
            return saveStreamToGallery(context, inputStream, fileName)
            
        } catch (e: Exception) {
            Log.e(TAG, "Stream error", e)
            return "Download error: ${e.message}"
        }
    }

    private fun saveStreamToGallery(context: Context, inputStream: InputStream, fileName: String): String {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        Log.d(TAG, "Creating optimized file entry in MediaStore...")
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: return "Failed to create file entry in Gallery"
        
        Log.d(TAG, "File entry created at URI: $uri")

        return try {
            Log.d(TAG, "Opening optimized output stream...")
            resolver.openOutputStream(uri)?.use { outputStream ->
                // Manual buffer copy for better control and less memory usage
                copyStreamOptimized(inputStream, outputStream)
            }
            Log.i(TAG, "Success: Optimized save completed at $uri")
            Log.i(TAG, "=== OPTIMIZED STREAM END ===")
            "Download Saved to Gallery!"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Log.e(TAG, "Save failed", e)
            "Save error: ${e.message}"
        }
    }

    // Custom copy function with 64KB buffer (better for mobile networks)
    private fun copyStreamOptimized(input: InputStream, output: OutputStream) {
        Log.d(TAG, "Starting optimized data transfer...")
        val bufferSize = 64 * 1024 // 64KB buffer
        val buffer = ByteArray(bufferSize)
        var bytesRead: Int
        var totalBytesRead: Long = 0
        
        Log.d(TAG, "Using optimized buffer size: 64KB")
        
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
        }
        
        output.flush()
        Log.d(TAG, "Optimized transfer completed: $totalBytesRead bytes")
    }
}