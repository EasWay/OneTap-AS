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
    const val BASE_URL = "https://onetap-225t.onrender.com/"
    const val YOUTUBE_SERVER_URL = "https://youtube-server-2n1t.onrender.com/" // TODO: Update with actual URL
    
    /**
     * Security: Redact sensitive data from URLs before logging
     */
    private fun redactUrl(url: String): String {
        return url.replace(Regex("([?&])(token|key|auth|signature|sig)=[^&]*"), "$1$2=***")
            .replace(Regex("(access_token|api_key)=([^&]*)"), "$1=***")
    }
    
    /**
     * Security: Safe logging interceptor that redacts sensitive data
     */
    private fun createLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor { message ->
            // Redact sensitive information before logging
            val redacted = redactUrl(message)
            Log.d(TAG, "HTTP: $redacted")
        }.apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
    }
    
    // Optimized HTTP Client Configuration for regular API calls
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)  // Reduced from 30s
        .readTimeout(30, TimeUnit.SECONDS)     // Reduced from 60s  
        .writeTimeout(30, TimeUnit.SECONDS)    // Reduced from 60s
        .callTimeout(45, TimeUnit.SECONDS)     // Total call timeout
        .retryOnConnectionFailure(true)        // Auto retry failed connections
        .apply {
            // Only add logging in debug builds for maximum performance
            if (BuildConfig.ENABLE_LOGGING) {
                addInterceptor(createLoggingInterceptor())
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
    
    // Separate client for update checks with longer timeouts for Render cold starts
    val updateCheckClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)  // Long timeout for Render free tier
        .readTimeout(30, TimeUnit.SECONDS)     // Reasonable read timeout
        .writeTimeout(15, TimeUnit.SECONDS)    // Short write timeout
        .callTimeout(75, TimeUnit.SECONDS)     // Total timeout for cold starts
        .retryOnConnectionFailure(false)       // Don't retry update checks
        .apply {
            if (BuildConfig.ENABLE_LOGGING) {
                addInterceptor(createLoggingInterceptor())
            }
        }
        .build()
    
    // Specialized client for video API calls (getting download URLs) with Render cold start handling
    private val videoApiClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)  // Long timeout for Render free tier cold starts
        .readTimeout(30, TimeUnit.SECONDS)     // Reasonable read timeout for API responses
        .writeTimeout(15, TimeUnit.SECONDS)    // Short write timeout for API requests
        .callTimeout(75, TimeUnit.SECONDS)     // Total timeout for cold starts
        .retryOnConnectionFailure(false)       // Don't auto-retry, we'll handle retries manually
        .apply {
            if (BuildConfig.ENABLE_LOGGING) {
                addInterceptor(createLoggingInterceptor())
            }
        }
        .build()
    
    // Specialized client for YouTube server with extended timeouts for video processing
    private val youtubeApiClient = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)   // Extended connection timeout
        .readTimeout(300, TimeUnit.SECONDS)     // 5 minutes for video processing
        .writeTimeout(60, TimeUnit.SECONDS)     // Write timeout
        .callTimeout(0, TimeUnit.SECONDS)       // No total timeout - let it run as long as needed
        .retryOnConnectionFailure(false)        // Manual retry handling
        .apply {
            if (BuildConfig.ENABLE_LOGGING) {
                addInterceptor(createLoggingInterceptor())
            }
        }
        .build()
    
    init {
        Log.i(TAG, "ApiClient initialized with base URL: $BASE_URL")
        Log.i(TAG, "YouTube server URL: $YOUTUBE_SERVER_URL")
        Log.i(TAG, "Update check client configured for Render free tier cold starts")
        Log.i(TAG, "Video API client configured for Render free tier cold starts")
        Log.i(TAG, "YouTube API client configured with 5-minute read timeout and no call timeout limit")
    }
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    // Separate retrofit instance for video API calls with longer timeouts
    private val videoApiRetrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(videoApiClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    // Retrofit instance for YouTube server with extended timeouts
    private val youtubeServerRetrofit = Retrofit.Builder()
        .baseUrl(YOUTUBE_SERVER_URL)
        .client(youtubeApiClient)  // Use YouTube-specific client with longer timeouts
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    val videoDownloadApi: VideoDownloadApi = videoApiRetrofit.create(VideoDownloadApi::class.java)
    val youtubeDownloadApi: VideoDownloadApi = youtubeServerRetrofit.create(VideoDownloadApi::class.java)
}