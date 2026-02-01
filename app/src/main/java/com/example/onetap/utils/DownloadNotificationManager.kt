package com.example.onetap.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.onetap.R

class DownloadNotificationManager(private val context: Context) {
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "download_progress"
    private val notificationId = 2001
    
    init {
        createNotificationChannel()
    }
    
    /**
     * Safely get notification icon resource ID with fallback
     */
    private fun getNotificationIcon(iconType: String = "default"): Int {
        // Always use system icons to avoid resource issues
        return when (iconType) {
            "download" -> android.R.drawable.stat_sys_download
            "complete" -> android.R.drawable.stat_sys_download_done
            "error" -> android.R.drawable.stat_notify_error
            else -> android.R.drawable.ic_dialog_info
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Download Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress for videos"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    fun showDownloadStarted() {
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Download Starting")
            .setContentText("Starting download...")
            .setSmallIcon(getNotificationIcon("download")) // Use safe icon getter
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        notificationManager.notify(notificationId, notification)
    }
    
    fun updateProgress(progress: Int, totalBytes: Long, downloadedBytes: Long) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Downloading")
            .setContentText("Downloaded: ${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}")
            .setSmallIcon(getNotificationIcon("download")) // Use safe icon getter
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        notificationManager.notify(notificationId, notification)
    }
    
    fun showDownloadComplete() {
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Download Complete")
            .setContentText("Video saved to Gallery")
            .setSmallIcon(getNotificationIcon("complete")) // Use safe icon getter
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        notificationManager.notify(notificationId, notification)
    }
    
    fun showDownloadError(error: String) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Download Failed")
            .setContentText(error)
            .setSmallIcon(getNotificationIcon("error")) // Use safe icon getter
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        notificationManager.notify(notificationId, notification)
    }
    
    fun hideNotification() {
        notificationManager.cancel(notificationId)
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
            bytes >= 1024 -> "${bytes / 1024}KB"
            else -> "${bytes}B"
        }
    }
}