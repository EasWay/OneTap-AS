package com.example.onetap.test

import android.util.Log
import com.example.onetap.api.DownloadRequest
import com.example.onetap.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ApiTest {
    private const val TAG = "OneTap_ApiTest"
    
    fun testApiConnection() {
        Log.i(TAG, "Starting API connection test")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Testing with sample YouTube URL")
                val testRequest = DownloadRequest(url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                Log.d(TAG, "Test request created: $testRequest")
                
                val response = ApiClient.videoDownloadApi.downloadVideo(testRequest)
                Log.i(TAG, "API test successful: $response")
                
            } catch (e: Exception) {
                Log.e(TAG, "API test failed", e)
            }
        }
    }
}