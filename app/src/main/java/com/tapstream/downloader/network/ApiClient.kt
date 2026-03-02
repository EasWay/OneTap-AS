package com.tapstream.downloader.network

/**
 * ApiClient - Server URL Constants
 * 
 * This object now only contains server URL constants.
 * All HTTP client and Retrofit instances are managed by NetworkModule with Dependency Injection.
 * 
 * @see com.tapstream.downloader.di.NetworkModule for HTTP client configuration
 */
object ApiClient {
    const val BASE_URL = "https://onetap-225t.onrender.com/"
    const val YOUTUBE_SERVER_URL = "https://youtube-server-k9cd.onrender.com/"
}