package com.example.onetap.api

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class DownloadRequest(
    val url: String
)

data class DownloadResponse(
    val status: String? = null,
    val message: String? = null,
    val filename: String? = null,
    val title: String? = null,
    val duration: Double? = null,
    @SerializedName("video_codec")
    val videoCodec: String? = null,
    @SerializedName("audio_codec")
    val audioCodec: String? = null,
    val container: String? = null,
    val method: String? = null,
    val error: String? = null,
    val type: String? = null,
    @SerializedName("total_images")
    val totalImages: Int? = null,
    val files: List<DownloadFile>? = null,
    val platform: String? = null,
    @SerializedName("download_url")
    val downloadUrl: String? = null
)

data class DownloadFile(
    val filename: String,
    @SerializedName("download_url")
    val downloadUrl: String,
    val type: String
)

data class VersionResponse(
    val version: String,
    @SerializedName("latest_version")
    val latestVersion: Int? = null,
    @SerializedName("apk_url")
    val apkUrl: String? = null,
    @SerializedName("release_notes")
    val releaseNotes: String? = null,
    val status: String,
    val service: String,
    val framework: String,
    val performance: String,
    @SerializedName("total_platforms")
    val totalPlatforms: Int,
    val features: List<String>,
    @SerializedName("supported_platforms")
    val supportedPlatforms: Map<String, List<String>>
)

interface VideoDownloadApi {
    @POST("/download")
    suspend fun downloadVideo(@Body request: DownloadRequest): Response<DownloadResponse>
    
    @GET("/")
    suspend fun getSystemInfo(): Response<VersionResponse>
}