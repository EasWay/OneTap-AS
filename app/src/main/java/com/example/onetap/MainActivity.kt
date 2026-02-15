package com.example.onetap

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.Keep
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.onetap.ui.theme.OneTapTheme
import com.example.onetap.gallery.MediaGallerySection
import com.example.onetap.repository.VideoRepository
import com.example.onetap.utils.UrlValidator
import com.example.onetap.utils.DownloadNotificationManager
import com.example.onetap.utils.UpdateManager
import com.example.onetap.ui.UpdateDialog
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val TAG = "OneTap_MainActivity"
    private lateinit var updateManager: UpdateManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        updateManager = UpdateManager(this)
        
        setContent {
            OneTapTheme {
                MinimalScreen()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        Log.i("OneTap_MainActivity", "📱 MainActivity.onResume() called")
        
        val sharedPreferences = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val hasRequestedOverlay = sharedPreferences.getBoolean("has_requested_overlay", false)
        val overlayPermissionGranted = Settings.canDrawOverlays(this)
        
        Log.d("OneTap_MainActivity", "🔍 hasRequestedOverlay: $hasRequestedOverlay")
        Log.d("OneTap_MainActivity", "🔍 overlayPermissionGranted: $overlayPermissionGranted")
        
        if (hasRequestedOverlay && overlayPermissionGranted) {
            Log.i("OneTap_MainActivity", "🎯 Conditions met - starting BubbleService from onResume")
            startBubbleService(this)
        } else {
            Log.w("OneTap_MainActivity", "⚠️ Conditions not met for starting BubbleService")
            Log.w("OneTap_MainActivity", "   - hasRequestedOverlay: $hasRequestedOverlay")
            Log.w("OneTap_MainActivity", "   - overlayPermissionGranted: $overlayPermissionGranted")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        updateManager.cleanup()
    }
}

@Composable
fun MinimalScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val videoRepository = remember { VideoRepository() }
    
    val notificationManager = remember { DownloadNotificationManager(context) }
    val updateManager = remember { UpdateManager(context) }
    val uriHandler = LocalUriHandler.current
    
    var isDownloading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("tap to download video") }
    var showTutorial by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.example.onetap.utils.UpdateInfo?>(null) }
    var isUpdating by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(0) }
    var galleryRefreshKey by remember { mutableStateOf(0) }
    
    // Check for updates on app start
    LaunchedEffect(Unit) {
        Log.i("OneTap_MainActivity", "🚀 App started - initializing...")
        

        
        // Server-side processing mode for non-YouTube platforms
        Log.i("OneTap_MainActivity", "☁️ Using hybrid processing: Native YouTube + Server for other platforms")
        
        val sharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val hasCompletedTutorial = sharedPreferences.getBoolean("tutorial_completed", false)
        val hasRequestedOverlay = sharedPreferences.getBoolean("has_requested_overlay", false)
        
        Log.d("OneTap_MainActivity", "📚 Tutorial completed: $hasCompletedTutorial")
        Log.d("OneTap_MainActivity", "🔄 Overlay requested: $hasRequestedOverlay")
        
        // Check for updates in a separate coroutine with proper error handling
        Log.i("OneTap_MainActivity", "🔍 Starting update check...")
        
        // Launch update check in a separate scope to prevent scope cancellation issues
        scope.launch {
            try {
                // Use retry mechanism for Render cold starts (2 attempts with delays)
                val update = updateManager.checkForUpdates(retryCount = 2)
                if (update?.isUpdateAvailable == true) {
                    Log.i("OneTap_MainActivity", "🎉 UPDATE FOUND! Version ${update.versionName}")
                    updateInfo = update
                    showUpdateDialog = true
                } else if (update != null) {
                    Log.i("OneTap_MainActivity", "✅ App is up to date (v${update.versionCode})")
                } else {
                    Log.w("OneTap_MainActivity", "⚠️ Update check failed - likely network timeout or server cold start")
                }
            } catch (e: Exception) {
                Log.e("OneTap_MainActivity", "💥 Update check failed: ${e.message}", e)
                // Don't crash the app if update check fails
            }
        }
        
        if (!hasCompletedTutorial) {
            Log.d("OneTap_MainActivity", "📖 Showing tutorial...")
            showTutorial = true
        } else {
            // Handle overlay permission for returning users
            if (!hasRequestedOverlay) {
                if (!Settings.canDrawOverlays(context)) {
                    Log.d("OneTap_MainActivity", "🔐 Requesting overlay permission...")
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                    sharedPreferences.edit().putBoolean("has_requested_overlay", true).apply()
                } else {
                    Log.d("OneTap_MainActivity", "✅ Overlay permission granted, starting service...")
                    startBubbleService(context)
                    sharedPreferences.edit().putBoolean("has_requested_overlay", true).apply()
                }
            } else if (Settings.canDrawOverlays(context)) {
                Log.d("OneTap_MainActivity", "🔄 Restarting bubble service...")
                startBubbleService(context)
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (showTutorial || showUpdateDialog) Modifier.blur(10.dp) else Modifier
            )
            .padding(WindowInsets.systemBars.asPaddingValues())
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(enabled = !isDownloading && !showTutorial && !showUpdateDialog) {
                        scope.launch {
                            handleTapToDownload(
                                context = context,
                                videoRepository = videoRepository,
                                notificationManager = notificationManager,
                                onDownloadStart = {
                                    isDownloading = true
                                    statusMessage = "downloading..."
                                },
                                onStatusUpdate = { message ->
                                    statusMessage = message
                                },
                                onDownloadComplete = {
                                    isDownloading = false
                                    statusMessage = "tap to download video"
                                },
                                onError = { error ->
                                    isDownloading = false
                                    statusMessage = error
                                },
                                onSuccess = {
                                    galleryRefreshKey++
                                }
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = statusMessage,
                    fontSize = 16.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }

            MediaGallerySection(
                refreshKey = galleryRefreshKey,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            val annotatedString = buildAnnotatedString {
                append("Credits: ")
                pushStringAnnotation(
                    tag = "URL",
                    annotation = "https://godfred-fokuo.vercel.app/"
                )
                pushStyle(
                    style = SpanStyle(
                        color = Color.White,
                        textDecoration = TextDecoration.Underline
                    )
                )
                append("Godfred Fokuo")
                pop()
                pop()
            }

            ClickableText(
                text = annotatedString,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Thin,
                    color = Color.White.copy(alpha = 0.85f)
                ),
                onClick = { offset ->
                    annotatedString.getStringAnnotations("URL", offset, offset)
                        .firstOrNull()?.let { annotation ->
                            uriHandler.openUri(annotation.item)
                        }
                }
            )
        }
    }
    
    // Update Dialog
    if (showUpdateDialog && updateInfo != null) {
        UpdateDialog(
            updateInfo = updateInfo!!,
            onUpdateClick = {
                isUpdating = true
                updateManager.downloadAndInstallUpdate(
                    updateInfo!!
                ) { progress ->
                    updateProgress = progress
                }
            },
            onDismiss = {
                showUpdateDialog = false
                updateInfo = null
            },
            isDownloading = isUpdating,
            downloadProgress = updateProgress
        )
    }
    
    // Tutorial Modal
    if (showTutorial) {
        TutorialModal(
            onComplete = {
                showTutorial = false
                val sharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                sharedPreferences.edit().putBoolean("tutorial_completed", true).apply()
                
                // Handle overlay permission after tutorial
                val hasRequestedOverlay = sharedPreferences.getBoolean("has_requested_overlay", false)
                if (!hasRequestedOverlay) {
                    if (!Settings.canDrawOverlays(context)) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                        sharedPreferences.edit().putBoolean("has_requested_overlay", true).apply()
                    } else {
                        startBubbleService(context)
                        sharedPreferences.edit().putBoolean("has_requested_overlay", true).apply()
                    }
                } else if (Settings.canDrawOverlays(context)) {
                    startBubbleService(context)
                }
            }
        )
    }
}

private suspend fun handleTapToDownload(
    context: Context,
    videoRepository: VideoRepository,
    notificationManager: DownloadNotificationManager,
    onDownloadStart: () -> Unit,
    onStatusUpdate: (String) -> Unit,
    onDownloadComplete: () -> Unit,
    onError: (String) -> Unit,
    onSuccess: () -> Unit
) {
    try {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        
        if (clipText.isNullOrBlank()) {
            onError("empty clipboard")
            kotlinx.coroutines.delay(2000)
            onDownloadComplete()
            return
        }
        
        val normalizedUrl = UrlValidator.normalizeUrl(clipText)
        if (!UrlValidator.isValidVideoUrl(normalizedUrl)) {
            onError("invalid url")
            kotlinx.coroutines.delay(2000)
            onDownloadComplete()
            return
        }
        
        onDownloadStart()
        notificationManager.showDownloadStarted()
        
        // Use retry mechanism for video downloads (especially important after app inactivity)
        val result = videoRepository.downloadVideo(context, normalizedUrl, retryCount = 2)
        
        if (result.contains("Saved") || result.contains("Success")) {
            onStatusUpdate("complete")
            notificationManager.showDownloadComplete()
            onSuccess()
            kotlinx.coroutines.delay(1000)
            onDownloadComplete()
        } else {
            notificationManager.showDownloadError(result)
            
            // Show more specific error messages
            when {
                result.contains("timeout") || result.contains("Timeout") -> {
                    onError("server timeout")
                }
                result.contains("Connection failed") -> {
                    onError("connection failed")
                }
                result.contains("Server Error") -> {
                    onError("server error")
                }
                else -> {
                    onError("failed")
                }
            }
            
            kotlinx.coroutines.delay(2000)
            onDownloadComplete()
        }
        
    } catch (e: Exception) {
        Log.e("OneTap_Download", "Download failed", e)
        notificationManager.showDownloadError(e.message ?: "error")
        onError("error")
        kotlinx.coroutines.delay(2000)
        onDownloadComplete()
    }
}

fun startBubbleService(context: Context) {
    Log.i("OneTap_MainActivity", "🚀 Attempting to start BubbleService...")
    
    try {
        // Check overlay permission first
        if (!Settings.canDrawOverlays(context)) {
            Log.w("OneTap_MainActivity", "❌ Overlay permission not granted - service may not work properly")
        } else {
            Log.i("OneTap_MainActivity", "✅ Overlay permission granted")
        }
        
        val intent = Intent(context, BubbleService::class.java)
        Log.d("OneTap_MainActivity", "📦 Created service intent: ${intent.component}")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d("OneTap_MainActivity", "📱 Android O+ detected, starting foreground service...")
            val result = context.startForegroundService(intent)
            Log.d("OneTap_MainActivity", "🔄 Foreground service start result: $result")
        } else {
            Log.d("OneTap_MainActivity", "📱 Pre-Android O, starting regular service...")
            val result = context.startService(intent)
            Log.d("OneTap_MainActivity", "🔄 Service start result: $result")
        }
        
        Log.i("OneTap_MainActivity", "✅ BubbleService start command sent successfully")
        
    } catch (e: Exception) {
        Log.e("OneTap_MainActivity", "💥 Failed to start bubble service: ${e.javaClass.simpleName}: ${e.message}", e)
        Log.e("OneTap_MainActivity", "📋 Stack trace: ${e.stackTraceToString()}")
    }
}

@Composable
fun TutorialModal(onComplete: () -> Unit) {
    var currentStep by remember { mutableStateOf(0) }
    val uriHandler = LocalUriHandler.current
    
    val tutorialSteps = listOf(
        TutorialStep(
            title = "This is OneTap!",
            description = "The fastest way to download videos from 50+ platforms including TikTok, Instagram, Facebook, Twitter, YouTube, and many more."
        ),
        TutorialStep(
            title = "How it works",
            description = "Copy any video URL from your social media app, then tap anywhere on the screen. Works with all major platforms worldwide."
        ),
        TutorialStep(
            title = "Background downloads",
            description = "OneTap works in the background with a floating bubble. You can download videos without opening the app."
        ),
        TutorialStep(
            title = "Connect with me",
            description = "Follow my journey and connect with me on LinkedIn for updates and new features."
        )
    )
    
    Dialog(
        onDismissRequest = { /* Prevent dismissal */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.9f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Step indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(tutorialSteps.size) { index ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (index <= currentStep) Color.White else Color.Gray,
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        if (index < tutorialSteps.size - 1) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Content
                Text(
                    text = tutorialSteps[currentStep].title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = tutorialSteps[currentStep].description,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (currentStep == 0) Arrangement.End else Arrangement.SpaceBetween
                ) {
                    if (currentStep > 0) {
                        TextButton(
                            onClick = { currentStep-- }
                        ) {
                            Text("Back", color = Color.Gray)
                        }
                    }
                    
                    if (currentStep == tutorialSteps.size - 1) {
                        // Last step - LinkedIn button (mandatory)
                        Button(
                            onClick = {
                                uriHandler.openUri("https://www.linkedin.com/in/resilience-fred")
                                onComplete() // Complete tutorial after clicking LinkedIn
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
                        ) {
                            Text("Connect on LinkedIn", color = Color.White)
                        }
                    } else {
                        Button(
                            onClick = { currentStep++ },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                        ) {
                            Text("Next", color = Color.Black)
                        }
                    }
                }
            }
        }
    }
}

@Keep
data class TutorialStep(
    val title: String,
    val description: String
)