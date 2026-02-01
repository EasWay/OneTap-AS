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
import com.example.onetap.repository.VideoRepository
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
    private val videoRepository = VideoRepository()
    
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
    
    suspend fun checkForUpdates(retryCount: Int = 1): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "🔍 Starting update check with VideoRepository (primary method)...")
                
                // Try the new repository-based method first
                val repositoryResult = checkForUpdatesUsingRepository(retryCount)
                if (repositoryResult != null) {
                    Log.i(TAG, "✅ Repository-based update check successful")
                    return@withContext repositoryResult
                }
                
                Log.w(TAG, "⚠️ Repository method failed, falling back to direct HTTP method...")
                
                // Fallback to the original HTTP method
                return@withContext checkForUpdatesLegacy(retryCount)
                
            } catch (e: Exception) {
                Log.e(TAG, "💥 Exception during update check: ${e.javaClass.simpleName}")
                Log.e(TAG, "💥 Exception message: ${e.message}")
                return@withContext null
            }
        }
    }
    
    private suspend fun checkForUpdatesUsingRepository(retryCount: Int = 1): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "🔍 Starting update check using VideoRepository...")
                
                // Use the VideoRepository to get system info
                val systemInfo = videoRepository.getSystemInfo()
                
                if (systemInfo != null) {
                    Log.i(TAG, "✅ System info retrieved successfully")
                    Log.d(TAG, "� Server ver sion: ${systemInfo.version}")
                    Log.d(TAG, "🌐 Total platforms: ${systemInfo.totalPlatforms}")
                    
                    // Use server-provided version code if available, otherwise parse from version string
                    val serverVersionCode = systemInfo.latestVersion ?: parseVersionCode(systemInfo.version)
                    val currentVersionCode = getCurrentVersionCode()
                    
                    Log.d(TAG, "📱 Current app version: $currentVersionCode")
                    Log.d(TAG, "🆕 Server version code: $serverVersionCode")
                    
                    val isUpdateAvailable = serverVersionCode > currentVersionCode
                    
                    // Use server-provided APK URL and release notes
                    val downloadUrl = systemInfo.apkUrl ?: "https://github.com/YourUsername/OneTap/releases/latest"
                    val releaseNotes = systemInfo.releaseNotes ?: "New version ${systemInfo.version} available with ${systemInfo.totalPlatforms} supported platforms"
                    
                    val updateInfo = UpdateInfo(
                        versionCode = serverVersionCode,
                        versionName = systemInfo.version,
                        downloadUrl = downloadUrl,
                        releaseNotes = releaseNotes,
                        isUpdateAvailable = isUpdateAvailable
                    )
                    
                    if (isUpdateAvailable) {
                        Log.i(TAG, "🚀 UPDATE AVAILABLE! Server version ${systemInfo.version}")
                        Log.d(TAG, "📥 Download URL: $downloadUrl")
                        Log.d(TAG, "📝 Release notes: ${releaseNotes.take(100)}...")
                    } else {
                        Log.i(TAG, "✨ App is up to date with server version ${systemInfo.version}")
                    }
                    
                    return@withContext updateInfo
                } else {
                    Log.w(TAG, "⚠️ Failed to get system info from VideoRepository")
                    return@withContext null
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "💥 Exception during repository update check: ${e.javaClass.simpleName}")
                Log.e(TAG, "💥 Exception message: ${e.message}")
                return@withContext null
            }
        }
    }
    
    private fun parseVersionCode(version: String): Int {
        return try {
            // Extract version code from version string like "4.0.0" -> 400
            val parts = version.split(".")
            when (parts.size) {
                1 -> parts[0].toInt() * 100 // "4" -> 400
                2 -> parts[0].toInt() * 100 + parts[1].toInt() * 10 // "4.0" -> 400
                3 -> parts[0].toInt() * 100 + parts[1].toInt() * 10 + parts[2].toInt() // "4.0.0" -> 400
                else -> {
                    // Take first 3 parts for complex versions
                    parts[0].toInt() * 100 + parts[1].toInt() * 10 + parts[2].toInt()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to parse version code from '$version', defaulting to 1")
            1
        }
    }
    
    private suspend fun checkForUpdatesLegacy(retryCount: Int = 1): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            var lastException: Exception? = null
            
            repeat(retryCount) { attempt ->
                try {
                    Log.i(TAG, "🔍 Starting update check (attempt ${attempt + 1}/$retryCount)...")
                    Log.d(TAG, "📡 Contacting server: $UPDATE_CHECK_URL")
                    
                    val url = URL(UPDATE_CHECK_URL)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    // Increased timeouts for Render free tier cold starts
                    connection.connectTimeout = 45000  // 45 seconds for connection
                    connection.readTimeout = 30000     // 30 seconds for reading
                    
                    Log.d(TAG, "⏱️ Connection timeout: 45s, Read timeout: 30s (optimized for Render free tier)")
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
                        
                        // Don't retry on HTTP errors, return null immediately
                        return@withContext null
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    lastException = e
                    Log.e(TAG, "⏰ TIMEOUT (attempt ${attempt + 1}/$retryCount): Server took too long to respond")
                    Log.e(TAG, "💡 This is normal for Render free tier - server needs time to wake up")
                    
                    if (attempt < retryCount - 1) {
                        Log.i(TAG, "🔄 Retrying in 5 seconds...")
                        kotlinx.coroutines.delay(5000) // Wait 5 seconds before retry
                    } else {
                        Log.e(TAG, "❌ All retry attempts exhausted")
                    }
                } catch (e: Exception) {
                    lastException = e
                    Log.e(TAG, "💥 Exception during update check (attempt ${attempt + 1}/$retryCount): ${e.javaClass.simpleName}")
                    Log.e(TAG, "💥 Exception message: ${e.message}")
                    
                    if (attempt < retryCount - 1) {
                        Log.i(TAG, "🔄 Retrying in 3 seconds...")
                        kotlinx.coroutines.delay(3000) // Wait 3 seconds before retry
                    } else {
                        Log.e(TAG, "💥 Exception details: ", e)
                    }
                }
            }
            
            // If we get here, all attempts failed
            Log.e(TAG, "💥 Update check failed after $retryCount attempts")
            lastException?.let { 
                Log.e(TAG, "💥 Last error: ${it.message}")
            }
            null
        }
    }
    
    private fun parseUpdateResponse(response: String): UpdateInfo? {
        return try {
            Log.d(TAG, "🔧 Parsing JSON response...")
            val json = JSONObject(response)
            
            val latestVersionCode = json.optInt("latest_version", 0)
            val versionString = json.optString("version", "1.0.0")
            val downloadUrl = json.optString("apk_url", "")
            val releaseNotes = json.optString("release_notes", "Bug fixes and improvements")
            
            // If latest_version is not provided, parse from version string
            val finalVersionCode = if (latestVersionCode > 0) latestVersionCode else parseVersionCode(versionString)
            
            Log.d(TAG, "📊 Parsed - Version: $finalVersionCode, URL: $downloadUrl")
            Log.d(TAG, "📝 Release notes: ${releaseNotes.take(100)}...")
            
            val currentVersionCode = getCurrentVersionCode()
            val isUpdateAvailable = finalVersionCode > currentVersionCode
            
            Log.d(TAG, "🔍 Version comparison: Current=$currentVersionCode, Latest=$finalVersionCode")
            
            val updateInfo = UpdateInfo(
                versionCode = finalVersionCode,
                versionName = versionString,
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
            Log.i(TAG, "� Starting dire ct download...")
            Log.d(TAG, "� DownEload URL: ${updateInfo.downloadUrl}")
            
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
            Log.e(TAG, "� Erroer starting direct download: ${e.javaClass.simpleName}")
            Log.e(TAG, "� Downlsoad error message: ${e.message}", e)
            onProgress(-1) // Signal error
        }
    }
    
    private fun startDirectDownload(updateInfo: UpdateInfo, onProgress: (Int) -> Unit) {
        // Use a separate coroutine scope for the download with proper timeout handling
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "🌐 Starting direct stream download...")
                
                // Create destination file in cache directory (no permissions needed)
                val fileName = extractFileNameFromUrl(updateInfo.downloadUrl) 
                    ?: "OneTap_${updateInfo.versionName}.apk"
                val destinationFile = File(context.externalCacheDir, fileName)
                
                Log.d(TAG, "📁 Download destination: ${destinationFile.absolutePath}")
                
                // Open connection to the APK URL with longer timeouts for large files
                val url = URL(updateInfo.downloadUrl)
                val connection = url.openConnection()
                connection.connectTimeout = 30000  // 30 seconds for connection
                connection.readTimeout = 60000     // 60 seconds for reading (large APK files)
                connection.setRequestProperty("User-Agent", "OneTap-UpdateManager/1.0")
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive")
                
                Log.d(TAG, "🔗 Connecting to: ${updateInfo.downloadUrl}")
                Log.d(TAG, "⏱️ Download timeouts - Connect: 30s, Read: 60s")
                connection.connect()
                
                val contentLength = connection.contentLength
                Log.i(TAG, "📊 File size: ${if (contentLength > 0) "${contentLength / 1024}KB" else "Unknown"}")
                
                // Stream the data directly
                val input = connection.getInputStream()
                val output = destinationFile.outputStream()
                
                val buffer = ByteArray(16384) // 16KB buffer for better performance with large files
                var totalBytesRead = 0
                var bytesRead: Int
                var lastProgressUpdate = System.currentTimeMillis()
                
                Log.i(TAG, "📥 Starting data transfer...")
                
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    
                    // Calculate and report progress (throttle updates to avoid UI spam)
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastProgressUpdate > 500) { // Update every 500ms
                        if (contentLength > 0) {
                            val progress = ((totalBytesRead * 100) / contentLength)
                            withContext(Dispatchers.Main) {
                                onProgress(progress)
                            }
                            Log.d(TAG, "📈 Download progress: $progress% ($totalBytesRead/$contentLength bytes)")
                        } else {
                            // Unknown file size, just show bytes downloaded
                            Log.d(TAG, "📈 Downloaded: ${totalBytesRead / 1024}KB")
                            withContext(Dispatchers.Main) {
                                onProgress(50) // Show 50% for unknown size
                            }
                        }
                        lastProgressUpdate = currentTime
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
                
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "⏰ Download timeout: ${e.message}")
                Log.e(TAG, "💡 Try downloading again - large APK files may take time")
                withContext(Dispatchers.Main) {
                    onProgress(-1) // Signal timeout error
                }
            } catch (e: Exception) {
                Log.e(TAG, "💥 Direct download failed: ${e.javaClass.simpleName}")
                Log.e(TAG, "� Erroro details: ${e.message}", e)
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