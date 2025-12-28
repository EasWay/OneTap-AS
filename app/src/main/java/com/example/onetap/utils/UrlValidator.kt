package com.example.onetap.utils

import android.util.Log
import java.util.regex.Pattern

object UrlValidator {
    private const val TAG = "OneTap_UrlValidator"
    
    private val supportedDomains = listOf(
        "youtube.com", "youtu.be", "m.youtube.com",
        "instagram.com", "www.instagram.com", "instagr.am",
        "tiktok.com", "www.tiktok.com", "vm.tiktok.com",
        "twitter.com", "x.com", "www.twitter.com", "www.x.com",
        "facebook.com", "www.facebook.com", "fb.watch",
        "vimeo.com", "www.vimeo.com",
        "dailymotion.com", "www.dailymotion.com"
    )
    
    fun isValidVideoUrl(url: String?): Boolean {
        Log.d(TAG, "Validating URL: $url")
        
        if (url.isNullOrBlank()) {
            Log.w(TAG, "URL is blank")
            return false
        }
        
        val lowerUrl = url.lowercase()
        
        // Check if it's from a supported domain
        val isSupported = supportedDomains.any { domain ->
            lowerUrl.contains(domain, ignoreCase = true)
        }
        
        Log.d(TAG, "URL validation result for '$url': $isSupported")
        return isSupported
    }
    
    fun normalizeUrl(url: String): String {
        Log.d(TAG, "Normalizing URL: $url")
        var normalizedUrl = url.trim()
        
        // Add https:// if no protocol is specified
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            normalizedUrl = "https://$normalizedUrl"
            Log.d(TAG, "Added https:// prefix: $normalizedUrl")
        }
        
        Log.d(TAG, "Normalized URL: $normalizedUrl")
        return normalizedUrl
    }
    
    fun getSupportedPlatforms(): String {
        return "TikTok, Instagram, YouTube, Twitter/X, Facebook, Vimeo, Dailymotion"
    }
}