package com.example.onetap.utils

import android.util.Log
import java.util.regex.Pattern

object UrlValidator {
    private const val TAG = "OneTap_UrlValidator"
    
    private val supportedDomains = listOf(
        // TikTok & Related
        "tiktok.com", "www.tiktok.com", "vm.tiktok.com", "vt.tiktok.com",
        "douyin.com", "capcut.com",
        
        // Meta Platforms
        "facebook.com", "www.facebook.com", "fb.watch", "fb.com", "m.facebook.com",
        "instagram.com", "www.instagram.com", "instagr.am", "ig.me",
        "threads.net",
        
        // Twitter/X
        "twitter.com", "x.com", "www.twitter.com", "www.x.com", "t.co", "mobile.twitter.com",
        
        // Video Platforms
        "youtube.com", "youtu.be", "m.youtube.com", "youtube-nocookie.com",
        "vimeo.com", "www.vimeo.com",
        "dailymotion.com", "www.dailymotion.com",
        "bilibili.com", "b23.tv",
        "rumble.com", "streamable.com", "ted.com",
        "sohu.com", "tv.sohu.com", "bitchute.com",
        
        // Chinese Platforms
        "kuaishou.com", "kwai.com",
        "xiaohongshu.com", "xhslink.com",
        "ixigua.com", "weibo.com", "weibo.cn",
        "miaopai.com", "meipai.com", "xiaoying.tv",
        "yingke.com", "sina.com", "qq.com",
        
        // Social & Communication
        "reddit.com", "redd.it", "snapchat.com",
        "pinterest.com", "pin.it", "tumblr.com",
        "linkedin.com", "lnkd.in", "telegram.org", "t.me",
        "bsky.app", "bluesky.social",
        
        // Indian Platforms
        "sharechat.com", "likee.video", "like.video", "hipi.co.in",
        
        // Entertainment & Media
        "imdb.com", "imgur.com", "ifunny.co", "izlesene.com",
        "espn.com", "9gag.com", "ok.ru", "oke.ru",
        "febspot.com", "getstickerpack.com",
        
        // Audio Platforms
        "soundcloud.com", "snd.sc", "mixcloud.com",
        "spotify.com", "spoti.fi", "deezer.com",
        "zingmp3.vn", "bandcamp.com", "castbox.fm",
        
        // File Sharing
        "mediafire.com",
        
        // Adult Content
        "pornbox.com", "xvideos.com", "xnxx.com"
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
        return "50+ platforms including TikTok, Instagram, YouTube, Twitter/X, Facebook, Vimeo, Dailymotion, Bilibili, Reddit, Pinterest, Snapchat, LinkedIn, SoundCloud, Spotify, and many more"
    }
}