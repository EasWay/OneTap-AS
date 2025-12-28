package com.example.onetap.api

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

@Keep
data class DownloadRequest(
    val url: String
)

@Keep
data class DownloadResponse(
    val success: Boolean? = null,
    @SerializedName("download_url")
    val downloadUrl: String? = null,
    val message: String? = null,
    val error: String? = null
)

@Keep
interface VideoDownloadApi {
    @POST("/download")
    suspend fun downloadVideo(@Body request: DownloadRequest): Response<DownloadResponse>
}