package com.example.onetap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.onetap.utils.UpdateInfo

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onUpdateClick: () -> Unit,
    onDismiss: () -> Unit,
    isDownloading: Boolean = false,
    downloadProgress: Int = 0
) {
    Dialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading,
            dismissOnClickOutside = !isDownloading
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.95f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = "Update Available",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Version info
                Text(
                    text = "Version ${updateInfo.versionName} is now available!",
                    fontSize = 16.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Release notes
                Text(
                    text = updateInfo.releaseNotes,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Download progress (if downloading)
                if (isDownloading) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Downloading... $downloadProgress%",
                            fontSize = 14.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        LinearProgressIndicator(
                            progress = downloadProgress / 100f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = Color.White,
                            trackColor = Color.Gray
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                
                // Buttons
                if (!isDownloading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = onDismiss
                        ) {
                            Text("Later", color = Color.Gray)
                        }
                        
                        Button(
                            onClick = onUpdateClick,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                        ) {
                            Text("Update Now", color = Color.Black)
                        }
                    }
                } else {
                    Text(
                        text = "Please wait while the update downloads...",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}