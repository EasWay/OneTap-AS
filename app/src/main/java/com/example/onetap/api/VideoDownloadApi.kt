package com.example.onetap.api

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

data class DownloadRequest(
    val url: String
)

data class DownloadResponse(
    val success: Boolean? = null,
    @SerializedName("download_url")
    val downloadUrl: String? = null,
    val message: String? = null,
    val error: String? = null
)

interface VideoDownloadApi {
    @POST("/download")
    suspend fun downloadVideo(@Body request: DownloadRequest): Response<DownloadResponse>
}