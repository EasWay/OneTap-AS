package com.example.onetap.network

import android.util.Log
import com.example.onetap.BuildConfig
import com.example.onetap.api.VideoDownloadApi
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val TAG = "OneTap_ApiClient"
    private const val BASE_URL = "https://onetap-225t.onrender.com/"
    
    // Optimized HTTP Client Configuration
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)  // Reduced from 30s
        .readTimeout(30, TimeUnit.SECONDS)     // Reduced from 60s  
        .writeTimeout(30, TimeUnit.SECONDS)    // Reduced from 60s
        .callTimeout(45, TimeUnit.SECONDS)     // Total call timeout
        .retryOnConnectionFailure(true)        // Auto retry failed connections
        .apply {
            // Only add logging in debug builds for maximum performance
            if (BuildConfig.ENABLE_LOGGING) {
                addInterceptor(HttpLoggingInterceptor { message ->
                    Log.d(TAG, "HTTP: $message")
                }.apply {
                    level = HttpLoggingInterceptor.Level.BASIC  // Reduced logging overhead
                })
            }
        }
        .build()
    
    // Separate optimized client for video downloads
    val downloadClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)   // Ultra fast connection
        .readTimeout(0, TimeUnit.SECONDS)      // No read timeout for large files
        .writeTimeout(0, TimeUnit.SECONDS)     // No write timeout
        .callTimeout(0, TimeUnit.SECONDS)      // No total timeout
        .retryOnConnectionFailure(false)       // Don't retry downloads
        .build()
    
    init {
        Log.i(TAG, "ApiClient initialized with base URL: $BASE_URL")
    }
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    val videoDownloadApi: VideoDownloadApi = retrofit.create(VideoDownloadApi::class.java)
}