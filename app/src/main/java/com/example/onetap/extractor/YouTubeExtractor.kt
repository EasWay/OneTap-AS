package com.example.onetap.extractor

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * YouTube Extractor using J2Download API
 * Ported from Python server implementation to avoid IP-locking issues
 */
class YouTubeExtractor {
    
    companion object {
        private const val TAG = "OneTap_YouTubeExtractor"
        private const val J2_BASE_URL = "https://j2download.com"
        private const val J2_API_URL = "$J2_BASE_URL/api/autolink"
        private const val J2_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    
    private val gson = Gson()
    
    /**
     * Extract YouTube video download URL using J2Download API
     */
    suspend fun extractDownloadUrl(youtubeUrl: String): ExtractionResult? = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "🎯 Starting YouTube extraction for: ${youtubeUrl.take(50)}...")
            
            // Step 1: Get CSRF token and cookies from homepage
            val (csrfToken, cookies) = getCsrfTokenAndCookies() ?: run {
                Log.e(TAG, "❌ Failed to get CSRF token")
                return@withContext null
            }
            
            Log.i(TAG, "✅ CSRF token acquired: ${csrfToken.take(10)}...")
            
            // Step 2: Call J2Download API with the token and cookies
            val result = callJ2Api(youtubeUrl, csrfToken, cookies)
            
            if (result != null) {
                Log.i(TAG, "✅ YouTube extraction successful!")
                Log.d(TAG, "   - Title: ${result.title}")
                Log.d(TAG, "   - URL: ${result.url.take(100)}...")
                Log.d(TAG, "   - Extension: ${result.extension}")
            } else {
                Log.e(TAG, "❌ YouTube extraction failed")
            }
            
            return@withContext result
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ YouTube extraction error: ${e.message}", e)
            return@withContext null
        }
    }
    
    /**
     * Get CSRF token and cookies from J2Download homepage
     */
    private fun getCsrfTokenAndCookies(): Pair<String, String>? {
        try {
            val request = Request.Builder()
                .url(J2_BASE_URL)
                .header("User-Agent", J2_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Ch-Ua", "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Google Chrome\";v=\"144\"")
                .header("Sec-Ch-Ua-Mobile", "?0")
                .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .build()
            
            Log.d(TAG, "🤝 Performing J2Download handshake...")
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.w(TAG, "⚠️ Handshake returned status ${response.code}")
                return null
            }
            
            val html = response.body?.string() ?: return null
            val cookieHeaders = response.headers("Set-Cookie")
            
            // Build cookie string from all Set-Cookie headers
            val cookieMap = mutableMapOf<String, String>()
            for (cookie in cookieHeaders) {
                val parts = cookie.split(";")[0].split("=", limit = 2)
                if (parts.size == 2) {
                    cookieMap[parts[0].trim()] = parts[1].trim()
                }
            }
            
            val cookieString = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            var csrfToken: String? = null
            
            // Priority 1: Check cookies for csrf_token
            csrfToken = cookieMap["csrf_token"]
            if (csrfToken != null) {
                Log.i(TAG, "✅ Found CSRF token in cookies")
                return Pair(csrfToken, cookieString)
            }
            
            // Priority 2: HTML Meta Tags
            val metaPattern = Regex("""<meta\s+name="csrf-token"\s+content="([^"]+)"""")
            metaPattern.find(html)?.let {
                Log.i(TAG, "✅ Found CSRF token in Meta Tag")
                csrfToken = it.groupValues[1]
                return Pair(csrfToken!!, cookieString)
            }
            
            // Priority 3: JavaScript Variables
            val jsPattern = Regex("""csrf_token\s*=\s*['"]([^'"]+)['"]""")
            jsPattern.find(html)?.let {
                Log.i(TAG, "✅ Found CSRF token in JS")
                csrfToken = it.groupValues[1]
                return Pair(csrfToken!!, cookieString)
            }
            
            // Priority 4: Laravel Token Pattern
            val laravelPattern = Regex("""_token['"']?\s*:\s*['"]([^'"]+)['"]""")
            laravelPattern.find(html)?.let {
                Log.i(TAG, "✅ Found Laravel token")
                csrfToken = it.groupValues[1]
                return Pair(csrfToken!!, cookieString)
            }
            
            // Priority 5: XSRF Token
            val xsrfPattern = Regex("""xsrf[_-]?token['"']?\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            xsrfPattern.find(html)?.let {
                Log.i(TAG, "✅ Found XSRF token")
                csrfToken = it.groupValues[1]
                return Pair(csrfToken!!, cookieString)
            }
            
            Log.w(TAG, "⚠️ No CSRF token found after deep search")
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting CSRF token: ${e.message}")
            return null
        }
    }
    
    /**
     * Call J2Download API to extract video URL
     */
    private fun callJ2Api(youtubeUrl: String, csrfToken: String, cookies: String): ExtractionResult? {
        try {
            val payload = J2ApiRequest(
                data = J2ApiData(
                    url = youtubeUrl,
                    unlock = true
                )
            )
            
            val jsonPayload = gson.toJson(payload)
            
            val requestBody = jsonPayload.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(J2_API_URL)
                .post(requestBody)
                .header("User-Agent", J2_UA)
                .header("Referer", "$J2_BASE_URL/")
                .header("Origin", J2_BASE_URL)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/plain, */*")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("x-csrf-token", csrfToken)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Cookie", cookies)  // Add cookies from handshake
                .build()
            
            Log.i(TAG, "🚀 Sending J2Download API request...")
            
            val response = httpClient.newCall(request).execute()
            
            Log.i(TAG, "📥 Response status: ${response.code}")
            
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ J2Download API HTTP error: ${response.code}")
                val errorBody = response.body?.string()
                if (!errorBody.isNullOrEmpty()) {
                    Log.e(TAG, "❌ Error response: ${errorBody.take(500)}")
                }
                return null
            }
            
            val responseBody = response.body?.string() ?: run {
                Log.e(TAG, "❌ Empty response body")
                return null
            }
            
            Log.d(TAG, "📥 J2Download response: ${responseBody.take(500)}...")
            
            val apiResponse = gson.fromJson(responseBody, J2ApiResponse::class.java)
            
            if (apiResponse.error == true) {
                Log.w(TAG, "⚠️ J2Download API error: ${apiResponse.message}")
                return null
            }
            
            // Parse the response to find the best media
            val bestMedia = parseBestMedia(apiResponse)
            
            if (bestMedia != null) {
                val title = apiResponse.title ?: apiResponse.author ?: "youtube_video"
                return ExtractionResult(
                    url = bestMedia.url,
                    extension = bestMedia.extension ?: "mp4",
                    title = title
                )
            }
            
            Log.w(TAG, "⚠️ No suitable media found in response")
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error calling J2 API: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Parse J2Download response to find best media (video with audio)
     */
    private fun parseBestMedia(response: J2ApiResponse): J2Media? {
        val medias = response.medias ?: return null
        
        if (medias.isEmpty()) {
            Log.w(TAG, "⚠️ No medias in response")
            return null
        }
        
        Log.i(TAG, "🧠 Parsing ${medias.size} formats for YouTube")
        
        // Priority: video with audio
        val videosWithAudio = medias.filter { media ->
            media.type == "video" && (
                media.isAudio == true || 
                media.audioQuality != null || 
                media.hasAudio == true
            )
        }
        
        if (videosWithAudio.isNotEmpty()) {
            Log.d(TAG, "✅ Found ${videosWithAudio.size} videos with audio")
            return videosWithAudio.first()
        }
        
        // Fallback: any video
        val videos = medias.filter { it.type == "video" }
        if (videos.isNotEmpty()) {
            Log.d(TAG, "⚠️ Found ${videos.size} videos (may not have audio)")
            return videos.first()
        }
        
        // Last resort: first media
        Log.w(TAG, "⚠️ Using first media as fallback")
        return medias.first()
    }
    
    // Data classes for J2Download API
    data class J2ApiRequest(
        val data: J2ApiData
    )
    
    data class J2ApiData(
        val url: String,
        val unlock: Boolean
    )
    
    data class J2ApiResponse(
        val error: Boolean? = null,
        val message: String? = null,
        val title: String? = null,
        val author: String? = null,
        val medias: List<J2Media>? = null
    )
    
    data class J2Media(
        val url: String,
        val type: String? = null,
        val extension: String? = null,
        val quality: String? = null,
        @SerializedName("is_audio")
        val isAudio: Boolean? = null,
        @SerializedName("audioQuality")
        val audioQuality: String? = null,
        @SerializedName("has_audio")
        val hasAudio: Boolean? = null
    )
    
    data class ExtractionResult(
        val url: String,
        val extension: String,
        val title: String
    )
}
