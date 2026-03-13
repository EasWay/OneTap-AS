package com.tapstream.downloader.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class TurnstileSession(
    val csrfToken: String,
    val cookieString: String,
    val userAgent: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Singleton
class TurnstileBypassProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "TurnstileBypass"
    private val J2_URL = "https://j2download.com"
    private val SPOOF_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
    
    // Cache the session for 20 minutes
    private var cachedSession: TurnstileSession? = null
    private val CACHE_DURATION = 20 * 60 * 1000L

    /**
     * Get a valid Turnstile session, either from cache or by spawning a WebView.
     */
    suspend fun getSession(): TurnstileSession? = withContext(Dispatchers.Main) {
        // 1. Check Cache
        cachedSession?.let {
            if (System.currentTimeMillis() - it.timestamp < CACHE_DURATION) {
                Log.i(TAG, "🚀 Using cached Turnstile session")
                return@withContext it
            }
        }

        Log.i(TAG, "🌐 Initializing Turnstile bypass WebView...")
        
        // 2. Spawn WebView with timeout
        val session = withTimeoutOrNull(15000) { // 15s total timeout
            interceptSession()
        }

        if (session != null) {
            Log.i(TAG, "✅ Turnstile Success! Session captured.")
            cachedSession = session
        } else {
            Log.e(TAG, "❌ Turnstile Bypass Failed (Timeout or Error)")
        }

        return@withContext session
    }

    /**
     * Clear the cache if the backend reports an expired token.
     */
    fun clearCache() {
        Log.w(TAG, "🧹 Clearing Turnstile session cache")
        cachedSession = null
    }

    private suspend fun interceptSession(): TurnstileSession? = withContext(Dispatchers.Main) {
        val deferredToken = CompletableDeferred<TurnstileSession?>()
        var webView: WebView? = null

        try {
            webView = WebView(context).apply {
                // Configure 1dp size but keeps it active
                layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                alpha = 0.01f
                
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = SPOOF_UA
                    // Disable cache for the intercept to ensure a fresh session if needed
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d(TAG, "📄 Page loaded: $url")
                        
                        // Wait for Turnstile challenge to complete
                        Handler(Looper.getMainLooper()).postDelayed({
                            val cookieManager = CookieManager.getInstance()
                            val cookies = cookieManager.getCookie(J2_URL)
                            
                            if (cookies != null && cookies.contains("csrf_token")) {
                                val token = extractCsrfToken(cookies)
                                if (token != null) {
                                    deferredToken.complete(TurnstileSession(
                                        csrfToken = token,
                                        cookieString = cookies,
                                        userAgent = SPOOF_UA
                                    ))
                                } else {
                                    Log.w(TAG, "⚠️ cookies found but csrf_token extraction failed")
                                }
                            } else {
                                Log.d(TAG, "⏳ Waiting for challenge... cookies: $cookies")
                            }
                        }, 4000) // Give it 4 seconds to solve
                    }
                }
            }

            webView.loadUrl(J2_URL)
            
            // Return the deferred result
            val result = deferredToken.await()
            return@withContext result

        } catch (e: Exception) {
            Log.e(TAG, "💥 WebView Error: ${e.message}")
            return@withContext null
        } finally {
            // Cleanup
            webView?.let {
                it.stopLoading()
                it.destroy()
                Log.d(TAG, "🗑️ WebView destroyed")
            }
        }
    }

    private fun extractCsrfToken(cookies: String): String? {
        // Pattern: csrf_token=XYZ; or at the end
        val match = Regex("csrf_token=([^;]+)").find(cookies)
        return match?.groupValues?.get(1)
    }
}
