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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.example.onetap.repository.VideoRepository
import com.example.onetap.utils.UrlValidator
import com.example.onetap.utils.DownloadNotificationManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val TAG = "OneTap_MainActivity"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            OneTapTheme {
                MinimalScreen()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        val sharedPreferences = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val hasRequestedOverlay = sharedPreferences.getBoolean("has_requested_overlay", false)
        
        if (hasRequestedOverlay && Settings.canDrawOverlays(this)) {
            startBubbleService(this)
        }
    }
}

@Composable
fun MinimalScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videoRepository = remember { VideoRepository() }
    val notificationManager = remember { DownloadNotificationManager(context) }
    val uriHandler = LocalUriHandler.current
    
    var isDownloading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("tap to download video") }
    var showTutorial by remember { mutableStateOf(false) }
    
    // Check if tutorial should be shown
    LaunchedEffect(Unit) {
        val sharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val hasCompletedTutorial = sharedPreferences.getBoolean("tutorial_completed", false)
        val hasRequestedOverlay = sharedPreferences.getBoolean("has_requested_overlay", false)
        
        if (!hasCompletedTutorial) {
            showTutorial = true
        } else {
            // Handle overlay permission for returning users
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
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (showTutorial) Modifier.blur(10.dp) else Modifier
            )
    ) {
        // Main tap area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = !isDownloading && !showTutorial) {
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
        
        // Credits at the bottom
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
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
                    color = Color.White
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
    onError: (String) -> Unit
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
        
        val result = videoRepository.downloadVideo(context, normalizedUrl)
        
        if (result.contains("Saved") || result.contains("Success")) {
            onStatusUpdate("complete")
            notificationManager.showDownloadComplete()
            kotlinx.coroutines.delay(1000)
            onDownloadComplete()
        } else {
            notificationManager.showDownloadError(result)
            onError("failed")
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
    try {
        val intent = Intent(context, BubbleService::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    } catch (e: Exception) {
        Log.e("OneTap_MainActivity", "Failed to start bubble service", e)
    }
}

@Composable
fun TutorialModal(onComplete: () -> Unit) {
    var currentStep by remember { mutableStateOf(0) }
    val uriHandler = LocalUriHandler.current
    
    val tutorialSteps = listOf(
        TutorialStep(
            title = "Welcome to OneTap",
            description = "The fastest way to download videos from TikTok, Instagram, and other platforms."
        ),
        TutorialStep(
            title = "How it works",
            description = "Copy any video URL from your favorite app, then come back and tap anywhere on the screen."
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
                        // Last step - LinkedIn button
                        Column {
                            Button(
                                onClick = {
                                    uriHandler.openUri("https://www.linkedin.com/in/resilience-fred")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
                            ) {
                                Text("Connect on LinkedIn", color = Color.White)
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            TextButton(
                                onClick = onComplete
                            ) {
                                Text("Skip & Continue", color = Color.Gray)
                            }
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

data class TutorialStep(
    val title: String,
    val description: String
)