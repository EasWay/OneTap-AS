package com.tapstream.downloader.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import androidx.core.app.ActivityCompat
import com.tapstream.downloader.repository.VideoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Keep
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val playStoreUrl: String? = null,
    val releaseNotes: String,
    val isUpdateAvailable: Boolean
)

@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoRepository: VideoRepository
) {
    private val TAG = "OneTap_UpdateManager"
    private val UPDATE_CHECK_URL = "https://onetap-225t.onrender.com/version"
    private val NOTIFICATION_CHANNEL_ID = "onetap_updates"

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
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    suspend fun checkForUpdates(retryCount: Int = 1): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val repositoryResult = checkForUpdatesUsingRepository(retryCount)
                if (repositoryResult != null) return@withContext repositoryResult
                return@withContext checkForUpdatesLegacy(retryCount)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during update check: ${e.message}")
                return@withContext null
            }
        }
    }

    private suspend fun checkForUpdatesUsingRepository(retryCount: Int = 1): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val systemInfo = videoRepository.getSystemInfo() ?: return@withContext null

                val serverVersionCode = systemInfo.latestVersion ?: parseVersionCode(systemInfo.version)
                val currentVersionCode = getCurrentVersionCode()
                val isUpdateAvailable = serverVersionCode > currentVersionCode

                val downloadUrl = systemInfo.apkUrl ?: "https://github.com/EasWay/OneTap-Releases/releases/latest"
                val releaseNotes = systemInfo.releaseNotes ?: "New version ${systemInfo.version} available with ${systemInfo.totalPlatforms} supported platforms"

                UpdateInfo(
                    versionCode = serverVersionCode,
                    versionName = systemInfo.version,
                    downloadUrl = downloadUrl,
                    playStoreUrl = systemInfo.playStoreUrl,
                    releaseNotes = releaseNotes,
                    isUpdateAvailable = isUpdateAvailable
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception during repository update check: ${e.message}")
                null
            }
        }
    }

    private fun parseVersionCode(version: String): Int {
        return try {
            val parts = version.split(".")
            when (parts.size) {
                1 -> parts[0].toInt() * 100
                2 -> parts[0].toInt() * 100 + parts[1].toInt() * 10
                else -> parts[0].toInt() * 100 + parts[1].toInt() * 10 + parts[2].toInt()
            }
        } catch (e: Exception) {
            1
        }
    }

    private suspend fun checkForUpdatesLegacy(retryCount: Int = 1): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            var lastException: Exception? = null

            repeat(retryCount) { attempt ->
                try {
                    val url = URL(UPDATE_CHECK_URL)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 45000
                    connection.readTimeout = 30000

                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().readText()
                        return@withContext parseUpdateResponse(response)
                    } else {
                        return@withContext null
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    lastException = e
                    if (attempt < retryCount - 1) kotlinx.coroutines.delay(5000)
                } catch (e: Exception) {
                    lastException = e
                    if (attempt < retryCount - 1) kotlinx.coroutines.delay(3000)
                }
            }

            Log.e(TAG, "Update check failed after $retryCount attempts: ${lastException?.message}")
            null
        }
    }

    private fun parseUpdateResponse(response: String): UpdateInfo? {
        return try {
            val json = JSONObject(response)
            val latestVersionCode = json.optInt("latest_version", 0)
            val versionString = json.optString("version", "1.0.0")
            val downloadUrl = json.optString("apk_url", "")
            val playStoreUrl = json.optString("play_store_url", "")
            val releaseNotes = json.optString("release_notes", "Bug fixes and improvements")
            val finalVersionCode = if (latestVersionCode > 0) latestVersionCode else parseVersionCode(versionString)
            val isUpdateAvailable = finalVersionCode > getCurrentVersionCode()

            UpdateInfo(
                versionCode = finalVersionCode,
                versionName = versionString,
                downloadUrl = downloadUrl,
                playStoreUrl = if (playStoreUrl.isNotEmpty()) playStoreUrl else null,
                releaseNotes = releaseNotes,
                isUpdateAvailable = isUpdateAvailable
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing update response: ${e.message}")
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
            1
        }
    }

    fun cleanup() {}
}
