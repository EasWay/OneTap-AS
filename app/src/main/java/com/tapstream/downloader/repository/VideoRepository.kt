package com.tapstream.downloader.repository

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.tapstream.downloader.api.DownloadRequest
import com.tapstream.downloader.api.DownloadResponse
import com.tapstream.downloader.api.VideoDownloadApi
import com.tapstream.downloader.di.DownloadHttpClient
import com.tapstream.downloader.download.AdvancedDownloadManager
import com.tapstream.downloader.download.BatchDownloadRequest
import com.tapstream.downloader.download.BatchDownloadResult
import com.tapstream.downloader.download.DownloadProgress
import com.tapstream.downloader.network.ApiClient
import com.tapstream.downloader.network.ErrorMapper
import com.tapstream.downloader.utils.DownloadHistoryManager
import com.tapstream.downloader.utils.DownloadInfo
import com.tapstream.downloader.utils.SonnerToast
import com.tapstream.downloader.utils.StreamUtils
import com.tapstream.downloader.network.TurnstileBypassProvider
import com.tapstream.downloader.network.J2Session
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.emitAll
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import retrofit2.HttpException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

// Custom exception for unsupported content types
class UnsupportedContentException(message: String) : Exception(message)

/**
 * VideoRepository with Dependency Injection
 * 
 * Now uses injected OkHttpClient from NetworkModule for:
 * - Shared connection pool across app
 * - TCP Keep-Alive benefits
 * - Reduced memory footprint
 * - Faster subsequent requests
 */
@Singleton
class VideoRepository @Inject constructor(
    @DownloadHttpClient private val downloadClient: OkHttpClient,
    private val videoDownloadApi: VideoDownloadApi,
    @Named("youtube") private val youtubeDownloadApi: VideoDownloadApi,
    private val advancedDownloadManager: AdvancedDownloadManager,
    private val turnstileBypassProvider: TurnstileBypassProvider,
    @ApplicationContext private val appContext: Context
) {
    private val tag = "OneTap_VideoRepo"

    /**
     * Ultra-fast batch download for multiple videos
     */
    suspend fun downloadMultipleVideos(
        context: Context, 
        videoUrls: List<String>,
        progressCallback: ((String, DownloadProgress) -> Unit)? = null
    ): Flow<BatchDownloadResult> = flow {
        Log.i(tag, "🎯 Starting ultra-fast batch download of ${videoUrls.size} videos")
        
        // Process URLs and get download info
        val batchRequests = mutableListOf<BatchDownloadRequest>()
        
        for ((index, videoUrl) in videoUrls.withIndex()) {
            try {
                Log.d(tag, "📋 Processing URL ${index + 1}/${videoUrls.size}: $videoUrl")
                
                val platform = detectPlatform(videoUrl)
                val downloadId = UUID.randomUUID().toString()
                
                // Get download info from server for all platforms
                val response = getServerProcessedResponse(videoUrl, 2)
                
                // Generate appropriate filename based on platform and response
                val filename = if (isMusicPlatform(platform)) {
                    generateMusicFilename(response.getActualTitle(), response.getActualFilename(), platform)
                } else {
                    response.getActualFilename() ?: "video_${index + 1}.mp4"
                }
                
                // Check for duplicates - prioritize URL check
                val downloadHistoryManager = DownloadHistoryManager(context)
                
                // Check by URL first (most reliable)
                val shouldSkip = if (downloadHistoryManager.isAlreadyDownloadedByUrlHash(videoUrl)) {
                    true
                } else {
                    // Only check by filename for non-generic names
                    val isGenericFilename = filename.lowercase().let { name ->
                        name.contains("facebook reel") || name.contains("tiktok") || 
                        name == "video.mp4" || name == "instagram.mp4" || 
                        name.startsWith("media_") || name.startsWith("- ")
                    }
                    !isGenericFilename && downloadHistoryManager.isAlreadyDownloadedByFilename(filename)
                }
                
                if (!shouldSkip) {
                    // Construct download URL - ensure HTTPS
                    val downloadUrl = if (response.getActualDownloadUrl()?.startsWith("http") == true) {
                        val rawUrl = response.getActualDownloadUrl()!!
                        if (rawUrl.startsWith("http://")) {
                            rawUrl.replace("http://", "https://")
                        } else {
                            rawUrl
                        }
                    } else {
                        val platform = detectPlatform(videoUrl)
                        val baseUrl = getBaseUrlForPlatform(platform)
                        "${baseUrl.trimEnd('/')}${response.getActualDownloadUrl()}"
                    }
                    
                    batchRequests.add(BatchDownloadRequest(downloadId, downloadUrl, filename))
                    Log.i(tag, "✅ Queued: $filename")
                } else {
                    Log.i(tag, "⏭️ Skipped duplicate: $filename")
                    emit(BatchDownloadResult.Success(UUID.randomUUID().toString(), filename, "Already downloaded"))
                }
                
            } catch (e: Exception) {
                Log.e(tag, "❌ Failed to process URL ${index + 1}: ${e.message}")
                emit(BatchDownloadResult.Error(UUID.randomUUID().toString(), "video_${index + 1}", e.message ?: "Processing failed"))
            }
        }
        
        if (batchRequests.isNotEmpty()) {
            Log.i(tag, "🚀 Starting concurrent download of ${batchRequests.size} videos")
            
            // Use advanced download manager for ultra-fast concurrent downloads
            advancedDownloadManager.queueBatchDownloads(batchRequests).collect { result ->
                when (result) {
                    is BatchDownloadResult.Success -> {
                        // Save to gallery and mark as downloaded
                        try {
                            saveToGallery(context, result.filePath, false)
                            
                            // Mark as downloaded in history
                            val downloadHistoryManager = DownloadHistoryManager(context)
                            downloadHistoryManager.markAsDownloadedByFilename(result.filename, "batch_download")
                            
                            Log.i(tag, "✅ Completed: ${result.filename}")
                        } catch (e: Exception) {
                            Log.e(tag, "❌ Failed to save ${result.filename}: ${e.message}")
                        }
                    }
                    is BatchDownloadResult.Error -> {
                        Log.e(tag, "❌ Download failed: ${result.filename} - ${result.error}")
                    }
                }
                emit(result)
            }
        }
    }

    /**
     * Enhanced single video download with progress tracking
     */
    suspend fun downloadVideoWithProgress(
        context: Context, 
        videoUrl: String,
        progressCallback: ((DownloadProgress) -> Unit)? = null
    ): Flow<DownloadProgress> = flow {
        try {
            Log.i(tag, "🎯 Starting enhanced download with progress tracking")
            
            // Get download info from server for all platforms
            val response = getServerProcessedResponse(videoUrl, 2)
            
            Log.d(tag, "📊 Server response details:")
            Log.d(tag, "   - Status: ${response.status}")
            Log.d(tag, "   - Platform: ${response.platform}")
            Log.d(tag, "   - Filename: ${response.getActualFilename()}")
            Log.d(tag, "   - Download URL: ${response.getActualDownloadUrl()?.take(100)}...")
            Log.d(tag, "   - Title: ${response.getActualTitle()}")
            Log.d(tag, "   - Type: ${response.type}")
            
            // The backend now handles J2 extraction robustly.
            // We just need to ensure the cookies/JWT are passed via getServerProcessedResponse
            // which is already handled above in line 183 (via the provider).
            
            // Check if this is a multi-image or multi-video download
            if ((response.type == "multi_image" || response.type == "multi_video") && !response.files.isNullOrEmpty()) {
                Log.i(tag, "📸 Multi-${response.type} response: ${response.files.size} files")
                val result = handleMultipleDownloads(context, response, videoUrl)
                if (result.startsWith("❌")) {
                    emit(DownloadProgress.Error(UUID.randomUUID().toString(), response.type ?: "multi_media", result))
                } else {
                    emit(DownloadProgress.Completed(UUID.randomUUID().toString(), response.type ?: "multi_media", result, 0L))
                }
                return@flow
            }
            
            // Detect platform from URL
            val platform = detectPlatform(videoUrl)
            
            // Generate appropriate filename based on platform and response
            val rawFilename = if (isMusicPlatform(platform)) {
                generateMusicFilename(response.getActualTitle(), response.getActualFilename(), platform)
            } else {
                response.getActualFilename() ?: "video.mp4"
            }
            
            // Sanitize filename for all platforms
            val filename = sanitizeFilename(rawFilename)
            
            Log.i(tag, "🎵 Platform: $platform, Generated filename: $filename")
            
            // Check for duplicates - prioritize URL check over filename
            val downloadHistoryManager = DownloadHistoryManager(context)
            
            // First check by URL (most reliable for unique content)
            if (downloadHistoryManager.isAlreadyDownloadedByUrlHash(videoUrl)) {
                val downloadInfo = downloadHistoryManager.getDownloadInfoByFilename(filename)
                val timeSince = downloadInfo?.let { downloadHistoryManager.getTimeSinceDownload(it.downloadedAt) } ?: "recently"
                emit(DownloadProgress.Error(UUID.randomUUID().toString(), filename, "Already downloaded $timeSince"))
                return@flow
            }
            
            // Only check by filename for non-generic names
            val isGenericFilename = filename.lowercase().let { name ->
                name.contains("facebook reel") || name.contains("tiktok") || 
                name == "video.mp4" || name == "instagram.mp4" || 
                name.startsWith("media_") || name.startsWith("- ")
            }
            
            if (!isGenericFilename && downloadHistoryManager.isAlreadyDownloadedByFilename(filename)) {
                val downloadInfo = downloadHistoryManager.getDownloadInfoByFilename(filename)
                val timeSince = downloadInfo?.let { downloadHistoryManager.getTimeSinceDownload(it.downloadedAt) } ?: "recently"
                emit(DownloadProgress.Error(UUID.randomUUID().toString(), filename, "Already downloaded $timeSince"))
                return@flow
            }
            
            // Retry loop for transient HTTP 5xx stream errors
            var currentResponse = response
            var streamAttempt = 0
            while (true) {
                val isDirectUrl = currentResponse.getActualDownloadUrl()?.startsWith("http") == true

                Log.d(tag, "🔍 Download URL analysis:")
                Log.d(tag, "   - Raw URL: ${currentResponse.getActualDownloadUrl()}")
                Log.d(tag, "   - Is Direct URL: $isDirectUrl")
                Log.d(tag, "   - Platform: ${currentResponse.platform}")

                val downloadUrl = if (isDirectUrl) {
                    val rawUrl = currentResponse.getActualDownloadUrl()!!
                    val secureUrl = if (rawUrl.startsWith("http://")) rawUrl.replace("http://", "https://") else rawUrl
                    Log.i(tag, "📱 Direct URL detected for $platform")
                    Log.d(tag, "   - Full URL: $secureUrl")
                    Log.d(tag, "   - URL length: ${secureUrl.length}")
                    Log.d(tag, "   - URL host: ${java.net.URL(secureUrl).host}")
                    secureUrl
                } else {
                    val baseUrl = getBaseUrlForPlatform(platform)
                    val fullUrl = "${baseUrl.trimEnd('/')}${currentResponse.getActualDownloadUrl()}"
                    Log.i(tag, "📱 Using ${if (platform == "youtube") "YouTube" else "main"} server stream proxy")
                    Log.d(tag, "   - Relative path: ${currentResponse.getActualDownloadUrl()}")
                    Log.d(tag, "   - Full URL: $fullUrl")
                    fullUrl
                }

                Log.i(tag, "🚀 Starting download with AdvancedDownloadManager")
                Log.d(tag, "   - Filename: $filename")
                Log.d(tag, "   - Download URL: ${downloadUrl.take(100)}...")

                var shouldRetryStream = false
                advancedDownloadManager.downloadWithProgress(downloadUrl, filename, currentResponse.size).collect { progress ->
                    progressCallback?.invoke(progress)

                    when (progress) {
                        is DownloadProgress.Started -> {
                            Log.i(tag, "🎯 Started download: ${progress.filename}")
                        }
                        is DownloadProgress.Completed -> {
                            val isImage = filename.lowercase().let { name ->
                                name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                                name.endsWith(".png") || name.endsWith(".webp") ||
                                name.endsWith(".gif")
                            }
                            val isAudio = isMusicPlatform(platform) || filename.lowercase().let { name ->
                                name.endsWith(".mp3") || name.endsWith(".m4a") ||
                                name.endsWith(".wav") || name.endsWith(".flac") ||
                                name.endsWith(".aac")
                            }
                            try {
                                saveToGallery(context, progress.filePath, isImage, isAudio)
                                downloadHistoryManager.markAsDownloadedByFilename(filename, videoUrl)
                                val mediaType = when {
                                    isImage -> "image"
                                    isAudio -> "music"
                                    else -> "video"
                                }
                                Log.i(tag, "✅ Enhanced $mediaType download completed: $filename")
                            } catch (e: Exception) {
                                Log.e(tag, "❌ Failed to save to gallery: ${e.message}")
                                emit(DownloadProgress.Error(progress.id, filename, "Failed to save: ${e.message}"))
                                return@collect
                            }
                        }
                        is DownloadProgress.Error -> {
                            if (progress.error.startsWith("HTTP 5") && streamAttempt < 1) {
                                Log.w(tag, "⚠️ Stream returned ${progress.error}, retrying with fresh URL...")
                                shouldRetryStream = true
                                return@collect
                            }
                            Log.e(tag, "❌ Enhanced download failed: ${progress.error}")
                        }
                        is DownloadProgress.Progress -> {
                            val speedMBps = progress.speed / 1024 / 1024
                            Log.d(tag, "📊 Progress: ${progress.percentage.toInt()}% (${speedMBps}MB/s)")
                        }
                    }
                    emit(progress)
                }

                if (!shouldRetryStream) break

                streamAttempt++
                Log.i(tag, "🔄 Retrying stream download with fresh URL (attempt ${streamAttempt + 1}/2)...")
                kotlinx.coroutines.delay(1000)
                try {
                    currentResponse = getServerProcessedResponse(videoUrl, 1)
                } catch (e: Exception) {
                    Log.e(tag, "❌ Failed to get fresh stream URL: ${e.message}")
                    emit(DownloadProgress.Error(UUID.randomUUID().toString(), filename, "HTTP 500"))
                    break
                }
            }
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Enhanced download failed: ${e.message}")
            emit(DownloadProgress.Error(UUID.randomUUID().toString(), "video", e.message ?: "Download failed"))
        }
    }

    suspend fun downloadVideo(context: Context, videoUrl: String, retryCount: Int = 2): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(tag, "Processing URL: $videoUrl")
                
                // Check if this is a batch download request (multiple URLs)
                if (videoUrl.contains("|")) {
                    val urls = videoUrl.split("|").filter { it.isNotBlank() }
                    if (urls.size > 1) {
                        Log.i(tag, "🎯 Detected batch download request: ${urls.size} URLs")
                        return@withContext "BATCH_DOWNLOAD_INITIATED"
                    }
                }
                
                // Use server-side processing for all platforms (including YouTube)
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
     * Detect platform from URL
     */
    private fun detectPlatform(url: String): String {
        return when {
            url.contains("youtube.com") || url.contains("youtu.be") -> "youtube"
            url.contains("tiktok.com") -> "tiktok"
            url.contains("instagram.com") -> "instagram"
            url.contains("facebook.com") -> "facebook"
            url.contains("twitter.com") || url.contains("x.com") -> "twitter"
            url.contains("soundcloud.com") -> "soundcloud"
            url.contains("deezer.com") -> "deezer"
            url.contains("spotify.com") -> "spotify"
            else -> "other"
        }
    }
    
    /**
     * Get the appropriate base URL for the platform
     */
    private fun getBaseUrlForPlatform(platform: String): String {
        return if (platform == "youtube") {
            ApiClient.YOUTUBE_SERVER_URL
        } else {
            ApiClient.BASE_URL
        }
    }

    /**
     * Check if platform is a music/audio platform
     */
    private fun isMusicPlatform(platform: String): Boolean {
        return platform in listOf("soundcloud", "deezer", "spotify")
    }

    /**
     * Sanitize filename to be filesystem-safe
     */
    private fun sanitizeFilename(filename: String, maxLength: Int = 100): String {
        // Remove or replace invalid filesystem characters
        var sanitized = filename
            .replace(Regex("[/\\\\:*?\"<>|#]"), "") // Remove invalid chars
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .trim()
        
        // Extract extension
        val extension = sanitized.substringAfterLast('.', "")
        val nameWithoutExt = if (extension.isNotEmpty()) {
            sanitized.substringBeforeLast('.')
        } else {
            sanitized
        }
        
        // Truncate name if too long (keeping room for extension)
        val maxNameLength = if (extension.isNotEmpty()) maxLength - extension.length - 1 else maxLength
        val truncatedName = if (nameWithoutExt.length > maxNameLength) {
            nameWithoutExt.take(maxNameLength).trim()
        } else {
            nameWithoutExt
        }
        
        // Reconstruct filename
        return if (extension.isNotEmpty()) {
            "$truncatedName.$extension"
        } else {
            truncatedName
        }
    }

    /**
     * Generate appropriate filename for music downloads
     */
    private fun generateMusicFilename(title: String?, originalFilename: String?, platform: String): String {
        // Use title if available, otherwise fall back to original filename
        val baseName = when {
            !title.isNullOrBlank() -> {
                // Clean the title for use as filename
                title.replace(Regex("[^a-zA-Z0-9\\s\\-_.]"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            !originalFilename.isNullOrBlank() -> originalFilename
            else -> "audio_${System.currentTimeMillis()}"
        }
        
        // Ensure proper audio file extension
        val filename = when {
            baseName.endsWith(".mp3", ignoreCase = true) -> baseName
            baseName.endsWith(".m4a", ignoreCase = true) -> baseName
            baseName.endsWith(".wav", ignoreCase = true) -> baseName
            baseName.endsWith(".flac", ignoreCase = true) -> baseName
            else -> "$baseName.mp3" // Default to mp3 for music platforms
        }
        
        return sanitizeFilename(filename)
    }

    /**
     * Handle YouTube direct downloads using AdvancedDownloadManager for ultra-fast performance
     */
    private suspend fun handleYouTubeDirectDownload(
        context: Context, 
        directUrl: String, 
        filename: String, 
        originalUrl: String
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(tag, "🚀 YouTube Direct Download Mode - Ultra Fast")
                Log.d(tag, "📱 Using AdvancedDownloadManager for: $filename")
                
                val downloadHistoryManager = DownloadHistoryManager(context)
                
                // Use AdvancedDownloadManager for chunked, high-speed download
                var finalResult = "Download failed"
                
                advancedDownloadManager.downloadWithProgress(directUrl, filename).collect { progress ->
                    when (progress) {
                        is DownloadProgress.Started -> {
                            Log.i(tag, "🎯 Started ultra-fast download: ${progress.filename}")
                        }
                        is DownloadProgress.Progress -> {
                            val speedMBps = progress.speed / 1024 / 1024
                            val progressPercent = progress.percentage.toInt()
                            Log.d(tag, "📊 Progress: $progressPercent% (${speedMBps}MB/s)")
                        }
                        is DownloadProgress.Completed -> {
                            Log.i(tag, "✅ Ultra-fast download completed: ${progress.filename}")
                            
                            // Save to gallery
                            val platform = detectPlatform(originalUrl)
                            val isImage = filename.lowercase().let { name ->
                                name.endsWith(".jpg") || name.endsWith(".jpeg") || 
                                name.endsWith(".png") || name.endsWith(".webp") || 
                                name.endsWith(".gif")
                            }
                            val isAudio = isMusicPlatform(platform) || filename.lowercase().let { name ->
                                name.endsWith(".mp3") || name.endsWith(".m4a") || 
                                name.endsWith(".wav") || name.endsWith(".flac") || 
                                name.endsWith(".aac")
                            }
                            
                            try {
                                saveToGallery(context, progress.filePath, isImage, isAudio)
                                downloadHistoryManager.markAsDownloadedByFilename(filename, originalUrl)
                                
                                val fileSizeMB = progress.fileSize / 1024 / 1024
                                val mediaType = when {
                                    isImage -> "image"
                                    isAudio -> "music"
                                    else -> "video"
                                }
                                finalResult = "✅ Success: Downloaded $mediaType $filename (${fileSizeMB}MB) using ultra-fast mode"
                                
                            } catch (e: Exception) {
                                Log.e(tag, "❌ Failed to save to gallery: ${e.message}")
                                finalResult = "❌ Download completed but failed to save: ${e.message}"
                            }
                        }
                        is DownloadProgress.Error -> {
                            Log.e(tag, "❌ Ultra-fast download failed: ${progress.error}")
                            finalResult = "❌ Ultra-fast download failed: ${progress.error}"
                        }
                    }
                }
                
                return@withContext finalResult
                
            } catch (e: Exception) {
                Log.e(tag, "❌ YouTube direct download failed: ${e.message}")
                return@withContext "❌ YouTube direct download failed: ${e.message}"
            }
        }
    }

    private fun saveToGallery(context: Context, filePath: String, isImage: Boolean, isAudio: Boolean = false) {
        val file = java.io.File(filePath)
        if (!file.exists()) {
            throw Exception("File does not exist: $filePath")
        }

        val ext = file.extension.lowercase()
        val mimeType = when {
            isImage -> when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
            isAudio -> when (ext) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                "flac" -> "audio/flac"
                "aac" -> "audio/aac"
                else -> "audio/mpeg"
            }
            else -> when (ext) {
                "mp4" -> "video/mp4"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "3gp" -> "video/3gpp"
                else -> "video/mp4"
            }
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            when {
                isImage -> put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                isAudio -> put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
                else -> put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            }
        }

        val collection = when {
            isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            isAudio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        // On Android Q (API 29) IS_PENDING=1 entries are invisible to plain queries/deletes.
        // setIncludePending() ensures orphaned pending entries are also removed.
        val deleteUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.setIncludePending(collection)
        } else {
            collection
        }
        val deleted = context.contentResolver.delete(
            deleteUri,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(file.name)
        )
        Log.d(tag, "🗑️ Pre-delete: removed $deleted MediaStore entries for ${file.name}")

        // Also delete any physical file already at the target gallery path so MediaStore's
        // filesystem uniqueness check doesn't block the insert.
        val targetDir = when {
            isImage -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            isAudio -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        }
        val existingGalleryFile = java.io.File(targetDir, file.name)
        if (existingGalleryFile.exists()) {
            existingGalleryFile.delete()
            Log.d(tag, "🗑️ Deleted orphaned gallery file: ${existingGalleryFile.absolutePath}")
        }

        val uri = try {
            context.contentResolver.insert(collection, contentValues)
                ?: throw Exception("Failed to create media store entry")
        } catch (e: Exception) {
            if (e.message?.contains("Failed to build unique file") == true) {
                // Race condition: a concurrent insert beat us. Re-delete and retry once.
                Log.w(tag, "⚠️ Failed to build unique file — re-deleting and retrying once")
                context.contentResolver.delete(
                    deleteUri,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(file.name)
                )
                context.contentResolver.insert(collection, contentValues)
                    ?: throw Exception("Failed to create media store entry after retry")
            } else {
                throw e
            }
        }

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            file.inputStream().use { inputStream ->
                StreamUtils.copyFast(inputStream, outputStream)
            }
        } ?: throw Exception("Failed to open output stream")

        file.delete()

        val mediaType = when {
            isImage -> "image"
            isAudio -> "music"
            else -> "video"
        }
        Log.i(tag, "✅ Saved $mediaType to gallery: ${file.name}")
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
        
        // Check if this is a multi-image or multi-video download
        if ((response.type == "multi_image" || response.type == "multi_video") && !response.files.isNullOrEmpty()) {
            Log.i(tag, "🎯 Detected multi-${response.type} response with ${response.files.size} files")
            return handleMultipleDownloads(context, response, videoUrl)
        }
        
        // Single file download - use existing logic with proper filename handling
        val platform = detectPlatform(videoUrl)
        val filename = if (isMusicPlatform(platform)) {
            generateMusicFilename(response.getActualTitle(), response.getActualFilename(), platform)
        } else {
            response.getActualFilename() ?: throw kotlin.Exception("No filename returned from server")
        }
        
        // Check for duplicates using existing logic
        val downloadHistoryManager = DownloadHistoryManager(context)
        
        // Multi-layer duplicate detection (in order of reliability):
        // 1. PRIORITY: Check by URL hash (most reliable - exact same URL = exact same video)
        if (downloadHistoryManager.isAlreadyDownloadedByUrlHash(videoUrl)) {
            val urlHash = videoUrl.hashCode().toString()
            val downloadedAt = downloadHistoryManager.prefs.getLong("url_hash_${urlHash}_time", 0L)
            val timeSince = if (downloadedAt > 0) downloadHistoryManager.getTimeSinceDownload(downloadedAt) else "recently"
            Log.i(tag, "🔄 Duplicate detected by URL hash: $urlHash (downloaded $timeSince)")
            return "DUPLICATE_DETECTED:$timeSince"
        }
        
        // 2. Check by video ID extraction from filename (for same video, different URLs)
        val videoId = downloadHistoryManager.extractVideoIdFromFilename(filename)
        if (videoId != null && downloadHistoryManager.prefs.contains("video_$videoId")) {
            val downloadedAt = downloadHistoryManager.prefs.getLong("video_${videoId}_time", 0L)
            val timeSince = if (downloadedAt > 0) downloadHistoryManager.getTimeSinceDownload(downloadedAt) else "recently"
            Log.i(tag, "🔄 Duplicate detected by video ID: $videoId (downloaded $timeSince)")
            return "DUPLICATE_DETECTED:$timeSince"
        }
        
        // 3. Check by filename prefix (only for non-generic names)
        val isGenericFilename = filename.lowercase().let { name ->
            name.contains("facebook reel") || name.contains("tiktok") || 
            name == "video.mp4" || name == "instagram.mp4" || 
            name.startsWith("media_") || name.startsWith("- ")
        }
        
        if (!isGenericFilename && downloadHistoryManager.isAlreadyDownloadedByFilename(filename)) {
            val downloadInfo = downloadHistoryManager.getDownloadInfoByFilename(filename)
            val timeSince = downloadInfo?.let { info: DownloadInfo -> downloadHistoryManager.getTimeSinceDownload(info.downloadedAt) } ?: "recently"
            Log.i(tag, "🔄 Duplicate detected by filename: $filename (downloaded $timeSince)")
            return "DUPLICATE_DETECTED:$timeSince"
        }

        // Use the download URL from server response
        val serverFileUrl = if (!response.getActualDownloadUrl().isNullOrEmpty()) {
            if (response.getActualDownloadUrl()!!.startsWith("http")) {
                // Direct URL (e.g., YouTube direct link) - ensure HTTPS
                val rawUrl = response.getActualDownloadUrl()!!
                if (rawUrl.startsWith("http://")) {
                    rawUrl.replace("http://", "https://")
                } else {
                    rawUrl
                }
            } else {
                // Server relative URL (e.g., /stream/xyz) - prepend appropriate base URL
                val platform = detectPlatform(videoUrl)
                val baseUrl = getBaseUrlForPlatform(platform)
                "${baseUrl.trimEnd('/')}${response.getActualDownloadUrl()}"
            }
        } else {
            // Fallback to old /files/ endpoint for backward compatibility
            "${ApiClient.BASE_URL}files/$filename"
        }
        
        Log.d(tag, "Using download URL: $serverFileUrl")
        Log.d(tag, "🌐 Host: ${java.net.URL(serverFileUrl).host}")
        Log.d(tag, "📡 Path: ${java.net.URL(serverFileUrl).path}")
        
        // Check if this is a direct URL (YouTube) or server proxy
        val isDirect = response.getActualDownloadUrl()?.startsWith("http") == true
        Log.i(tag, if (isDirect) "📱 Direct download from source" else "🌊 Download via server proxy")
        
        // Detect file type based on filename extension and platform
        val isImage = filename.lowercase().let { name ->
            name.endsWith(".jpg") || name.endsWith(".jpeg") || 
            name.endsWith(".png") || name.endsWith(".webp") || 
            name.endsWith(".gif")
        }
        val isAudio = isMusicPlatform(platform) || filename.lowercase().let { name ->
            name.endsWith(".mp3") || name.endsWith(".m4a") || 
            name.endsWith(".wav") || name.endsWith(".flac") || 
            name.endsWith(".aac")
        }
        
        val result = downloadAndSaveToGallery(context, serverFileUrl, isImage, isAudio)
        
        // If download was successful, mark it as downloaded by filename
        if (result.contains("Success") || result.contains("Saved")) {
            downloadHistoryManager.markAsDownloadedByFilename(filename, videoUrl)
        }
        
        return result
    }


    private suspend fun handleMultipleDownloads(context: Context, response: DownloadResponse, videoUrl: String): String {
        val files = response.files ?: return "No files to download"
        val downloadHistoryManager = DownloadHistoryManager(context)
        
        val mediaType = if (response.type == "multi_video") "video" else "image"
        Log.i(tag, "📸 Multi-${mediaType} download: ${files.size} files")
        Log.d(tag, "Files to download: ${files.map { it.filename }}")
        
        var successCount = 0
        var failCount = 0
        
        for ((index, file) in files.withIndex()) {
            try {
                // Update toast with current progress
                withContext(Dispatchers.Main) {
                    SonnerToast.updateMessage("${index + 1}/${files.size}")
                }
                
                Log.d(tag, "Downloading file ${index + 1}/${files.size}: ${file.filename}")
                Log.d(tag, "File type: ${file.type}, Download URL: ${file.downloadUrl}")
                
                // For multi-media posts, create unique filenames using UUID from download URL
                // This prevents false duplicate detection across different posts
                val uniqueFilename = if (file.downloadUrl.contains("/stream/")) {
                    // Extract UUID from stream URL and use it in filename
                    val uuid = file.downloadUrl.substringAfter("/stream/").substringBefore("_")
                    val extension = file.filename.substringAfterLast(".", if (mediaType == "video") "mp4" else "jpg")
                    "${uuid}_${file.filename.substringBeforeLast(".")}.${extension}"
                } else {
                    file.filename
                }
                
                Log.d(tag, "Unique filename: $uniqueFilename")
                
                // Check for duplicates - prioritize URL check
                val fileUrl = if (file.downloadUrl.startsWith("http")) {
                    // Ensure HTTPS
                    if (file.downloadUrl.startsWith("http://")) {
                        file.downloadUrl.replace("http://", "https://")
                    } else {
                        file.downloadUrl
                    }
                } else {
                    val platform = detectPlatform(videoUrl)
                    val baseUrl = getBaseUrlForPlatform(platform)
                    "${baseUrl.trimEnd('/')}${file.downloadUrl}"
                }
                
                if (downloadHistoryManager.isAlreadyDownloadedByUrlHash(fileUrl)) {
                    Log.i(tag, "🔄 File already downloaded (by URL), skipping")
                    successCount++
                    continue
                }
                
                if (downloadHistoryManager.isAlreadyDownloadedByFilename(uniqueFilename)) {
                    Log.i(tag, "🔄 File ${uniqueFilename} already downloaded, skipping")
                    successCount++
                    continue
                }
                
                // Use the download URL from server response (could be relative or absolute)
                val serverFileUrl = if (file.downloadUrl.isNotEmpty() && file.downloadUrl.startsWith("http")) {
                    // Absolute URL - ensure HTTPS
                    if (file.downloadUrl.startsWith("http://")) {
                        file.downloadUrl.replace("http://", "https://")
                    } else {
                        file.downloadUrl
                    }
                } else {
                    // Relative URL, prepend appropriate base URL
                    val platform = detectPlatform(videoUrl)
                    val baseUrl = getBaseUrlForPlatform(platform)
                    "${baseUrl.trimEnd('/')}${file.downloadUrl}"
                }
                
                Log.d(tag, "Constructed server URL: $serverFileUrl")
                
                // Determine if this is an image or video based on file type or extension
                val isImage = file.type == "image" || file.filename.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp)$", RegexOption.IGNORE_CASE))
                val result = downloadAndSaveToGallery(context, serverFileUrl, isImage = isImage, isAudio = false)
                
                if (result.contains("Success") || result.contains("Saved")) {
                    // Mark as downloaded using the unique filename and actual file URL
                    downloadHistoryManager.markAsDownloadedByFilename(uniqueFilename, serverFileUrl)
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
            successCount == files.size -> "✅ ${successCount} ${mediaType}s"
            successCount > 0 -> "⚠️ ${successCount}/${files.size} ${mediaType}s"
            else -> "❌ Download failed"
        }
    }

    /**
     * Process video URL on server and get the response
     */
    private suspend fun getServerProcessedResponse(videoUrl: String, retryCount: Int): DownloadResponse {
        var lastException: Exception? = null
        
        // Detect if this is a YouTube URL
        val isYouTube = videoUrl.contains("youtube.com") || videoUrl.contains("youtu.be")
        val apiToUse = if (isYouTube) {
            Log.i(tag, "🎥 Routing to YouTube server")
            youtubeDownloadApi
        } else {
            Log.i(tag, "🌐 Routing to main server")
            videoDownloadApi
        }
        
        // Retry the API call
        repeat(retryCount) { attempt ->
            try {
                // For non-YouTube URLs, try to get a Turnstile session
                var turnstileSession: com.tapstream.downloader.network.J2Session? = null
                if (!isYouTube) {
                    turnstileSession = turnstileBypassProvider.getSession(videoUrl)
                }

                Log.d(tag, "Making API call to ${if (isYouTube) "YouTube" else "main"} backend (attempt ${attempt + 1}/$retryCount)...")
                val request = DownloadRequest(
                    url = videoUrl,
                    csrfToken = turnstileSession?.jwtAccessToken,
                    cookieString = turnstileSession?.cookieString,
                    userAgent = turnstileSession?.userAgent
                )
                val apiResponse = apiToUse.downloadVideo(request)
                
                if (!apiResponse.isSuccessful) {
                    Log.e(tag, "Backend Failed (attempt ${attempt + 1}/$retryCount): HTTP ${apiResponse.code()}")
                    
                    // Use ErrorMapper to handle server errors
                    val httpException = HttpException(apiResponse)
                    val errorMessage = ErrorMapper.mapServerError(httpException)
                    
                    // Check for token expiration (triggered by backend returning specific error)
                    if (errorMessage.contains("SESSION_EXPIRED", ignoreCase = true)) {
                        Log.w(tag, "⚠️ Turnstile session expired, clearing cache and retrying...")
                        turnstileBypassProvider.clearCache()
                        if (attempt < retryCount - 1) {
                            return@repeat // Continue to next attempt
                        }
                    }

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
                            Log.i(tag, "🔄 Server error, retrying in 15 seconds...")
                            kotlinx.coroutines.delay(15_000)
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
                    
                    // Check if server says YouTube should be handled client-side
                    if (errorMsg.contains("YOUTUBE_CLIENT_SIDE", ignoreCase = true)) {
                        Log.i(tag, "📱 Server redirected YouTube to client-side extraction")
                        throw UnsupportedContentException("YOUTUBE_CLIENT_SIDE")
                    }
                    
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
                if (responseBody.getActualFilename() == null && responseBody.files.isNullOrEmpty()) {
                    throw kotlin.Exception("No filename or files returned from server")
                }

                Log.i(tag, "✅ Server processing successful")
                if (responseBody.type == "multi_image" || responseBody.type == "multi_video") {
                    Log.d(tag, "📸 Multi-${responseBody.type} response: ${responseBody.files?.size} files")
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
                        Log.i(tag, "🔄 HTTP error ${e.code()}, retrying in 15 seconds...")
                        kotlinx.coroutines.delay(15_000)
                        return@repeat
                    }
                }
                
                throw kotlin.Exception(errorMessage)
                
            } catch (e: SocketTimeoutException) {
                lastException = e
                Log.e(tag, "⏰ TIMEOUT (attempt ${attempt + 1}/$retryCount): ${e.message}")
                Log.e(tag, "💡 This is normal for Render free tier - server needs time to wake up")
                
                if (attempt < retryCount - 1) {
                    Log.i(tag, "🔄 Timeout, retrying in 20 seconds...")
                    kotlinx.coroutines.delay(20_000)
                    return@repeat
                }
                
                throw kotlin.Exception(ErrorMapper.mapNetworkError(e))
                
            } catch (e: ConnectException) {
                lastException = e
                Log.e(tag, "🔌 Connection failed (attempt ${attempt + 1}/$retryCount): ${e.message}")
                
                if (attempt < retryCount - 1) {
                    Log.i(tag, "🔄 Connection failed, retrying in 20 seconds...")
                    kotlinx.coroutines.delay(20_000)
                    return@repeat
                }
                
                throw kotlin.Exception(ErrorMapper.mapNetworkError(e))
                
            } catch (e: UnknownHostException) {
                lastException = e
                Log.e(tag, "🌐 DNS resolution failed (attempt ${attempt + 1}/$retryCount): ${e.message}")
                
                if (attempt < retryCount - 1) {
                    Log.i(tag, "🔄 DNS error, retrying in 10 seconds...")
                    kotlinx.coroutines.delay(10_000)
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
                    Log.i(tag, "🔄 Exception occurred, retrying in 10 seconds...")
                    kotlinx.coroutines.delay(10_000)
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

    private fun downloadAndSaveToGallery(context: Context, fileUrl: String, isImage: Boolean = false, isAudio: Boolean = false): String {
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
            Log.i(tag, "Opening output stream...")
            resolver.openOutputStream(uri)?.use { outputStream ->
                // Use Okio for optimized stream copying (20-30% faster)
                StreamUtils.copyWithProgress(inputStream, outputStream, contentLength)
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
                errorMsg.contains("no space left on device") || errorMsg.contains("enospc") ->
                    "💾 Not enough storage space on device. Please free up some space and try again."
                else -> "💾 Storage error: Unable to save file - ${e.message}"
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Log.e(tag, "Save failed: ${e.javaClass.simpleName}", e)
            "💾 Save error: ${e.message}"
        }
    }

    suspend fun getSystemVersion(): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(tag, "Fetching system version from server...")
                val apiResponse = videoDownloadApi.getSystemInfo()
                
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

    suspend fun getSystemInfo(): com.tapstream.downloader.api.VersionResponse? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(tag, "Fetching system info from server...")
                val apiResponse = videoDownloadApi.getSystemInfo()
                
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

    /**
     * Cleanup resources (no longer needed with DI - Hilt manages lifecycle)
     */
    fun cleanup() {
        advancedDownloadManager.cleanup()
        Log.i(tag, "🧹 VideoRepository cleanup completed")
    }
}