package com.example.onetap.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Keep
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val isUpdateAvailable: Boolean
)

class UpdateManager(private val context: Context) {
    private val TAG = "OneTap_UpdateManager"
    private val UPDATE_CHECK_URL = "https://onetap-225t.onrender.com/version"
    private val NOTIFICATION_CHANNEL_ID = "onetap_updates"
    
    private var downloadId: Long = -1
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "OneTap Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for OneTap app updates"
                enableVibration(true)
                setShowBadge(true)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "📢 Notification channel created: $NOTIFICATION_CHANNEL_ID")
        }
    }
    
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Pre-Android 13 doesn't need runtime permission
        }
    }
    
    suspend fun checkForUpdates(): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "🔍 Starting update check...")
                Log.d(TAG, "📡 Contacting server: $UPDATE_CHECK_URL")
                
                val url = URL(UPDATE_CHECK_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                Log.d(TAG, "⏱️ Connection timeout: 10s, Read timeout: 10s")
                Log.d(TAG, "🌐 Connecting to server...")
                
                val responseCode = connection.responseCode
                Log.i(TAG, "📊 Server response code: $responseCode")
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().readText()
                    Log.i(TAG, "✅ Server response received")
                    Log.d(TAG, "📄 Raw response: $response")
                    
                    val updateInfo = parseUpdateResponse(response)
                    if (updateInfo != null) {
                        Log.i(TAG, "🎯 Update info parsed successfully")
                        Log.d(TAG, "📱 Current version: ${getCurrentVersionCode()}")
                        Log.d(TAG, "🆕 Latest version: ${updateInfo.versionCode}")
                        Log.d(TAG, "🔄 Update available: ${updateInfo.isUpdateAvailable}")
                        if (updateInfo.isUpdateAvailable) {
                            Log.i(TAG, "🚀 UPDATE AVAILABLE! v${updateInfo.versionName}")
                        } else {
                            Log.i(TAG, "✨ App is up to date")
                        }
                    } else {
                        Log.e(TAG, "❌ Failed to parse update response")
                    }
                    return@withContext updateInfo
                } else {
                    Log.e(TAG, "❌ Update check failed with response code: $responseCode")
                    
                    // Try to read error response
                    try {
                        val errorResponse = connection.errorStream?.bufferedReader()?.readText()
                        Log.e(TAG, "🔥 Error response: $errorResponse")
                    } catch (e: Exception) {
                        Log.e(TAG, "🔥 Could not read error response: ${e.message}")
                    }
                    
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "💥 Exception during update check: ${e.javaClass.simpleName}")
                Log.e(TAG, "💥 Exception message: ${e.message}")
                Log.e(TAG, "💥 Exception details: ", e)
                null
            }
        }
    }
    
    private fun parseUpdateResponse(response: String): UpdateInfo? {
        return try {
            Log.d(TAG, "🔧 Parsing JSON response...")
            val json = JSONObject(response)
            
            val latestVersionCode = json.getInt("latest_version")
            val downloadUrl = json.getString("apk_url")
            val releaseNotes = json.optString("release_notes", "Bug fixes and improvements")
            
            Log.d(TAG, "📊 Parsed - Version: $latestVersionCode, URL: $downloadUrl")
            Log.d(TAG, "📝 Release notes: $releaseNotes")
            
            val currentVersionCode = getCurrentVersionCode()
            val isUpdateAvailable = latestVersionCode > currentVersionCode
            
            Log.d(TAG, "🔍 Version comparison: Current=$currentVersionCode, Latest=$latestVersionCode")
            
            // Extract version name from download URL or use a default format
            val versionName = extractVersionFromUrl(downloadUrl) ?: "v$latestVersionCode"
            Log.d(TAG, "🏷️ Version name extracted: $versionName")
            
            val updateInfo = UpdateInfo(
                versionCode = latestVersionCode,
                versionName = versionName,
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes,
                isUpdateAvailable = isUpdateAvailable
            )
            
            Log.i(TAG, "✅ UpdateInfo created successfully")
            updateInfo
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error parsing update response: ${e.javaClass.simpleName}")
            Log.e(TAG, "💥 Parse error message: ${e.message}")
            Log.e(TAG, "💥 Raw response that failed to parse: $response")
            null
        }
    }
    
    private fun extractVersionFromUrl(url: String): String? {
        return try {
            // Extract version from GitHub release URL like: /releases/download/v2.0/OneTap_v2.apk
            val regex = Regex("/releases/download/([^/]+)/")
            regex.find(url)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
            Log.d(TAG, "📱 Current app version code: $versionCode")
            versionCode
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error getting current version code: ${e.message}", e)
            Log.w(TAG, "⚠️ Defaulting to version code 1")
            1
        }
    }
    
    fun downloadAndInstallUpdate(updateInfo: UpdateInfo, onProgress: (Int) -> Unit = {}) {
        try {
            Log.i(TAG, "📥 Starting direct download...")
            Log.d(TAG, "🔗 Download URL: ${updateInfo.downloadUrl}")
            
            // Check notification permission
            if (!hasNotificationPermission()) {
                Log.w(TAG, "⚠️ No notification permission - download will be silent")
            }
            
            // Start direct download in background
            downloadId = System.currentTimeMillis() // Use timestamp as ID
            Log.i(TAG, "⬇️ Direct download started with ID: $downloadId")
            
            // Start downloading directly
            startDirectDownload(updateInfo, onProgress)
            
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error starting direct download: ${e.javaClass.simpleName}")
            Log.e(TAG, "💥 Download error message: ${e.message}", e)
            onProgress(-1) // Signal error
        }
    }
    
    private fun startDirectDownload(updateInfo: UpdateInfo, onProgress: (Int) -> Unit) {
        // Use a separate coroutine scope for the download
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "🌐 Starting direct stream download...")
                
                // Create destination file in cache directory (no permissions needed)
                val fileName = extractFileNameFromUrl(updateInfo.downloadUrl) 
                    ?: "OneTap_${updateInfo.versionName}.apk"
                val destinationFile = File(context.externalCacheDir, fileName)
                
                Log.d(TAG, "📁 Download destination: ${destinationFile.absolutePath}")
                
                // Open connection to the APK URL
                val url = URL(updateInfo.downloadUrl)
                val connection = url.openConnection()
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "OneTap-UpdateManager/1.0")
                
                Log.d(TAG, "🔗 Connecting to: ${updateInfo.downloadUrl}")
                connection.connect()
                
                val contentLength = connection.contentLength
                Log.i(TAG, "📊 File size: ${contentLength / 1024}KB")
                
                // Stream the data directly
                val input = connection.getInputStream()
                val output = destinationFile.outputStream()
                
                val buffer = ByteArray(8192) // 8KB buffer for better performance
                var totalBytesRead = 0
                var bytesRead: Int
                
                Log.i(TAG, "📥 Starting data transfer...")
                
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    
                    // Calculate and report progress
                    if (contentLength > 0) {
                        val progress = ((totalBytesRead * 100) / contentLength)
                        withContext(Dispatchers.Main) {
                            onProgress(progress)
                        }
                        Log.d(TAG, "📈 Download progress: $progress% ($totalBytesRead/$contentLength bytes)")
                    }
                }
                
                output.close()
                input.close()
                
                Log.i(TAG, "✅ Download completed! File size: ${destinationFile.length()} bytes")
                
                // Verify file was downloaded correctly
                if (destinationFile.exists() && destinationFile.length() > 0) {
                    withContext(Dispatchers.Main) {
                        onProgress(100) // Complete
                        Log.i(TAG, "🎉 Starting APK installation...")
                        installApkDirect(destinationFile)
                    }
                } else {
                    throw Exception("Downloaded file is empty or doesn't exist")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "💥 Direct download failed: ${e.javaClass.simpleName}")
                Log.e(TAG, "💥 Error details: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onProgress(-1) // Signal error
                }
            }
        }
    }
    
    private fun installApkDirect(apkFile: File) {
        try {
            Log.d(TAG, "📦 Installing APK: ${apkFile.absolutePath}")
            Log.d(TAG, "📊 APK file size: ${apkFile.length()} bytes")
            
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }
            
            Log.d(TAG, "🔗 APK URI: $apkUri")
            
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            context.startActivity(installIntent)
            Log.i(TAG, "🚀 Installation intent launched successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error installing APK: ${e.javaClass.simpleName}", e)
            Log.e(TAG, "💥 Installation error: ${e.message}")
        }
    }
    
    private fun extractFileNameFromUrl(url: String): String? {
        return try {
            url.substringAfterLast("/")
        } catch (e: Exception) {
            null
        }
    }
    
    fun cleanup() {
        // Clean up any remaining resources
        downloadId = -1
    }
}