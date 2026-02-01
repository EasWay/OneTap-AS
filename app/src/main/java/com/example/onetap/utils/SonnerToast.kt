package com.example.onetap.utils

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import com.example.onetap.R

object SonnerToast {
    
    private var currentView: View? = null
    private var windowManager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    
    enum class Type {
        LOADING, SUCCESS, ERROR, WARNING, INFO
    }
    
    fun show(context: Context, message: String, type: Type) {
        handler.post {
            try {
                removeView()
                
                if (windowManager == null) {
                    windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                }
                
                val inflater = LayoutInflater.from(context)
                val view = inflater.inflate(R.layout.layout_sonner_toast, null)
                
                val spinner = view.findViewById<ImageView>(R.id.loadingSpinner)
                val successIcon = view.findViewById<ImageView>(R.id.successIcon)
                val errorIcon = view.findViewById<ImageView>(R.id.errorIcon)
                val warningIcon = view.findViewById<ImageView>(R.id.warningIcon)
                val infoIcon = view.findViewById<ImageView>(R.id.infoIcon)
                val text = view.findViewById<TextView>(R.id.messageText)
                
                text.text = message
                
                // Hide all icons first
                spinner.visibility = View.GONE
                successIcon.visibility = View.GONE
                errorIcon.visibility = View.GONE
                warningIcon.visibility = View.GONE
                infoIcon.visibility = View.GONE
                
                when (type) {
                    Type.LOADING -> {
                        spinner.visibility = View.VISIBLE
                        // Spin animation
                        val rotate = AnimationUtils.loadAnimation(context, R.anim.spinner_rotate)
                        spinner.startAnimation(rotate)
                    }
                    Type.SUCCESS -> {
                        spinner.clearAnimation()
                        successIcon.visibility = View.VISIBLE
                    }
                    Type.ERROR -> {
                        spinner.clearAnimation()
                        errorIcon.visibility = View.VISIBLE
                    }
                    Type.WARNING -> {
                        spinner.clearAnimation()
                        warningIcon.visibility = View.VISIBLE
                    }
                    Type.INFO -> {
                        spinner.clearAnimation()
                        infoIcon.visibility = View.VISIBLE
                    }
                }
                
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 80 // Slightly lower for better visibility
                    windowAnimations = android.R.style.Animation_Toast
                }
                
                windowManager?.addView(view, params)
                currentView = view
                
                // Keep loading toast visible until manually dismissed or replaced
                if (type != Type.LOADING) {
                    handler.postDelayed({ removeView() }, 3000)
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun dismiss() {
        handler.post { removeView() }
    }
    
    private fun removeView() {
        try {
            currentView?.let {
                val spinner = it.findViewById<ImageView>(R.id.loadingSpinner)
                spinner?.clearAnimation()
                windowManager?.removeView(it)
                currentView = null
            }
        } catch (e: Exception) {
            // View might already be removed
        }
    }
    
    // Convenience methods for different events and downloads
    
    // === DOWNLOAD EVENTS ===
    fun showDownloadStarted(context: Context, platform: String = "") {
        val message = if (platform.isNotEmpty()) "Downloading from $platform..." else "Downloading..."
        show(context, message, Type.LOADING)
    }
    
    fun showDownloadSuccess(context: Context, platform: String = "") {
        val message = if (platform.isNotEmpty()) "Downloaded from $platform" else "Downloaded"
        show(context, message, Type.SUCCESS)
    }
    
    fun showDownloadFailed(context: Context, reason: String = "") {
        val message = if (reason.isNotEmpty()) "Download failed: $reason" else "Download failed"
        show(context, message, Type.ERROR)
    }
    
    // === SPECIFIC PLATFORM DOWNLOADS ===
    fun showYouTubeDownload(context: Context) {
        show(context, "Downloading from YouTube...", Type.LOADING)
    }
    
    fun showInstagramDownload(context: Context) {
        show(context, "Downloading from Instagram...", Type.LOADING)
    }
    
    fun showTikTokDownload(context: Context) {
        show(context, "Downloading from TikTok...", Type.LOADING)
    }
    
    fun showTwitterDownload(context: Context) {
        show(context, "Downloading from Twitter...", Type.LOADING)
    }
    
    fun showFacebookDownload(context: Context) {
        show(context, "Downloading from Facebook...", Type.LOADING)
    }
    
    // === APP EVENTS ===
    fun showUrlCopied(context: Context) {
        show(context, "URL copied to clipboard", Type.INFO)
    }
    
    fun showInvalidUrl(context: Context) {
        show(context, "Invalid URL format", Type.WARNING)
    }
    
    fun showNetworkError(context: Context) {
        show(context, "Network connection error", Type.ERROR)
    }
    
    fun showServerError(context: Context) {
        show(context, "Server temporarily unavailable", Type.WARNING)
    }
    
    fun showPermissionRequired(context: Context, permission: String) {
        show(context, "$permission permission required", Type.WARNING)
    }
    
    fun showStorageFull(context: Context) {
        show(context, "Storage space full", Type.ERROR)
    }
    
    fun showUnsupportedPlatform(context: Context) {
        show(context, "Platform not supported", Type.WARNING)
    }
    
    // === PROCESSING EVENTS ===
    fun showProcessingVideo(context: Context) {
        show(context, "Processing video...", Type.LOADING)
    }
    
    fun showExtractingAudio(context: Context) {
        show(context, "Extracting audio...", Type.LOADING)
    }
    
    fun showConvertingFormat(context: Context) {
        show(context, "Converting format...", Type.LOADING)
    }
    
    // === LEGACY METHODS (for backward compatibility) ===
    fun showLoading(context: Context) {
        show(context, "Downloading...", Type.LOADING)
    }
    
    fun showSuccess(context: Context) {
        show(context, "Downloaded", Type.SUCCESS)
    }
}