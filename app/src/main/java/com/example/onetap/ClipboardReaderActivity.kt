package com.example.onetap

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.example.onetap.utils.UrlValidator

/**
 * A transparent "Ghost" activity that exists solely to read the clipboard
 * because Android 10+ prevents Services from doing so.
 * 
 * This activity is invisible to the user and closes immediately after
 * reading the clipboard and starting the download process.
 */
class ClipboardReaderActivity : Activity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Give the activity a split second to gain focus
        // This is the "magic trick" for Android 12+
        Handler(Looper.getMainLooper()).postDelayed({
            checkClipboardAndDownload()
        }, 100)
    }
    
    private fun checkClipboardAndDownload() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        
        // Check if clipboard actually has data
        if (!clipboard.hasPrimaryClip()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }
        
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            // Get text and coerce to string to handle rich text/HTML links
            val copiedUrl = clipData.getItemAt(0).coerceToText(this).toString().trim()
            
            if (UrlValidator.isValidVideoUrl(copiedUrl)) {
                // Send to Service
                val serviceIntent = Intent(this, BubbleService::class.java).apply {
                    action = "com.example.onetap.ACTION_DOWNLOAD"
                    putExtra("URL", copiedUrl)
                }
                startService(serviceIntent)
            } else {
                // Only show error if it looks like a URL but isn't supported
                // Otherwise, the user might just have random text copied
                if (copiedUrl.startsWith("http")) {
                    Toast.makeText(this, "Link not supported: $copiedUrl", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "No video link found", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        // Close immediately
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }
}