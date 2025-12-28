package com.example.onetap.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
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
    
    private fun getCircularIcon() = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_round)
    
    fun showDownloadStarted() {
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Download Starting")
            .setContentText("Starting download...")
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setLargeIcon(getCircularIcon())
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
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setLargeIcon(getCircularIcon())
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
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setLargeIcon(getCircularIcon())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        notificationManager.notify(notificationId, notification)
    }
    
    fun showDownloadError(error: String) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Download Failed")
            .setContentText(error)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setLargeIcon(getCircularIcon())
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