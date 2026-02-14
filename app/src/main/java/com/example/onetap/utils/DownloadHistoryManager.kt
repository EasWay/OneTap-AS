package com.example.onetap.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.regex.Pattern

data class DownloadInfo(
    val filename: String,
    val downloadedAt: Long,
    val url: String
)

class DownloadHistoryManager(context: Context) {
    
    val prefs: SharedPreferences = context.getSharedPreferences("download_history", Context.MODE_PRIVATE)
    
    fun extractVideoIdFromFilename(filename: String): String? {
        // Extract TikTok video ID from filename patterns
        val patterns = listOf(
            Pattern.compile(".*_([0-9]{19}).*"), // TikTok 19-digit ID
            Pattern.compile(".*_([a-zA-Z0-9]{11}).*"), // YouTube-style ID
            Pattern.compile(".*_([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}).*") // UUID pattern
        )
        
        for (pattern in patterns) {
            val matcher = pattern.matcher(filename)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }
    
    fun isAlreadyDownloadedByFilename(filename: String): Boolean {
        // For multi-image posts, check the exact filename, not just prefix
        if (filename.contains("_image_")) {
            // This is likely a multi-image post, check exact filename
            return prefs.all.keys.any { key ->
                key.startsWith("filename_") && try {
                    prefs.getString(key, "") == filename
                } catch (e: ClassCastException) {
                    // Skip keys that aren't strings
                    false
                }
            }
        }
        
        // For generic filenames (like "Tiktok.mp4", "video.mp4"), require exact match
        val isGenericFilename = filename.lowercase().let { name ->
            name == "tiktok.mp4" || name == "video.mp4" || name == "instagram.mp4" || 
            name == "facebook.mp4" || name == "twitter.mp4" || name.startsWith("media_")
        }
        
        if (isGenericFilename) {
            // Generic filenames must match exactly AND have same URL
            // This prevents false positives from generic names
            return false // Don't block generic filenames - they're not unique enough
        }
        
        // For regular files with meaningful names, check by filename prefix (first 30 characters)
        val prefix = filename.take(30) // Increased from 20 to 30 for better uniqueness
        return prefs.all.keys.any { key ->
            key.startsWith("filename_") && try {
                val storedFilename = prefs.getString(key, "") ?: ""
                storedFilename.take(30) == prefix && storedFilename.length > 15 // Avoid very short generic names
            } catch (e: ClassCastException) {
                // Skip keys that aren't strings
                false
            }
        }
    }
    
    fun getDownloadInfoByFilename(filename: String): DownloadInfo? {
        // For multi-image posts, check the exact filename
        if (filename.contains("_image_")) {
            val matchingKey = prefs.all.keys.find { key ->
                key.startsWith("filename_") && try {
                    prefs.getString(key, "") == filename
                } catch (e: ClassCastException) {
                    false
                }
            }
            
            return matchingKey?.let { key ->
                val timestamp = key.removePrefix("filename_").toLongOrNull() ?: return null
                val storedFilename = try {
                    prefs.getString(key, "") ?: return null
                } catch (e: ClassCastException) {
                    return null
                }
                val url = try {
                    prefs.getString("url_$timestamp", "") ?: ""
                } catch (e: ClassCastException) {
                    ""
                }
                DownloadInfo(storedFilename, timestamp, url)
            }
        }
        
        // For generic filenames, don't return match info to avoid false positives
        val isGenericFilename = filename.lowercase().let { name ->
            name == "tiktok.mp4" || name == "video.mp4" || name == "instagram.mp4" || 
            name == "facebook.mp4" || name == "twitter.mp4" || name.startsWith("media_")
        }
        
        if (isGenericFilename) {
            return null // Don't return info for generic filenames
        }
        
        // For regular files, check by prefix
        val prefix = filename.take(30)
        val matchingKey = prefs.all.keys.find { key ->
            key.startsWith("filename_") && try {
                val storedFilename = prefs.getString(key, "") ?: ""
                storedFilename.take(30) == prefix && storedFilename.length > 15
            } catch (e: ClassCastException) {
                false
            }
        }
        
        return matchingKey?.let { key ->
            val timestamp = key.removePrefix("filename_").toLongOrNull() ?: return null
            val storedFilename = try {
                prefs.getString(key, "") ?: return null
            } catch (e: ClassCastException) {
                return null
            }
            val url = try {
                prefs.getString("url_$timestamp", "") ?: ""
            } catch (e: ClassCastException) {
                ""
            }
            DownloadInfo(storedFilename, timestamp, url)
        }
    }
    
    fun isAlreadyDownloadedByUrlHash(url: String): Boolean {
        val urlHash = url.hashCode().toString()
        return prefs.contains("url_hash_$urlHash")
    }
    
    fun markAsDownloadedByFilename(filename: String, url: String) {
        val timestamp = System.currentTimeMillis()
        prefs.edit().apply {
            putString("filename_$timestamp", filename)
            putString("url_$timestamp", url)
            putLong("timestamp_$timestamp", timestamp)
            
            // Also mark by URL hash for exact URL matching
            val urlHash = url.hashCode().toString()
            putBoolean("url_hash_$urlHash", true)
            putLong("url_hash_${urlHash}_time", timestamp)
            
            // Mark by video ID if extractable
            extractVideoIdFromFilename(filename)?.let { videoId ->
                putBoolean("video_$videoId", true)
                putLong("video_${videoId}_time", timestamp)
            }
            
            apply()
        }
    }
    
    fun getTimeSinceDownload(downloadedAt: Long): String {
        val now = System.currentTimeMillis()
        val diffMinutes = (now - downloadedAt) / (1000 * 60)
        
        return when {
            diffMinutes < 1 -> "just now"
            diffMinutes < 60 -> "${diffMinutes}m ago"
            diffMinutes < 1440 -> "${diffMinutes / 60}h ago"
            else -> "${diffMinutes / 1440}d ago"
        }
    }
}