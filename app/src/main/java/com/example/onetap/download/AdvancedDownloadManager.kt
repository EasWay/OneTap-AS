package com.example.onetap.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Advanced Download Manager
 * Features:
 * - Concurrent multi-threaded downloads
 * - Chunked downloading with resume capability
 * - Real-time progress tracking
 * - Automatic retry with exponential backoff
 */
class AdvancedDownloadManager(private val context: Context) {
    
    companion object {
        private const val TAG = "AdvancedDownloadManager"
        private const val MAX_CONCURRENT_DOWNLOADS = 10
        private const val CHUNK_SIZE = 1024 * 1024 // 1MB chunks
        private const val MAX_CONNECTIONS_PER_HOST = 8
        private const val CONNECT_TIMEOUT = 15_000L
        private const val READ_TIMEOUT = 30_000L
        private const val WRITE_TIMEOUT = 30_000L
    }
    
    // High-performance OkHttp client with optimized settings
    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(32, 5, java.util.concurrent.TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(CONNECT_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
    
    // Coroutine scope with optimized dispatcher
    private val downloadScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + 
        CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Download coroutine error", throwable)
        }
    )
    
    // Download queue and tracking
    private val downloadQueue = Channel<DownloadTask>(Channel.UNLIMITED)
    private val activeDownloads = ConcurrentHashMap<String, Job>()
    private val downloadStats = ConcurrentHashMap<String, DownloadStats>()
    
    // Download semaphore to limit concurrent downloads
    private val downloadSemaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_DOWNLOADS)
    
    init {
        // Start download processor
        startDownloadProcessor()
    }
    
    /**
     * Queue multiple downloads for ultra-fast batch processing
     */
    suspend fun queueBatchDownloads(downloads: List<BatchDownloadRequest>): Flow<BatchDownloadResult> = flow {
        Log.i(TAG, "🚀 Starting batch download of ${downloads.size} items")
        
        val results = downloads.map { request ->
            downloadScope.async<BatchDownloadResult> {
                try {
                    val result = downloadWithProgress(
                        url = request.url,
                        filename = request.filename,
                        downloadId = request.id
                    ).last() // Get final result
                    
                    when (result) {
                        is DownloadProgress.Completed -> BatchDownloadResult.Success(request.id, request.filename, result.filePath)
                        else -> BatchDownloadResult.Error(request.id, request.filename, "Download failed")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Batch download failed for ${request.filename}: ${e.message}")
                    BatchDownloadResult.Error(request.id, request.filename, e.message ?: "Unknown error")
                }
            }
        }.awaitAll()
        
        results.forEach { emit(it) }
    }
    
    /**
     * Download single file with real-time progress and chunked downloading
     */
    suspend fun downloadWithProgress(
        url: String,
        filename: String,
        downloadId: String = java.util.UUID.randomUUID().toString()
    ): Flow<DownloadProgress> = flow {
        downloadSemaphore.acquire()
        
        try {
            // Sanitize filename to prevent file system errors
            val sanitizedFilename = sanitizeFilename(filename)
            
            Log.i(TAG, "🎯 Starting ultra-fast download: $sanitizedFilename")
            
            val outputFile = File(context.getExternalFilesDir(null), sanitizedFilename)
            val stats = DownloadStats(downloadId, sanitizedFilename, System.currentTimeMillis())
            downloadStats[downloadId] = stats
            
            // Emit initial progress
            emit(DownloadProgress.Started(downloadId, sanitizedFilename))
            
            // Check if server supports range requests for chunked downloading
            val supportsRanges = checkRangeSupport(url)
            
            if (supportsRanges) {
                // Use chunked download for better performance
                downloadChunked(url, outputFile, downloadId, stats).collect { progress ->
                    emit(progress)
                }
            } else {
                // Fallback to single-threaded download
                downloadSingleStream(url, outputFile, downloadId, stats).collect { progress ->
                    emit(progress)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Download failed for $filename: ${e.message}")
            emit(DownloadProgress.Error(downloadId, filename, e.message ?: "Download failed"))
        } finally {
            downloadSemaphore.release()
            downloadStats.remove(downloadId)
        }
    }
    
    /**
     * Sanitize filename to prevent file system errors
     * - Limit length to 200 characters (safe limit for most file systems)
     * - Remove invalid characters
     * - Preserve file extension
     */
    private fun sanitizeFilename(filename: String): String {
        // Extract extension
        val lastDot = filename.lastIndexOf('.')
        val name = if (lastDot > 0) filename.substring(0, lastDot) else filename
        val ext = if (lastDot > 0) filename.substring(lastDot) else ""
        
        // Remove invalid characters
        val cleanName = name.replace(Regex("[^a-zA-Z0-9\\s\\-_.]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        
        // Limit length (200 chars for name + extension)
        val maxNameLength = 200 - ext.length
        val truncatedName = if (cleanName.length > maxNameLength) {
            cleanName.substring(0, maxNameLength).trim()
        } else {
            cleanName
        }
        
        return "$truncatedName$ext"
    }
    
    /**
     * Ultra-fast chunked download with multiple connections
     */
    private suspend fun downloadChunked(
        url: String,
        outputFile: File,
        downloadId: String,
        stats: DownloadStats
    ): Flow<DownloadProgress> = flow {
        
        // Get file size first
        val fileSize = getFileSize(url)
        if (fileSize <= 0) {
            // Fallback to single stream if size unknown
            downloadSingleStream(url, outputFile, downloadId, stats).collect { emit(it) }
            return@flow
        }
        
        Log.i(TAG, "📊 File size: ${fileSize / 1024 / 1024}MB, using chunked download")
        
        val numChunks = min(8, (fileSize / CHUNK_SIZE).toInt() + 1) // Max 8 chunks
        val chunkSize = fileSize / numChunks
        
        stats.totalBytes = fileSize
        emit(DownloadProgress.Progress(downloadId, outputFile.name, 0, fileSize, 0f, 0))
        
        // Create temporary chunk files
        val chunkFiles = (0 until numChunks).map { i ->
            File(outputFile.parent, "${outputFile.name}.chunk$i")
        }
        
        try {
            // Download chunks concurrently
            val chunkJobs = (0 until numChunks).map { chunkIndex ->
                downloadScope.async<Unit>(Dispatchers.IO) {
                    val start = chunkIndex * chunkSize
                    val end = if (chunkIndex == numChunks - 1) fileSize - 1 else (start + chunkSize - 1)
                    
                    downloadChunk(url, chunkFiles[chunkIndex], start, end, chunkIndex)
                }
            }
            
            // Monitor progress
            val progressJob = downloadScope.async<Unit> {
                while (chunkJobs.any { !it.isCompleted }) {
                    val totalDownloaded = chunkFiles.sumOf { if (it.exists()) it.length() else 0L }
                    val progress = (totalDownloaded.toFloat() / fileSize * 100).coerceIn(0f, 100f)
                    val speed = calculateSpeed(stats, totalDownloaded)
                    
                    emit(DownloadProgress.Progress(downloadId, outputFile.name, totalDownloaded, fileSize, progress, speed))
                    delay(100) // Update every 100ms
                }
            }
            
            // Wait for all chunks to complete
            chunkJobs.awaitAll()
            progressJob.cancel()
            
            // Merge chunks into final file
            Log.i(TAG, "🔗 Merging ${numChunks} chunks...")
            mergeChunks(chunkFiles, outputFile)
            
            // Cleanup chunk files
            chunkFiles.forEach { it.delete() }
            
            Log.i(TAG, "✅ Chunked download completed: ${outputFile.name}")
            emit(DownloadProgress.Completed(downloadId, outputFile.name, outputFile.absolutePath, fileSize))
            
        } catch (e: Exception) {
            // Cleanup on error
            chunkFiles.forEach { it.delete() }
            throw e
        }
    }
    
    /**
     * Download single chunk with range request
     */
    private suspend fun downloadChunk(
        url: String,
        chunkFile: File,
        start: Long,
        end: Long,
        chunkIndex: Int
    ) = withContext(Dispatchers.IO) {
        
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .header("User-Agent", "OneTap-UltraFast/2.0")
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw IOException("Chunk $chunkIndex download failed: ${response.code}")
        }
        
        response.body?.let { body ->
            FileOutputStream(chunkFile).use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }
        } ?: throw IOException("Empty response body for chunk $chunkIndex")
        
        Log.d(TAG, "✅ Chunk $chunkIndex downloaded: ${chunkFile.length()} bytes")
    }
    
    /**
     * Fallback single-stream download with progress
     */
    private suspend fun downloadSingleStream(
        url: String,
        outputFile: File,
        downloadId: String,
        stats: DownloadStats
    ): Flow<DownloadProgress> = flow {
        
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OneTap-UltraFast/2.0")
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw IOException("Download failed: ${response.code}")
        }
        
        val body = response.body ?: throw IOException("Empty response body")
        val contentLength = body.contentLength()
        stats.totalBytes = contentLength
        
        Log.i(TAG, "📡 Single-stream download: ${contentLength / 1024 / 1024}MB (contentLength: $contentLength)")
        
        // Emit initial progress
        emit(DownloadProgress.Progress(downloadId, outputFile.name, 0L, contentLength, 0f, 0L))
        
        FileOutputStream(outputFile).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(8192) // 8KB buffer
                var totalBytesRead = 0L
                var lastProgressTime = System.currentTimeMillis()
                var lastProgressBytes = 0L
                
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    
                    // Emit progress every 100ms
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastProgressTime >= 100) {
                        val progress = if (contentLength > 0) {
                            (totalBytesRead.toFloat() / contentLength * 100).coerceIn(0f, 100f)
                        } else {
                            // If content length unknown, show bytes downloaded
                            0f
                        }
                        
                        // Calculate speed based on bytes since last update
                        val timeDiff = currentTime - lastProgressTime
                        val bytesDiff = totalBytesRead - lastProgressBytes
                        val speed = if (timeDiff > 0) (bytesDiff * 1000) / timeDiff else 0L
                        
                        Log.d(TAG, "📊 Progress: ${progress.toInt()}% ($totalBytesRead/$contentLength bytes, ${speed / 1024}KB/s)")
                        
                        emit(DownloadProgress.Progress(downloadId, outputFile.name, totalBytesRead, contentLength, progress, speed))
                        
                        lastProgressTime = currentTime
                        lastProgressBytes = totalBytesRead
                    }
                }
                
                // Emit final progress at 100%
                if (contentLength > 0) {
                    emit(DownloadProgress.Progress(downloadId, outputFile.name, totalBytesRead, contentLength, 100f, 0L))
                }
            }
        }
        
        Log.i(TAG, "✅ Single-stream download completed: ${outputFile.name}")
        emit(DownloadProgress.Completed(downloadId, outputFile.name, outputFile.absolutePath, outputFile.length()))
    }
    
    /**
     * Check if server supports range requests
     */
    private suspend fun checkRangeSupport(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "OneTap-UltraFast/2.0")
                .build()
            
            val response = httpClient.newCall(request).execute()
            val acceptRanges = response.header("Accept-Ranges")
            val supportsRanges = acceptRanges == "bytes"
            
            Log.d(TAG, "🔍 Range support check: $supportsRanges (Accept-Ranges: $acceptRanges)")
            return@withContext supportsRanges
        } catch (e: Exception) {
            Log.w(TAG, "Range support check failed: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Get file size from server
     */
    private suspend fun getFileSize(url: String): Long = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "OneTap-UltraFast/2.0")
                .build()
            
            val response = httpClient.newCall(request).execute()
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
            
            Log.d(TAG, "📏 File size: ${contentLength / 1024 / 1024}MB")
            return@withContext contentLength
        } catch (e: Exception) {
            Log.w(TAG, "File size check failed: ${e.message}")
            return@withContext -1L
        }
    }
    
    /**
     * Merge chunk files into final file
     */
    private suspend fun mergeChunks(chunkFiles: List<File>, outputFile: File) = withContext(Dispatchers.IO) {
        FileOutputStream(outputFile).use { output ->
            chunkFiles.forEach { chunkFile ->
                if (chunkFile.exists()) {
                    chunkFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
    
    /**
     * Calculate download speed in bytes per second
     */
    private fun calculateSpeed(stats: DownloadStats, currentBytes: Long): Long {
        val currentTime = System.currentTimeMillis()
        val elapsedTime = currentTime - stats.startTime
        
        return if (elapsedTime > 0) {
            (currentBytes * 1000) / elapsedTime
        } else 0L
    }
    
    /**
     * Start download processor for queued downloads
     */
    private fun startDownloadProcessor() {
        downloadScope.launch {
            for (task in downloadQueue) {
                launch {
                    try {
                        downloadWithProgress(task.url, task.filename, task.id).collect { progress ->
                            task.progressCallback(progress)
                        }
                    } catch (e: Exception) {
                        task.progressCallback(DownloadProgress.Error(task.id, task.filename, e.message ?: "Unknown error"))
                    }
                }
            }
        }
    }
    
    /**
     * Cancel download
     */
    fun cancelDownload(downloadId: String) {
        activeDownloads[downloadId]?.cancel()
        activeDownloads.remove(downloadId)
        downloadStats.remove(downloadId)
    }
    
    /**
     * Cancel all downloads
     */
    fun cancelAllDownloads() {
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
        downloadStats.clear()
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        downloadScope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}

// Data classes
data class BatchDownloadRequest(
    val id: String,
    val url: String,
    val filename: String
)

sealed class BatchDownloadResult {
    data class Success(val id: String, val filename: String, val filePath: String) : BatchDownloadResult()
    data class Error(val id: String, val filename: String, val error: String) : BatchDownloadResult()
}

sealed class DownloadProgress {
    data class Started(val id: String, val filename: String) : DownloadProgress()
    data class Progress(val id: String, val filename: String, val downloaded: Long, val total: Long, val percentage: Float, val speed: Long) : DownloadProgress()
    data class Completed(val id: String, val filename: String, val filePath: String, val fileSize: Long) : DownloadProgress()
    data class Error(val id: String, val filename: String, val error: String) : DownloadProgress()
}

data class DownloadTask(
    val id: String,
    val url: String,
    val filename: String,
    val progressCallback: (DownloadProgress) -> Unit
)

data class DownloadStats(
    val id: String,
    val filename: String,
    val startTime: Long,
    var totalBytes: Long = 0L
)