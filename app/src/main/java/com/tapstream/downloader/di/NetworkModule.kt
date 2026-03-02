package com.tapstream.downloader.di

import com.tapstream.downloader.BuildConfig
import com.tapstream.downloader.api.VideoDownloadApi
import com.tapstream.downloader.network.ApiClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifiers to distinguish different OkHttpClient configurations
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VideoApiHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class YoutubeApiHttpClient

/**
 * Network Module - Single Source of Truth for HTTP clients
 * 
 * This module provides singleton OkHttpClient instances to eliminate:
 * - Multiple connection pools (memory leak)
 * - Lost TCP Keep-Alive benefits
 * - Redundant DNS lookups and TLS handshakes
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    /**
     * Shared connection pool for all clients
     * 5 connections, 5 minute keep-alive
     */
    @Provides
    @Singleton
    fun provideConnectionPool(): ConnectionPool {
        return ConnectionPool(
            maxIdleConnections = 5,
            keepAliveDuration = 5,
            timeUnit = TimeUnit.MINUTES
        )
    }
    
    /**
     * Logging interceptor (only in debug builds)
     */
    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor { message ->
            // Redact sensitive data
            val redacted = message.replace(
                Regex("([?&])(token|key|auth|signature|sig)=[^&]*"),
                "$1$2=***"
            )
            android.util.Log.d("OneTap_HTTP", redacted)
        }.apply {
            level = if (BuildConfig.ENABLE_LOGGING) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }
    
    /**
     * Standard API client for regular API calls
     */
    @Provides
    @Singleton
    @ApiHttpClient
    fun provideApiHttpClient(
        connectionPool: ConnectionPool,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(loggingInterceptor)
            .build()
    }
    
    /**
     * Optimized client for file downloads
     * Extended connect timeout for Render cold starts
     * No timeouts for large file transfers
     */
    @Provides
    @Singleton
    @DownloadHttpClient
    fun provideDownloadHttpClient(
        connectionPool: ConnectionPool
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .connectTimeout(60, TimeUnit.SECONDS)  // Extended for Render cold start
            .readTimeout(0, TimeUnit.SECONDS)  // No timeout for large files
            .writeTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)  // Retry on connection failure
            .build()
    }
    
    /**
     * Client for video API calls with Render cold start handling
     */
    @Provides
    @Singleton
    @VideoApiHttpClient
    fun provideVideoApiHttpClient(
        connectionPool: ConnectionPool,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .addInterceptor(loggingInterceptor)
            .build()
    }
    
    /**
     * Client for YouTube server with extended timeouts
     */
    @Provides
    @Singleton
    @YoutubeApiHttpClient
    fun provideYoutubeApiHttpClient(
        connectionPool: ConnectionPool,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .connectTimeout(90, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .addInterceptor(loggingInterceptor)
            .build()
    }
    
    /**
     * Main server Retrofit instance
     */
    @Provides
    @Singleton
    fun provideMainRetrofit(
        @VideoApiHttpClient client: OkHttpClient
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(ApiClient.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    /**
     * YouTube server Retrofit instance
     */
    @Provides
    @Singleton
    @Named("youtube")
    fun provideYoutubeRetrofit(
        @YoutubeApiHttpClient client: OkHttpClient
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(ApiClient.YOUTUBE_SERVER_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    /**
     * Main video download API
     */
    @Provides
    @Singleton
    fun provideVideoDownloadApi(
        retrofit: Retrofit
    ): VideoDownloadApi {
        return retrofit.create(VideoDownloadApi::class.java)
    }
    
    /**
     * YouTube video download API (separate server)
     */
    @Provides
    @Singleton
    @Named("youtube")
    fun provideYoutubeDownloadApi(
        @Named("youtube") retrofit: Retrofit
    ): VideoDownloadApi {
        return retrofit.create(VideoDownloadApi::class.java)
    }
}
