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
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.onetap.repository.VideoRepository
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
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "=== BubbleService Created ===")
        
        // 1. CRITICAL: Start foreground immediately to prevent Android 12+ crash
        startForeground(NOTIFICATION_ID, createNotification())
        
        // 2. Initialize System Services
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // 3. Setup UI (Only if permission is granted)
        if (Settings.canDrawOverlays(this)) {
            try {
                addBubbleOverlay()
                // Note: Clipboard monitoring removed - now handled by ClipboardReaderActivity
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add overlay", e)
            }
        } else {
            Log.w(TAG, "Overlay permission not granted. Bubble will not appear.")
            Toast.makeText(this, "Permission required for Bubble", Toast.LENGTH_LONG).show()
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
            .setSmallIcon(R.mipmap.ic_launcher_round) // Round logo for consistent branding
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher_round))
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
        Toast.makeText(this, "Download Starting...", Toast.LENGTH_SHORT).show()
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Use applicationContext to avoid leaking Service context
                val result = videoRepository.downloadVideo(applicationContext, url)
                
                withContext(Dispatchers.Main) {
                    val successMessage = if (result.contains("Success") || result.contains("Saved")) {
                        "Download Complete!"
                    } else {
                        result
                    }
                    Toast.makeText(applicationContext, successMessage, Toast.LENGTH_LONG).show()
                    isDownloading = false
                    updateBubbleAppearance(false) // Restore bubble
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "⚠️ Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                    isDownloading = false
                    updateBubbleAppearance(false)
                }
            }
        }
    }
    
    private fun updateBubbleAppearance(isBusy: Boolean) {
        val bubbleImage = bubbleView?.findViewById<ImageView>(R.id.bubble_image)
        bubbleImage?.alpha = if (isBusy) 0.5f else 1.0f
    }
    
    private fun addBubbleOverlay() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.bubble_layout, null)
        val bubbleImage = bubbleView?.findViewById<ImageView>(R.id.bubble_image)
        
        // Make the ImageView circular
        bubbleImage?.apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        
        closeView = LayoutInflater.from(this).inflate(R.layout.close_layout, null)
        
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
        else 
            WindowManager.LayoutParams.TYPE_PHONE

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 100
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
        
        bubbleView?.let { windowManager.addView(it, bubbleParams) }
        closeView?.let { 
            it.visibility = View.GONE
            windowManager.addView(it, closeParams) 
        }
        
        setupTouchListeners(bubbleImage)
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