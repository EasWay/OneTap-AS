package com.tapstream.downloader.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TurnstileBypass"

@Singleton
class TurnstileBypassProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var webView: WebView? = null
    private val capturedJwt = AtomicReference<String?>(null)
    private var lastUserAgent = ""
    private var currentVideoUrl = ""

    /**
     * Obtains a valid J2Session by solving the Turnstile challenge in a WebView.
     * Uses a Native Hijack strategy to capture cryptographic headers and fetch JWT via OkHttp.
     * @param videoUrl The target video URL to trigger J2Download's processing.
     */
    suspend fun getSession(videoUrl: String): J2Session? = withContext(Dispatchers.Main) {
        capturedJwt.set(null)
        currentVideoUrl = videoUrl
        
        Log.i(TAG, "🌐 Initializing Turnstile bypass WebView (Auto-Clicker Mode)...")
        
        initializeWebView()
        Log.i(TAG, "🚀 Loading Base URL for Auto-Clicker...")
        webView?.loadUrl("https://j2download.com/")

        // Wait for JWT with 60s timeout
        withTimeoutOrNull(60_000) {
            var attempts = 0
            while (capturedJwt.get() == null) {
                attempts++
                if (attempts % 5 == 0) {
                    Log.d(TAG, "⏳ Polling for JWT... (${attempts} polls)")
                }
                
                // Potential safety reload if cookies exist but JWT is missing
                if (attempts == 30) {
                    val cookies = CookieManager.getInstance().getCookie("https://j2download.com")
                    if (cookies?.contains("jx_session") == true) {
                        Log.w(TAG, "⚠️ Session cookie found but no JWT. Forcing reload...")
                        webView?.reload()
                    }
                }
                
                delay(1000)
            }
        }

        val jwt = capturedJwt.get()
        if (jwt != null) {
            val cookies = CookieManager.getInstance().getCookie("https://j2download.com") ?: ""
            Log.i(TAG, "✅ Turnstile Success! J2Session captured.")
            cleanup()
            return@withContext J2Session(
                jwtAccessToken = jwt,
                cookieString = cookies,
                userAgent = lastUserAgent
            )
        }

        Log.e(TAG, "❌ Turnstile Bypass Timeout (No JWT captured)")
        cleanup()
        null
    }

    /**
     * Clears internal state and session cookies.
     */
    fun clearCache() {
        Log.d(TAG, "🧹 Clearing captured JWT and session state...")
        capturedJwt.set(null)
        CookieManager.getInstance().removeAllCookies(null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initializeWebView() {
        if (webView != null) cleanup()

        webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                // Use a more realistic Mobile Chrome User-Agent
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                lastUserAgent = userAgentString
            }

            // High priority: Hijack the network request at the OS layer
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                    val method = request.method ?: "GET"
                    val headers = request.requestHeaders ?: emptyMap()

                    // 🔍 ULTRA-VERBOSE: Log every single request to find where it's stalling
                    Log.d(TAG, "📡 Intercepting: [$method] ${url.take(150)}")

                    // 🚨 NATIVE HIJACK: Catch the authentication request
                    if (url.contains("auth/issue") || url.contains("issue")) {
                        Log.i(TAG, "🚨 HANDSHAKE DETECTED: $url")
                        
                        val nonce = headers.entries.find { it.key.equals("x-page-nonce", ignoreCase = true) }?.value
                        val pow = headers.entries.find { it.key.equals("x-pow-solution", ignoreCase = true) }?.value

                        if (nonce != null && pow != null) {
                            Log.i(TAG, "🔑 Stole PoW Headers! Nonce: $nonce, PoW: $pow")
                            fetchJwtNatively(url, headers)
                            val emptyStream = ByteArrayInputStream("{}".toByteArray())
                            return WebResourceResponse("application/json", "UTF-8", emptyStream)
                        } else {
                            Log.w(TAG, "⚠️ Handshake found but headers missing! Header keys: ${headers.keys}")
                        }
                    }

                    // Block ads but be MORE PERMISSIVE during debugging
                    if (!isDomainAllowed(url)) {
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("".toByteArray()))
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    Log.d(TAG, "📄 Page started: $url")
                    // No more JS injection needed! The hijack happens natively.
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "📄 Page loaded: $url")
                    
                    if (url == "https://j2download.com/") {
                        Log.d(TAG, "🤖 Injecting Auto-Clicker script for: $currentVideoUrl")
                        // Inject JS to fill the input and click the submit button
                        val jsClicker = """
                            setTimeout(function() {
                                var input = document.getElementById('url');
                                var btn = document.querySelector('button[type="submit"]');
                                
                                if (input && btn) {
                                    console.log('🤖 Auto-clicker: Filling URL and clicking download...');
                                    // 1. Set the value
                                    input.value = '$currentVideoUrl';
                                    
                                    // 2. Dispatch an 'input' event so the Vue.js frontend registers the change
                                    input.dispatchEvent(new Event('input', { bubbles: true }));
                                    
                                    // 3. Click the download button
                                    btn.click();
                                } else {
                                    console.log('❌ Auto-clicker failed: Elements not found.');
                                }
                            }, 1500); // Wait 1.5 seconds for Vue to fully mount
                        """.trimIndent()
                        
                        view?.evaluateJavascript(jsClicker, null)
                    }
                }
            }

            // Layout Params for "semi-visible" state to avoid bot detection
            layoutParams = ViewGroup.LayoutParams(100, 100)
            alpha = 0.1f
        }
    }

    private fun fetchJwtNatively(url: String, headers: Map<String, String>) {
        // Launch on IO thread so we don't block the WebView's thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val cookieManager = CookieManager.getInstance()
                val cookies = cookieManager.getCookie("https://j2download.com") ?: ""

                // Rebuild the request exactly as the browser intended
                val requestBuilder = Request.Builder()
                    .url(url)
                    .post("".toRequestBody()) // Empty body, just like the browser
                    .addHeader("Cookie", cookies)
                
                // Add all the intercepted headers (User-Agent, Nonce, PoW, etc.)
                for ((key, value) in headers) {
                    if (!key.equals("Cookie", ignoreCase = true)) {
                        requestBuilder.addHeader(key, value)
                    }
                }

                Log.d(TAG, "📡 Sending native HTTP POST to /api/auth/issue...")
                val response = client.newCall(requestBuilder.build()).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val jwt = json.optString("accessToken")
                    
                    if (jwt.isNotEmpty()) {
                        Log.i(TAG, "✅ Native Hijack Success! JWT obtained.")
                        capturedJwt.set(jwt)
                    } else {
                        Log.e(TAG, "❌ JWT missing from native response: $responseBody")
                    }
                } else {
                    Log.e(TAG, "❌ Native request failed: ${response.code} - ${response.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "💥 Native request crashed: ${e.message}")
            }
        }
    }

    private fun isDomainAllowed(url: String): Boolean {
        val allowedHosts = listOf(
            "j2download.com",
            "cloudflare.com",
            "challenges.cloudflare.com",
            "cloudflareinsights.com",
            "cloudflare-eth.com",
            "gstatic.com",
            "google.com",
            "googleapis.com"
        )
        return try {
            val host = java.net.URL(url).host.lowercase()
            allowedHosts.any { host.contains(it) }
        } catch (e: Exception) {
            false
        }
    }

    private fun cleanup() {
        Handler(Looper.getMainLooper()).post {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }
}