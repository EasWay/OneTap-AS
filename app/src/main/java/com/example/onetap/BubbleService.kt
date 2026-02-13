package com.example.onetap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.onetap.repository.VideoRepository
import com.example.onetap.repository.UnsupportedContentException
import com.example.onetap.download.DownloadProgress
import com.example.onetap.utils.SonnerToast
import kotlinx.coroutines.*
import kotlin.math.sqrt

class BubbleService : Service() {
    
    private val TAG = "OneTap_Bubble"
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var closeView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var closeParams: WindowManager.LayoutParams
    
    private var lastCopiedUrl: String = ""
    private var isDragging = false
    private var isDownloading = false
    
    // Video download components
    private val videoRepository = VideoRepository()
    
    // Coroutine scope (SupervisorJob ensures one crash doesn't kill the whole scope)
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "onetap_bubble_service"
    }
    
    /**
     * Safely get notification icon resource ID with fallback
     */
    private fun getNotificationIcon(): Int {
        // Always use system icon to avoid resource issues
        return android.R.drawable.ic_dialog_info
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "=== BubbleService Created ===")
        
        try {
            // 1. CRITICAL: Start foreground immediately to prevent Android 12+ crash
            Log.d(TAG, "🔔 Creating foreground notification...")
            startForeground(NOTIFICATION_ID, createNotification())
            Log.i(TAG, "✅ Foreground service started successfully")
            
            // 2. Initialize System Services
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            Log.d(TAG, "📱 WindowManager initialized")
            
            // 3. Setup UI (Only if permission is granted)
            if (Settings.canDrawOverlays(this)) {
                Log.i(TAG, "✅ Overlay permission granted - adding bubble overlay")
                try {
                    addBubbleOverlay()
                    Log.i(TAG, "🎯 Bubble overlay added successfully!")
                    // Note: Clipboard monitoring removed - now handled by ClipboardReaderActivity
                } catch (e: Exception) {
                    Log.e(TAG, "💥 Failed to add overlay", e)
                }
            } else {
                Log.w(TAG, "❌ Overlay permission not granted. Bubble will not appear.")
                // Don't show error toast for permission issues
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Critical error in BubbleService.onCreate()", e)
            throw e // Re-throw to crash and show the real issue
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OneTap Bubble",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the download bubble active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        // Clicking notification opens the App
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OneTap Active")
            .setContentText("Tap bubble to download")
            .setSmallIcon(getNotificationIcon()) // Use safe icon getter
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.example.onetap.ACTION_DOWNLOAD") {
            val url = intent.getStringExtra("URL")
            if (!url.isNullOrEmpty()) {
                handleDownload(url)
            }
        }
        return START_STICKY
    }
    
    private fun handleDownload(url: String) {
        if (url == lastCopiedUrl && isDownloading) return
        lastCopiedUrl = url
        isDownloading = true

        updateBubbleAppearance(true) // Dim bubble to show working state
        
        // Show platform-specific loading toast
        showPlatformSpecificLoadingToast(url)
        
        Log.i(TAG, "🚀 Starting download for URL: $url")
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "📥 Starting video download")
                
                // Use VideoRepository with progress tracking
                videoRepository.downloadVideoWithProgress(applicationContext, url) { progress ->
                    // Update progress in toast on main thread (non-suspend)
                    when (progress) {
                        is DownloadProgress.Progress -> {
                            // Use handler to post to main thread instead of withContext
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (progress.total > 0) {
                                    // Content length known - show percentage
                                    val percentage = progress.percentage.toInt()
                                    SonnerToast.updateProgress(percentage)
                                } else {
                                    // Content length unknown - show MB downloaded
                                    val mb = progress.downloaded / 1024.0 / 1024.0
                                    SonnerToast.updateProgressText(String.format("%.1f MB", mb))
                                }
                            }
                        }
                        else -> {} // Handle other progress types if needed
                    }
                }.collect { progress ->
                    when (progress) {
                        is DownloadProgress.Completed -> {
                            withContext(Dispatchers.Main) {
                                Log.i(TAG, "🎉 Video download completed successfully")
                                SonnerToast.showDownloadSuccess(applicationContext)
                                updateBubbleAppearance(false)
                                isDownloading = false
                            }
                        }
                        is DownloadProgress.Error -> {
                            withContext(Dispatchers.Main) {
                                Log.e(TAG, "❌ Video download failed: ${progress.error}")
                                val errorMessage = when {
                                    progress.error.contains("Network", ignoreCase = true) || 
                                    progress.error.contains("internet", ignoreCase = true) || 
                                    progress.error.contains("connection", ignoreCase = true) -> {
                                        "No connection"
                                    }
                                    progress.error.contains("timeout", ignoreCase = true) -> {
                                        "Timeout"
                                    }
                                    progress.error.contains("Storage", ignoreCase = true) || 
                                    progress.error.contains("space", ignoreCase = true) -> {
                                        "Storage full"
                                    }
                                    progress.error.contains("Already downloaded") -> {
                                        progress.error // Keep the "Already downloaded X ago" message
                                    }
                                    else -> {
                                        "Failed"
                                    }
                                }
                                SonnerToast.showDownloadFailed(applicationContext, errorMessage)
                                updateBubbleAppearance(false)
                                isDownloading = false
                            }
                        }
                        else -> {} // Started and Progress are handled above
                    }
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "❌ Download exception: ${e.message}")
                    SonnerToast.showDownloadFailed(applicationContext, "Failed")
                    updateBubbleAppearance(false)
                    isDownloading = false
                }
            }
        }
    }
    
    private fun showPlatformSpecificLoadingToast(url: String) {
        when {
            // Video Platforms
            url.contains("youtube.com") || url.contains("youtu.be") -> 
                SonnerToast.showYouTubeDownload(this)
            url.contains("vimeo.com") -> 
                SonnerToast.showDownloadStarted(this, "Vimeo")
            url.contains("dailymotion.com") -> 
                SonnerToast.showDownloadStarted(this, "Dailymotion")
            url.contains("bilibili.com") || url.contains("b23.tv") -> 
                SonnerToast.showDownloadStarted(this, "Bilibili")
            url.contains("rumble.com") -> 
                SonnerToast.showDownloadStarted(this, "Rumble")
            url.contains("streamable.com") -> 
                SonnerToast.showDownloadStarted(this, "Streamable")
            url.contains("ted.com") -> 
                SonnerToast.showDownloadStarted(this, "TED")
            url.contains("bitchute.com") -> 
                SonnerToast.showDownloadStarted(this, "BitChute")
            
            // Social Media
            url.contains("instagram.com") -> 
                SonnerToast.showInstagramDownload(this)
            url.contains("tiktok.com") || url.contains("vm.tiktok.com") -> 
                SonnerToast.showTikTokDownload(this)
            url.contains("twitter.com") || url.contains("x.com") -> 
                SonnerToast.showTwitterDownload(this)
            url.contains("facebook.com") || url.contains("fb.com") -> 
                SonnerToast.showFacebookDownload(this)
            url.contains("threads.net") -> 
                SonnerToast.showDownloadStarted(this, "Threads")
            url.contains("snapchat.com") -> 
                SonnerToast.showDownloadStarted(this, "Snapchat")
            url.contains("pinterest.com") || url.contains("pin.it") -> 
                SonnerToast.showDownloadStarted(this, "Pinterest")
            url.contains("reddit.com") || url.contains("redd.it") -> 
                SonnerToast.showDownloadStarted(this, "Reddit")
            url.contains("tumblr.com") -> 
                SonnerToast.showDownloadStarted(this, "Tumblr")
            url.contains("linkedin.com") || url.contains("lnkd.in") -> 
                SonnerToast.showDownloadStarted(this, "LinkedIn")
            url.contains("telegram.org") || url.contains("t.me") -> 
                SonnerToast.showDownloadStarted(this, "Telegram")
            url.contains("bsky.app") || url.contains("bluesky.social") -> 
                SonnerToast.showDownloadStarted(this, "Bluesky")
            
            // Chinese Platforms
            url.contains("douyin.com") -> 
                SonnerToast.showDownloadStarted(this, "Douyin")
            url.contains("kuaishou.com") || url.contains("kwai.com") -> 
                SonnerToast.showDownloadStarted(this, "Kuaishou")
            url.contains("xiaohongshu.com") || url.contains("xhslink.com") -> 
                SonnerToast.showDownloadStarted(this, "Xiaohongshu")
            url.contains("weibo.com") -> 
                SonnerToast.showDownloadStarted(this, "Weibo")
            url.contains("ixigua.com") -> 
                SonnerToast.showDownloadStarted(this, "Ixigua")
            url.contains("miaopai.com") -> 
                SonnerToast.showDownloadStarted(this, "Miaopai")
            url.contains("meipai.com") -> 
                SonnerToast.showDownloadStarted(this, "Meipai")
            url.contains("qq.com") -> 
                SonnerToast.showDownloadStarted(this, "QQ")
            
            // Indian Platforms
            url.contains("sharechat.com") -> 
                SonnerToast.showDownloadStarted(this, "ShareChat")
            url.contains("likee.video") || url.contains("like.video") -> 
                SonnerToast.showDownloadStarted(this, "Likee")
            url.contains("hipi.co.in") -> 
                SonnerToast.showDownloadStarted(this, "Hipi")
            
            // Audio Platforms
            url.contains("soundcloud.com") || url.contains("snd.sc") -> 
                SonnerToast.showDownloadStarted(this, "SoundCloud")
            url.contains("mixcloud.com") -> 
                SonnerToast.showDownloadStarted(this, "Mixcloud")
            url.contains("spotify.com") || url.contains("spoti.fi") -> 
                SonnerToast.showDownloadStarted(this, "Spotify")
            url.contains("deezer.com") -> 
                SonnerToast.showDownloadStarted(this, "Deezer")
            url.contains("bandcamp.com") -> 
                SonnerToast.showDownloadStarted(this, "Bandcamp")
            
            // Entertainment & Media
            url.contains("imgur.com") -> 
                SonnerToast.showDownloadStarted(this, "Imgur")
            url.contains("9gag.com") -> 
                SonnerToast.showDownloadStarted(this, "9GAG")
            url.contains("ifunny.co") -> 
                SonnerToast.showDownloadStarted(this, "iFunny")
            url.contains("imdb.com") -> 
                SonnerToast.showDownloadStarted(this, "IMDB")
            url.contains("espn.com") -> 
                SonnerToast.showDownloadStarted(this, "ESPN")
            
            else -> 
                SonnerToast.showDownloadStarted(this)
        }
    }
    
    private fun showPlatformSpecificSuccessToast(url: String) {
        val platform = when {
            url.contains("youtube.com") || url.contains("youtu.be") -> "YouTube"
            url.contains("instagram.com") -> "Instagram"
            url.contains("tiktok.com") -> "TikTok"
            url.contains("twitter.com") || url.contains("x.com") -> "Twitter"
            url.contains("facebook.com") || url.contains("fb.com") -> "Facebook"
            else -> ""
        }
        SonnerToast.showDownloadSuccess(this, platform)
    }
    
    private fun showPlatformSpecificErrorToast(url: String, errorMessage: String) {
        val reason = when {
            errorMessage.contains("network", ignoreCase = true) || 
            errorMessage.contains("connection", ignoreCase = true) || 
            errorMessage.contains("internet", ignoreCase = true) -> "Check your network"
            errorMessage.contains("timeout", ignoreCase = true) -> "Network timeout"
            errorMessage.contains("Server Error") -> "Server error"
            errorMessage.contains("Storage", ignoreCase = true) || 
            errorMessage.contains("space", ignoreCase = true) -> "Storage full"
            else -> "Unknown error"
        }
        SonnerToast.showDownloadFailed(this, reason)
    }
    
    private fun updateBubbleAppearance(isBusy: Boolean) {
        val bubbleImage = bubbleView?.findViewById<ImageView>(R.id.bubble_image)
        bubbleImage?.alpha = if (isBusy) 0.5f else 1.0f
    }
    
    private fun updateNotificationForDownloadComplete(isSuccess: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val title = if (isSuccess) "✅ Download Complete" else "❌ Download Failed"
        val text = if (isSuccess) "Video saved successfully" else "Check your network connection"
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(getNotificationIcon()) // Use safe icon getter
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(false)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        // Reset notification back to normal after 3 seconds
        serviceScope.launch {
            kotlinx.coroutines.delay(3000)
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        }
    }
    
    private fun addBubbleOverlay() {
        Log.d(TAG, "🎨 Starting to add bubble overlay...")
        
        try {
            Log.d(TAG, "📦 Inflating bubble layout...")
            bubbleView = LayoutInflater.from(this).inflate(R.layout.bubble_layout, null)
            Log.d(TAG, "✅ Bubble view inflated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error inflating bubble view", e)
            // Try to create a simple bubble view programmatically as fallback
            try {
                Log.d(TAG, "🔄 Creating fallback bubble view...")
                bubbleView = createFallbackBubbleView()
                Log.d(TAG, "✅ Fallback bubble view created successfully")
            } catch (fallbackError: Exception) {
                Log.e(TAG, "💥 Fallback bubble creation also failed", fallbackError)
                throw e
            }
        }
        
        val bubbleImageView = bubbleView?.findViewById<ImageView>(R.id.bubble_image)
        Log.d(TAG, "🖼️ Bubble image view found: ${bubbleImageView != null}")
        
        // Make the ImageView circular
        bubbleImageView?.apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            Log.d(TAG, "🔵 Bubble image made circular")
        }
        
        try {
            Log.d(TAG, "🗑️ Inflating close layout...")
            closeView = LayoutInflater.from(this).inflate(R.layout.close_layout, null)
            Log.d(TAG, "✅ Close view inflated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error inflating close view", e)
            // Try to create a simple close view programmatically as fallback
            try {
                Log.d(TAG, "🔄 Creating fallback close view...")
                closeView = createFallbackCloseView()
                Log.d(TAG, "✅ Fallback close view created successfully")
            } catch (fallbackError: Exception) {
                Log.e(TAG, "💥 Fallback close view creation also failed", fallbackError)
                throw e
            }
        }
        
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
        else 
            WindowManager.LayoutParams.TYPE_PHONE

        Log.d(TAG, "📱 Using layout type: $layoutType (Android ${Build.VERSION.SDK_INT})")

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 200  // Move it more to the center and lower
        }
        
        closeParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        
        Log.d(TAG, "⚙️ Window layout parameters configured")
        
        bubbleView?.let { 
            Log.d(TAG, "➕ Adding bubble view to window manager...")
            try {
                windowManager.addView(it, bubbleParams) 
                Log.i(TAG, "✅ Bubble view added successfully!")
            } catch (e: Exception) {
                Log.e(TAG, "💥 Error adding bubble view to window manager", e)
                throw e
            }
        }
        
        closeView?.let { 
            it.visibility = View.GONE
            Log.d(TAG, "➕ Adding close view to window manager...")
            try {
                windowManager.addView(it, closeParams) 
                Log.i(TAG, "✅ Close view added successfully!")
            } catch (e: Exception) {
                Log.e(TAG, "💥 Error adding close view to window manager", e)
                throw e
            }
        }
        
        Log.d(TAG, "🎮 Setting up touch listeners...")
        setupTouchListeners(bubbleImageView)
        Log.i(TAG, "🎯 Bubble overlay setup complete!")
    }
    
    private fun createFallbackBubbleView(): View {
        val frameLayout = FrameLayout(this)
        val imageView = ImageView(this).apply {
            id = R.id.bubble_image
            layoutParams = FrameLayout.LayoutParams(
                (56 * resources.displayMetrics.density).toInt(),
                (56 * resources.displayMetrics.density).toInt()
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "OneTap Bubble"
            
            // Try to set the drawable, fallback to system icon if needed
            try {
                setImageResource(R.drawable.onetap)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load onetap drawable, using system icon", e)
                setImageResource(android.R.drawable.ic_dialog_info)
            }
        }
        frameLayout.addView(imageView)
        return frameLayout
    }
    
    private fun createFallbackCloseView(): View {
        val frameLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                bottomMargin = (50 * resources.displayMetrics.density).toInt()
            }
        }
        
        // Create trash body
        val trashBody = ImageView(this).apply {
            id = R.id.trash_body
            layoutParams = FrameLayout.LayoutParams(
                (100 * resources.displayMetrics.density).toInt(),
                (100 * resources.displayMetrics.density).toInt()
            )
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Trash Body"
            
            // Try to set the drawable, fallback to system icon if needed
            try {
                setImageResource(R.drawable.ic_trash_body)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load trash_body drawable, using system icon", e)
                setImageResource(android.R.drawable.ic_delete)
            }
        }
        
        // Create trash lid
        val trashLid = ImageView(this).apply {
            id = R.id.trash_lid
            layoutParams = FrameLayout.LayoutParams(
                (100 * resources.displayMetrics.density).toInt(),
                (100 * resources.displayMetrics.density).toInt()
            )
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Trash Lid"
            
            // Try to set the drawable, fallback to system icon if needed
            try {
                setImageResource(R.drawable.ic_trash_lid)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load trash_lid drawable, using system icon", e)
                setImageResource(android.R.drawable.ic_menu_delete)
            }
        }
        
        frameLayout.addView(trashBody)
        frameLayout.addView(trashLid)
        return frameLayout
    }
    
    private fun setupTouchListeners(view: View?) {
        view?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f
            private var isNearTrash = false
            private var trashLidOpen = false
            
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams.x
                        initialY = bubbleParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        isNearTrash = false
                        trashLidOpen = false
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // Reset bubble size first
                        val bubbleImage = bubbleView?.findViewById<ImageView>(R.id.bubble_image)
                        
                        // Stop any ongoing animations
                        closeView?.clearAnimation()
                        closeView?.findViewById<View>(R.id.trash_lid)?.clearAnimation()
                        closeView?.findViewById<View>(R.id.trash_body)?.clearAnimation()
                        
                        // Check if dropped on Trash
                        val screenHeight = resources.displayMetrics.heightPixels
                        if (event.rawY > screenHeight - 200) {
                            // WhatsApp-style trash animation (bubble is already small)
                            performWhatsAppTrashAnimation()
                            return true
                        } else {
                            // Reset bubble to normal size when not trashing
                            bubbleImage?.scaleX = 1.0f
                            bubbleImage?.scaleY = 1.0f
                            bubbleImage?.alpha = 1.0f
                            
                            // Close trash lid if it was open
                            if (trashLidOpen) {
                                val lidCloseAnim = AnimationUtils.loadAnimation(this@BubbleService, R.anim.trash_lid_close)
                                closeView?.findViewById<View>(R.id.trash_lid)?.startAnimation(lidCloseAnim)
                                trashLidOpen = false
                            }
                            closeView?.visibility = View.GONE
                        }
                        
                        if (!isDragging) {
                            // Launch the Ghost Proxy instead of MainActivity
                            val intent = Intent(this@BubbleService, ClipboardReaderActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(intent)
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        
                        if (sqrt((dx * dx + dy * dy).toDouble()) > 10) isDragging = true
                        
                        if (isDragging) {
                            closeView?.visibility = View.VISIBLE
                            bubbleParams.x = initialX + dx
                            bubbleParams.y = initialY + dy
                            windowManager.updateViewLayout(bubbleView, bubbleParams)
                            
                            // Check distance to trash
                            val screenHeight = resources.displayMetrics.heightPixels
                            val nearTrash = event.rawY > screenHeight - 300
                            val veryNearTrash = event.rawY > screenHeight - 200
                            
                            // Dynamic bubble scaling based on distance to trash
                            val distanceToTrash = screenHeight - event.rawY
                            val bubbleImage = bubbleView?.findViewById<ImageView>(R.id.bubble_image)
                            
                            when {
                                distanceToTrash < 120 -> {
                                    // Very close - shrink to 25% (tiny!)
                                    bubbleImage?.scaleX = 0.25f
                                    bubbleImage?.scaleY = 0.25f
                                    bubbleImage?.alpha = 0.7f
                                }
                                distanceToTrash < 180 -> {
                                    // Close - shrink to 40%
                                    bubbleImage?.scaleX = 0.4f
                                    bubbleImage?.scaleY = 0.4f
                                    bubbleImage?.alpha = 0.8f
                                }
                                distanceToTrash < 250 -> {
                                    // Getting close - shrink to 60%
                                    bubbleImage?.scaleX = 0.6f
                                    bubbleImage?.scaleY = 0.6f
                                    bubbleImage?.alpha = 0.9f
                                }
                                distanceToTrash < 320 -> {
                                    // Near trash area - shrink to 80%
                                    bubbleImage?.scaleX = 0.8f
                                    bubbleImage?.scaleY = 0.8f
                                    bubbleImage?.alpha = 0.95f
                                }
                                else -> {
                                    // Normal size
                                    bubbleImage?.scaleX = 1.0f
                                    bubbleImage?.scaleY = 1.0f
                                    bubbleImage?.alpha = 1.0f
                                }
                            }
                            
                            if (veryNearTrash && !trashLidOpen) {
                                // Open trash lid when very close
                                trashLidOpen = true
                                val lidOpenAnim = AnimationUtils.loadAnimation(this@BubbleService, R.anim.trash_lid_open)
                                closeView?.findViewById<View>(R.id.trash_lid)?.startAnimation(lidOpenAnim)
                                
                                // Start pulsing animation
                                val pulseAnim = AnimationUtils.loadAnimation(this@BubbleService, R.anim.trash_pulse)
                                closeView?.startAnimation(pulseAnim)
                                
                            } else if (!veryNearTrash && trashLidOpen) {
                                // Close trash lid when moving away
                                trashLidOpen = false
                                val lidCloseAnim = AnimationUtils.loadAnimation(this@BubbleService, R.anim.trash_lid_close)
                                closeView?.findViewById<View>(R.id.trash_lid)?.startAnimation(lidCloseAnim)
                                closeView?.clearAnimation()
                            }
                            
                            if (nearTrash && !isNearTrash) {
                                isNearTrash = true
                            } else if (!nearTrash && isNearTrash) {
                                isNearTrash = false
                                closeView?.clearAnimation()
                                // Reset bubble size when moving away from trash area
                                bubbleImage?.scaleX = 1.0f
                                bubbleImage?.scaleY = 1.0f
                                bubbleImage?.alpha = 1.0f
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })
    }
    
    private fun performWhatsAppTrashAnimation() {
        // 1. Bubble gets sucked into trash
        val bubbleDisappearAnim = AnimationUtils.loadAnimation(this, R.anim.bubble_disappear)
        bubbleView?.startAnimation(bubbleDisappearAnim)
        
        // 2. Trash lid closes after bubble goes in
        bubbleView?.postDelayed({
            val lidCloseAnim = AnimationUtils.loadAnimation(this, R.anim.trash_lid_close)
            closeView?.findViewById<View>(R.id.trash_lid)?.startAnimation(lidCloseAnim)
        }, 150)
        
        // 3. Whole trash fades out
        bubbleView?.postDelayed({
            val fadeOut = AnimationUtils.loadAnimation(this, android.R.anim.fade_out)
            closeView?.startAnimation(fadeOut)
        }, 300)
        
        // 4. Stop service after all animations
        bubbleView?.postDelayed({
            stopSelf()
        }, 500)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try {
            if (bubbleView != null) windowManager.removeView(bubbleView)
            if (closeView != null) windowManager.removeView(closeView)
        } catch (e: Exception) {
            // Views might already be removed
        }
    }
}