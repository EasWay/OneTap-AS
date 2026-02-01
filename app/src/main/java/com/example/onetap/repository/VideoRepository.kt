package com.example.onetap.repository

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.onetap.api.DownloadRequest
import com.example.onetap.api.DownloadResponse
import com.example.onetap.network.ApiClient
import com.example.onetap.network.ErrorMapper
import com.example.onetap.utils.DownloadHistoryManager
import com.example.onetap.utils.DownloadInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// Custom exception for unsupported content types
class UnsupportedContentException(message: String) : Exception(message)

class VideoRepository {
    private val tag = "OneTap_VideoRepo"
    
    // Configure a client with longer timeouts for video file downloads
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)    // Increased from 30s to 60s for Render free tier
        .readTimeout(300, TimeUnit.SECONDS)      // Increased from 2min to 5min for large video streams
        .writeTimeout(120, TimeUnit.SECONDS)     // Increased from 1min to 2min for writing
        .callTimeout(0, TimeUnit.SECONDS)        // No total timeout for large downloads
        .retryOnConnectionFailure(true)          // Enable retry for connection failures
        .build()

    suspend fun downloadVideo(context: Context, videoUrl: String, retryCount: Int = 2): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(tag, "Processing URL: $videoUrl")
                
                // Use server-side processing for all URLs
                Log.i(tag, "☁️ Using server-side processing")
                return@withContext handleServerSideDownload(context, videoUrl, retryCount)
                
            } catch (e: UnsupportedContentException) {
                // Don't log as error - this is expected for unsupported content
                Log.w(tag, "Unsupported content: ${e.message}")
                e.message ?: "Content not supported"
            } catch (e: Exception) {
                Log.e(tag, "Process failed", e)
                "Failed: ${e.message}"
            }
        }
    }
    
    /**
     * Handle non-YouTube downloads using server-side processing
     */
    private suspend fun handleServerSideDownload(context: Context, videoUrl: String, retryCount: Int): String {
        Log.i(tag, "☁️ Mode: Server-Side Processing")

        // Get the response from server processing
        val response = getServerProcessedResponse(videoUrl, retryCount)
        Log.d(tag, "Got response from server: $response")
        Log.d(tag, "Response type: ${response.type}")
        Log.d(tag, "Response files: ${response.files}")
        Log.d(tag, "Files count: ${response.files?.size}")
        
        // Check if this is a multi-image download
        if (response.type == "multi_image" && !response.files.isNullOrEmpty()) {
            Log.i(tag, "🎯 Detected multi-image response with ${response.files.size} files")
            return handleMultipleDownloads(context, response, videoUrl)
        }
        
        // Single file download - use existing logic
        val filename = response.filename ?: throw kotlin.Exception("No filename returned from server")
        
        // Check for duplicates using existing logic
        val downloadHistoryManager = DownloadHistoryManager(context)
        
        // Multi-layer duplicate detection:
        // 1. PRIORITY: Check by video ID extraction from filename (most reliable for same video, different URLs)
        val videoId = downloadHistoryManager.extractVideoIdFromFilename(filename)
        if (videoId != null && downloadHistoryManager.prefs.contains("video_$videoId")) {
            val downloadedAt = downloadHistoryManager.prefs.getLong("video_${videoId}_time", 0L)
            val timeSince = if (downloadedAt > 0) downloadHistoryManager.getTimeSinceDownload(downloadedAt) else "recently"
            Log.i(tag, "🔄 Duplicate detected by video ID: $videoId (downloaded $timeSince)")
            return "DUPLICATE_DETECTED:$timeSince"
        }
        
        // 2. Check by filename prefix (for non-TikTok or when video ID extraction fails)
        if (downloadHistoryManager.isAlreadyDownloadedByFilename(filename)) {
            val downloadInfo = downloadHistoryManager.getDownloadInfoByFilename(filename)
            val timeSince = downloadInfo?.let { info: DownloadInfo -> downloadHistoryManager.getTimeSinceDownload(info.downloadedAt) } ?: "recently"
            Log.i(tag, "🔄 Duplicate detected by filename: $filename (downloaded $timeSince)")
            return "DUPLICATE_DETECTED:$timeSince"
        }
        
        // 3. FALLBACK: Check by URL hash (only for exact same URL)
        if (downloadHistoryManager.isAlreadyDownloadedByUrlHash(videoUrl)) {
            val urlHash = videoUrl.hashCode().toString()
            val downloadedAt = downloadHistoryManager.prefs.getLong("url_hash_${urlHash}_time", 0L)
            val timeSince = if (downloadedAt > 0) downloadHistoryManager.getTimeSinceDownload(downloadedAt) else "recently"
            Log.i(tag, "🔄 Duplicate detected by URL hash: $urlHash (downloaded $timeSince)")
            return "DUPLICATE_DETECTED:$timeSince"
        }

        // Use the download URL from server response (could be /files/ or /stream/)
        val serverFileUrl = if (!response.downloadUrl.isNullOrEmpty()) {
            // Server provided a download URL (e.g., /stream/xyz), prepend base URL
            "${ApiClient.BASE_URL.trimEnd('/')}${response.downloadUrl}"
        } else {
            // Fallback to old /files/ endpoint for backward compatibility
            "${ApiClient.BASE_URL}files/$filename"
        }
        
        Log.d(tag, "Using server download URL: $serverFileUrl")
        Log.d(tag, "🌐 Server host: ${java.net.URL(serverFileUrl).host}")
        Log.d(tag, "🔌 Server port: ${java.net.URL(serverFileUrl).port}")
        Log.d(tag, "📡 Server path: ${java.net.URL(serverFileUrl).path}")
        
        // Detect file type based on filename extension
        val isImage = filename.lowercase().let { name ->
            name.endsWith(".jpg") || name.endsWith(".jpeg") || 
            name.endsWith(".png") || name.endsWith(".webp") || 
            name.endsWith(".gif")
        }
        
        val result = downloadAndSaveToGallery(context, serverFileUrl, isImage)
        
        // If download was successful, mark it as downloaded by filename
        if (result.contains("Success") || result.contains("Saved")) {
            downloadHistoryManager.markAsDownloadedByFilename(filename, videoUrl)
        }
        
        return result
    }

    private suspend fun handleMultipleDownloads(context: Context, response: DownloadResponse, videoUrl: String): String {
        val files = response.files ?: return "No files to download"
        val downloadHistoryManager = DownloadHistoryManager(context)
        
        Log.i(tag, "📸 Multi-image download: ${files.size} files")
        Log.d(tag, "Files to download: ${files.map { it.filename }}")
        
        var successCount = 0
        var failCount = 0
        
        for ((index, file) in files.withIndex()) {
            try {
                Log.d(tag, "Downloading file ${index + 1}/${files.size}: ${file.filename}")
                Log.d(tag, "File type: ${file.type}, Download URL: ${file.downloadUrl}")
                
                // Check for duplicates for each file
                if (downloadHistoryManager.isAlreadyDownloadedByFilename(file.filename)) {
                    Log.i(tag, "🔄 File ${file.filename} already downloaded, skipping")
                    successCount++
                    continue
                }
                
                // Use the download URL from server response (could be relative or absolute)
                val serverFileUrl = if (file.downloadUrl.startsWith("http")) {
                    // Absolute URL
                    file.downloadUrl
                } else {
                    // Relative URL, prepend base URL
                    "${ApiClient.BASE_URL.trimEnd('/')}${file.downloadUrl}"
                }
                
                Log.d(tag, "Constructed server URL: $serverFileUrl")
                val result = downloadAndSaveToGallery(context, serverFileUrl, isImage = true)
                
                if (result.contains("Success") || result.contains("Saved")) {
                    downloadHistoryManager.markAsDownloadedByFilename(file.filename, videoUrl)
                    successCount++
                    Log.i(tag, "✅ Downloaded ${index + 1}/${files.size}: ${file.filename}")
                } else {
                    failCount++
                    Log.w(tag, "❌ Failed ${index + 1}/${files.size}: ${file.filename} - $result")
                }
                
            } catch (e: Exception) {
                failCount++
                Log.e(tag, "❌ Error downloading ${file.filename}: ${e.message}")
            }
        }
        
        return when {
            successCount == files.size -> "✅ Downloaded all $successCount images from ${response.platform ?: "photo"} slideshow"
            successCount > 0 -> "⚠️ Downloaded $successCount of ${files.size} images ($failCount failed)"
            else -> "❌ Failed to download any images from slideshow"
        }
    }

    /**
     * Process video URL on server and get the response
     */
    private suspend fun getServerProcessedResponse(videoUrl: String, retryCount: Int): DownloadResponse {
        var lastException: Exception? = null
        
        // Retry the API call
        repeat(retryCount) { attempt ->
            try {
                Log.d(tag, "Making API call to backend (attempt ${attempt + 1}/$retryCount)...")
                val apiResponse = ApiClient.videoDownloadApi.downloadVideo(DownloadRequest(videoUrl))
                
                if (!apiResponse.isSuccessful) {
                    Log.e(tag, "Backend Failed (attempt ${attempt + 1}/$retryCount): HTTP ${apiResponse.code()}")
                    
                    // Use ErrorMapper to handle server errors
                    val httpException = HttpException(apiResponse)
                    val errorMessage = ErrorMapper.mapServerError(httpException)
                    
                    // Check if this is an unsupported content type (don't retry these)
                    if (errorMessage.contains("not supported", ignoreCase = true) || 
                        errorMessage.contains("photo", ignoreCase = true) ||
                        errorMessage.contains("live", ignoreCase = true) ||
                        errorMessage.contains("story", ignoreCase = true) ||
                        errorMessage.contains("private", ignoreCase = true)) {
                        
                        throw UnsupportedContentException(errorMessage)
                    }
                    
                    // If it's a server error or timeout, retry
                    if (apiResponse.code() in 500..599 || apiResponse.code() == 408) {
                        lastException = kotlin.Exception(errorMessage)
                        if (attempt < retryCount - 1) {
                            Log.i(tag, "🔄 Server error, retrying in 5 seconds...")
                            kotlinx.coroutines.delay(5000)
                            return@repeat
                        }
                    }
                    
                    throw kotlin.Exception(errorMessage)
                }

                val responseBody = apiResponse.body()
                if (responseBody == null) {
                    throw kotlin.Exception("No response body from server")
                }
                
                // Handle error in response body
                if (!responseBody.error.isNullOrEmpty()) {
                    val errorMsg = responseBody.error
                    
                    // Check for unsupported content type errors in response body
                    if (errorMsg.contains("not supported", ignoreCase = true) || 
                        errorMsg.contains("unsupported", ignoreCase = true)) {
                        
                        // Map the error message using ErrorMapper
                        val mappedError = when {
                            errorMsg.contains("photo", ignoreCase = true) -> 
                                "📸 Photo posts are not supported. Only video posts can be downloaded."
                            errorMsg.contains("live", ignoreCase = true) -> 
                                "🔴 Live streams are not supported. Only recorded videos can be downloaded."
                            errorMsg.contains("story", ignoreCase = true) || errorMsg.contains("stories", ignoreCase = true) -> 
                                "📱 Stories are not supported. Only permanent posts can be downloaded."
                            errorMsg.contains("private", ignoreCase = true) -> 
                                "🔒 Private content is not supported. Only public videos can be downloaded."
                            else -> 
                                "❌ This content type is not supported. Only videos can be downloaded."
                        }
                        
                        throw UnsupportedContentException(mappedError)
                    }
                    
                    throw kotlin.Exception("Server processing failed: $errorMsg")
                }

                // Check if we have either a single file or multiple files
                if (responseBody.filename == null && responseBody.files.isNullOrEmpty()) {
                    throw kotlin.Exception("No filename or files returned from server")
                }

                Log.i(tag, "✅ Server processing successful")
                if (responseBody.type == "multi_image") {
                    Log.d(tag, "📸 Multi-image response: ${responseBody.files?.size} files")
                } else {
                    Log.d(tag, "📁 Single file: ${responseBody.filename}")
                    Log.d(tag, "🎥 Video codec: ${responseBody.videoCodec}")
                    Log.d(tag, "🔊 Audio codec: ${responseBody.audioCodec}")
                    Log.d(tag, "📦 Container: ${responseBody.container}")
                }

                return responseBody
                
            } catch (e: UnsupportedContentException) {
                // Don't retry for unsupported content types - throw immediately
                Log.w(tag, "Unsupported content type: ${e.message}")
                throw e
                
            } catch (e: HttpException) {
                lastException = e
                Log.e(tag, "HTTP Exception (attempt ${attempt + 1}/$retryCount): ${e.code()} - ${e.message()}")
                
                // Use ErrorMapper to get user-friendly message
                val errorMessage = ErrorMapper.mapServerError(e)
                
                // Check if this is an unsupported content type
                if (errorMessage.contains("not supported", ignoreCase = true) || 
                    errorMessage.contains("photo", ignoreCase = true) ||
                    errorMessage.contains("live", ignoreCase = true) ||
                    errorMessage.contains("story", ignoreCase = true) ||
                    errorMessage.contains("private", ignoreCase = true)) {
                    
                    throw UnsupportedContentException(errorMessage)
                }
                
                if (e.code() in 500..599 || e.code() == 408) {
                    // Server error or timeout, retry
                    if (attempt < retryCount - 1) {
                        Log.i(tag, "🔄 HTTP error ${e.code()}, retrying in 5 seconds...")
                        kotlinx.coroutines.delay(5000)
                        return@repeat
                    }
                }
                
                throw kotlin.Exception(errorMessage)
                
            } catch (e: SocketTimeoutException) {
                lastException = e
                Log.e(tag, "⏰ TIMEOUT (attempt ${attempt + 1}/$retryCount): ${e.message}")
                Log.e(tag, "💡 This is normal for Render free tier - server needs time to wake up")
                
                if (attempt < retryCount - 1) {
                    Log.i(tag, "🔄 Timeout, retrying in 8 seconds...")
                    kotlinx.coroutines.delay(8000) // Longer delay for timeouts
                    return@repeat
                }
                
                throw kotlin.Exception(ErrorMapper.mapNetworkError(e))
                
            } catch (e: ConnectException) {
                lastException = e
                Log.e(tag, "🔌 Connection failed (attempt ${attempt + 1}/$retryCount): ${e.message}")
                
                if (attempt < retryCount - 1) {
                    Log.i(tag, "🔄 Connection failed, retrying in 5 seconds...")
                    kotlinx.coroutines.delay(5000)
                    return@repeat
                }
                
                throw kotlin.Exception(ErrorMapper.mapNetworkError(e))
                
            } catch (e: UnknownHostException) {
                lastException = e
                Log.e(tag, "🌐 DNS resolution failed (attempt ${attempt + 1}/$retryCount): ${e.message}")
                
                if (attempt < retryCount - 1) {
                    Log.i(tag, "🔄 DNS error, retrying in 3 seconds...")
                    kotlinx.coroutines.delay(3000)
                    return@repeat
                }
                
                throw kotlin.Exception(ErrorMapper.mapNetworkError(e))
                
            } catch (e: Exception) {
                // Check if it's an UnsupportedContentException wrapped in another exception
                if (e is UnsupportedContentException) {
                    Log.w(tag, "Unsupported content type: ${e.message}")
                    throw e
                }
                
                lastException = e
                Log.e(tag, "Exception (attempt ${attempt + 1}/$retryCount): ${e.javaClass.simpleName}")
                Log.e(tag, "Exception message: ${e.message}")
                
                if (attempt < retryCount - 1) {
                    Log.i(tag, "🔄 Exception occurred, retrying in 3 seconds...")
                    kotlinx.coroutines.delay(3000)
                    return@repeat
                } else {
                    Log.e(tag, "Exception details: ", e)
                    throw e
                }
            }
        }
        
        // If we get here, all attempts failed
        // Use ErrorMapper for the final error message
        val finalException = lastException
        when (finalException) {
            is UnsupportedContentException -> throw finalException
            is HttpException -> {
                val finalErrorMessage = ErrorMapper.mapServerError(finalException)
                throw kotlin.Exception(finalErrorMessage)
            }
            is SocketTimeoutException, 
            is ConnectException, 
            is UnknownHostException -> {
                val finalErrorMessage = ErrorMapper.mapNetworkError(finalException)
                throw kotlin.Exception(finalErrorMessage)
            }
            else -> {
                val finalErrorMessage = finalException?.message ?: "Unknown error after $retryCount attempts"
                throw kotlin.Exception(finalErrorMessage)
            }
        }
    }

    private fun downloadAndSaveToGallery(context: Context, fileUrl: String, isImage: Boolean = false): String {
        val request = Request.Builder()
            .url(fileUrl)
            .header("User-Agent", "OneTap-VideoDownloader/1.0")
            .header("Accept", "*/*")
            .build()

        try {
            Log.i(tag, "=== SERVER-SIDE DOWNLOAD START ===")
            Log.d(tag, "Downloading processed file from server: $fileUrl")
            Log.d(tag, "⏱️ File download timeouts - Connect: 30s, Read: 2min, Write: 1min")
            
            val response = downloadClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(tag, "File download failed with HTTP ${response.code}: ${response.message}")
                return "Failed to download file. Server replied: ${response.code} - ${response.message}"
            }

            val body = response.body
            val inputStream = body?.byteStream()
            if (inputStream == null) {
                Log.e(tag, "Server returned empty response body")
                return "Error: Server returned an empty file."
            }

            val contentLength = body.contentLength()
            Log.d(tag, "HTTP response received - Success: true, Code: ${response.code}")
            Log.d(tag, "Content length: ${if (contentLength > 0) "${contentLength / 1024}KB" else "Unknown"}")
            Log.d(tag, "Content type: ${response.header("Content-Type", "unknown")}")

            // Determine file extension and MIME type based on content type or URL
            val contentType = response.header("Content-Type", "")?.lowercase() ?: ""
            Log.d(tag, "Server Content-Type header: '$contentType'")
            Log.d(tag, "File URL: $fileUrl")
            
            val (fileName, mimeType, isAudio) = when {
                // Audio files
                fileUrl.contains(".mp3", ignoreCase = true) || contentType.contains("audio/mpeg") -> {
                    Log.d(tag, "Detected as MP3 audio file")
                    Triple("OneTap_${System.currentTimeMillis()}.mp3", "audio/mpeg", true)
                }
                fileUrl.contains(".m4a", ignoreCase = true) || contentType.contains("audio/mp4") -> {
                    Log.d(tag, "Detected as M4A audio file")
                    Triple("OneTap_${System.currentTimeMillis()}.m4a", "audio/mp4", true)
                }
                fileUrl.contains(".wav", ignoreCase = true) || contentType.contains("audio/wav") -> {
                    Log.d(tag, "Detected as WAV audio file")
                    Triple("OneTap_${System.currentTimeMillis()}.wav", "audio/wav", true)
                }
                fileUrl.contains(".flac", ignoreCase = true) || contentType.contains("audio/flac") -> {
                    Log.d(tag, "Detected as FLAC audio file")
                    Triple("OneTap_${System.currentTimeMillis()}.flac", "audio/flac", true)
                }
                fileUrl.contains(".aac", ignoreCase = true) || contentType.contains("audio/aac") -> {
                    Log.d(tag, "Detected as AAC audio file")
                    Triple("OneTap_${System.currentTimeMillis()}.aac", "audio/aac", true)
                }
                // Image files
                isImage -> {
                    val extension = when {
                        fileUrl.contains(".jpg", ignoreCase = true) || fileUrl.contains(".jpeg", ignoreCase = true) || contentType.contains("image/jpeg") -> "jpg"
                        fileUrl.contains(".png", ignoreCase = true) || contentType.contains("image/png") -> "png"
                        fileUrl.contains(".webp", ignoreCase = true) || contentType.contains("image/webp") -> "webp"
                        fileUrl.contains(".gif", ignoreCase = true) || contentType.contains("image/gif") -> "gif"
                        else -> "jpg" // Default to jpg for images
                    }
                    Log.d(tag, "Detected as image file: $extension")
                    Triple("OneTap_${System.currentTimeMillis()}.$extension", "image/$extension", false)
                }
                // Video files (default)
                else -> {
                    Log.d(tag, "Detected as video file (default)")
                    Triple("OneTap_${System.currentTimeMillis()}.mp4", "video/mp4", false)
                }
            }
            
            Log.d(tag, "Final file details - Name: $fileName, MIME: $mimeType, IsAudio: $isAudio")

            // Save using MediaStore with optimized buffering
            return saveStreamToGallery(context, inputStream, fileName, contentLength, mimeType, isImage, isAudio)
            
        } catch (e: SocketTimeoutException) {
            Log.e(tag, "⏰ File download timeout: ${e.message}")
            Log.e(tag, "💡 Network issue - check your connection")
            return ErrorMapper.mapNetworkError(e)
        } catch (e: ConnectException) {
            Log.e(tag, "🔌 Connection failed: ${e.message}")
            return ErrorMapper.mapNetworkError(e)
        } catch (e: UnknownHostException) {
            Log.e(tag, "🌐 DNS resolution failed: ${e.message}")
            return ErrorMapper.mapNetworkError(e)
        } catch (e: java.io.IOException) {
            Log.e(tag, "📡 Network I/O error: ${e.message}")
            return ErrorMapper.mapNetworkError(e)
        } catch (e: Exception) {
            Log.e(tag, "Stream error: ${e.javaClass.simpleName}", e)
            return "Download error: ${e.message}"
        }
    }

    private fun saveStreamToGallery(context: Context, inputStream: InputStream, fileName: String, contentLength: Long, mimeType: String, isImage: Boolean, isAudio: Boolean): String {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, when {
                    isImage -> Environment.DIRECTORY_PICTURES
                    isAudio -> Environment.DIRECTORY_MUSIC
                    else -> Environment.DIRECTORY_DOWNLOADS
                })
            }
        }

        Log.d(tag, "Creating file entry in MediaStore...")
        
        // Use appropriate URI based on API level and content type
        val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            when {
                isImage -> resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                isAudio -> resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                else -> resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            }
        } else {
            // For API < 29, save to appropriate directory
            when {
                isImage -> {
                    contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                }
                isAudio -> {
                    contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
                    resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                }
                else -> {
                    contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                    resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                }
            }
        }
        
        if (uri == null) {
            return "Failed to create file entry in Gallery"
        }
        
        Log.d(tag, "File entry created at URI: $uri")

        return try {
            Log.d(tag, "Opening output stream...")
            resolver.openOutputStream(uri)?.use { outputStream ->
                // Manual buffer copy for better control and progress tracking
                copyStreamOptimized(inputStream, outputStream, contentLength)
            }
            Log.i(tag, "Success: Server-side download completed at $uri")
            Log.i(tag, "=== SERVER-SIDE DOWNLOAD END ===")
            "Download Saved to Gallery!"
        } catch (e: SocketTimeoutException) {
            resolver.delete(uri, null, null)
            Log.e(tag, "⏰ Network timeout during download: ${e.message}", e)
            ErrorMapper.mapNetworkError(e)
        } catch (e: UnknownHostException) {
            resolver.delete(uri, null, null)
            Log.e(tag, "🌐 Network error during download: ${e.message}", e)
            ErrorMapper.mapNetworkError(e)
        } catch (e: java.io.IOException) {
            resolver.delete(uri, null, null)
            Log.e(tag, "IO error during save: ${e.message}", e)
            // Check if it's a network-related IOException
            val errorMsg = e.message?.lowercase() ?: ""
            when {
                errorMsg.contains("connection") || errorMsg.contains("network") || 
                errorMsg.contains("socket") || errorMsg.contains("timeout") || 
                errorMsg.contains("reset") || errorMsg.contains("broken pipe") ->
                    ErrorMapper.mapNetworkError(e)
                errorMsg.contains("space") || errorMsg.contains("storage") ->
                    "💾 Not enough storage space on device. Please free up some space and try again."
                else -> "💾 Storage error: Unable to save file - ${e.message}"
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Log.e(tag, "Save failed: ${e.javaClass.simpleName}", e)
            "💾 Save error: ${e.message}"
        }
    }

    // Custom copy function with 64KB buffer and progress tracking
    private fun copyStreamOptimized(input: InputStream, output: OutputStream, contentLength: Long) {
        Log.d(tag, "Starting optimized data transfer...")
        val bufferSize = 64 * 1024 // 64KB buffer
        val buffer = ByteArray(bufferSize)
        var bytesRead: Int
        var totalBytesRead: Long = 0
        var lastProgressLog = System.currentTimeMillis()
        
        Log.d(tag, "Using optimized buffer size: 64KB")
        if (contentLength > 0) {
            Log.d(tag, "Expected file size: ${contentLength / 1024}KB")
        }
        
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
            
            // Log progress every 2 seconds to avoid spam
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastProgressLog > 2000) {
                if (contentLength > 0) {
                    val progress = (totalBytesRead * 100) / contentLength
                    Log.d(tag, "📈 Transfer progress: $progress% (${totalBytesRead / 1024}KB/${contentLength / 1024}KB)")
                } else {
                    Log.d(tag, "📈 Transferred: ${totalBytesRead / 1024}KB")
                }
                lastProgressLog = currentTime
            }
        }
        
        output.flush()
        Log.d(tag, "Transfer completed: ${totalBytesRead / 1024}KB total")
        
        if (contentLength > 0 && totalBytesRead != contentLength) {
            Log.w(tag, "⚠️ Size mismatch: Expected ${contentLength}B, got ${totalBytesRead}B")
        }
    }

    suspend fun getSystemVersion(): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(tag, "Fetching system version from server...")
                val apiResponse = ApiClient.videoDownloadApi.getSystemInfo()
                
                if (apiResponse.isSuccessful) {
                    val versionInfo = apiResponse.body()
                    if (versionInfo != null) {
                        Log.i(tag, "✅ System version: ${versionInfo.version}")
                        Log.i(tag, "📊 Total platforms: ${versionInfo.totalPlatforms}")
                        return@withContext versionInfo.version
                    }
                }
                
                Log.w(tag, "Failed to get version info: HTTP ${apiResponse.code()}")
                return@withContext "Unknown"
                
            } catch (e: Exception) {
                Log.e(tag, "Error fetching version: ${e.message}")
                return@withContext "Unknown"
            }
        }
    }

    suspend fun getSystemInfo(): com.example.onetap.api.VersionResponse? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(tag, "Fetching system info from server...")
                val apiResponse = ApiClient.videoDownloadApi.getSystemInfo()
                
                if (apiResponse.isSuccessful) {
                    val versionInfo = apiResponse.body()
                    if (versionInfo != null) {
                        Log.i(tag, "✅ System info retrieved successfully")
                        Log.i(tag, "📊 Version: ${versionInfo.version}")
                        Log.i(tag, "🌐 Total platforms: ${versionInfo.totalPlatforms}")
                        return@withContext versionInfo
                    }
                }
                
                Log.w(tag, "Failed to get system info: HTTP ${apiResponse.code()}")
                return@withContext null
                
            } catch (e: Exception) {
                Log.e(tag, "Error fetching system info: ${e.message}")
                return@withContext null
            }
        }
    }
}
