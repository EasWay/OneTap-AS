package com.example.onetap.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
    
    private var downloadReceiver: BroadcastReceiver? = null
    
    suspend fun checkForUpdates(): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(UPDATE_CHECK_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().readText()
                    parseUpdateResponse(response)
                } else {
                    Log.e(TAG, "Update check failed with response code: $responseCode")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates", e)
                null
            }
        }
    }
    
    private fun parseUpdateResponse(response: String): UpdateInfo? {
        return try {
            val json = JSONObject(response)
            val latestVersionCode = json.getInt("latest_version")
            val downloadUrl = json.getString("apk_url")
            val releaseNotes = json.optString("release_notes", "Bug fixes and improvements")
            
            val currentVersionCode = getCurrentVersionCode()
            val isUpdateAvailable = latestVersionCode > currentVersionCode
            
            // Extract version name from download URL or use a default format
            val versionName = extractVersionFromUrl(downloadUrl) ?: "v$latestVersionCode"
            
            UpdateInfo(
                versionCode = latestVersionCode,
                versionName = versionName,
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes,
                isUpdateAvailable = isUpdateAvailable
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing update response", e)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current version code", e)
            1
        }
    }
    
    fun downloadAndInstallUpdate(updateInfo: UpdateInfo, onProgress: (Int) -> Unit = {}) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            
            // Extract filename from URL or create a default one
            val fileName = extractFileNameFromUrl(updateInfo.downloadUrl) 
                ?: "OneTap_${updateInfo.versionName}.apk"
            
            val request = DownloadManager.Request(Uri.parse(updateInfo.downloadUrl)).apply {
                setTitle("OneTap Update")
                setDescription("Downloading OneTap ${updateInfo.versionName}")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                setAllowedOverRoaming(false)
            }
            
            val downloadId = downloadManager.enqueue(request)
            Log.d(TAG, "Started download with ID: $downloadId")
            
            // Register receiver to handle download completion
            downloadReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        Log.d(TAG, "Download completed for ID: $downloadId")
                        
                        // Check if download was successful
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        
                        if (cursor.moveToFirst()) {
                            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                installApk(updateInfo, fileName)
                            } else {
                                Log.e(TAG, "Download failed with status: $status")
                            }
                        }
                        cursor.close()
                        
                        context?.unregisterReceiver(this)
                        downloadReceiver = null
                    }
                }
            }
            
            context.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading update", e)
        }
    }
    
    private fun extractFileNameFromUrl(url: String): String? {
        return try {
            url.substringAfterLast("/")
        } catch (e: Exception) {
            null
        }
    }
    
    private fun installApk(updateInfo: UpdateInfo, fileName: String) {
        try {
            val apkFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            
            if (!apkFile.exists()) {
                Log.e(TAG, "APK file not found: ${apkFile.absolutePath}")
                return
            }
            
            Log.d(TAG, "Installing APK: ${apkFile.absolutePath}")
            
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }
            
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            context.startActivity(installIntent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK", e)
        }
    }
    
    fun cleanup() {
        downloadReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering download receiver", e)
            }
            downloadReceiver = null
        }
    }
}