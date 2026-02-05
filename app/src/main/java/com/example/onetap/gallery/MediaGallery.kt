package com.example.onetap.gallery

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun MediaGallerySection(
    refreshKey: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { MediaStoreGalleryRepository(context) }
    val mediaItems = remember { mutableStateListOf<DownloadedMedia>() }
    var selectedItem by remember { mutableStateOf<DownloadedMedia?>(null) }

    val permissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
                add(android.Manifest.permission.READ_MEDIA_VIDEO)
                add(android.Manifest.permission.READ_MEDIA_AUDIO)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        mediaItems.clear()
        mediaItems.addAll(repository.loadOneTapMedia())
    }

    LaunchedEffect(refreshKey) {
        val needsPermission = permissions.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsPermission && permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            mediaItems.clear()
            mediaItems.addAll(repository.loadOneTapMedia())
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = "Gallery",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.85f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (mediaItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No downloaded media yet",
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(mediaItems, key = { it.id }) { item ->
                    MediaCard(item = item, onClick = { selectedItem = item })
                }
            }
        }
    }

    selectedItem?.let { media ->
        MediaPlayerDialog(item = media, onDismiss = { selectedItem = null })
    }
}

@Composable
private fun MediaCard(item: DownloadedMedia, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnail by remember(item.uri.toString()) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.uri.toString()) {
        thumbnail = runCatching {
            when (item.kind) {
                MediaKind.IMAGE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val source = ImageDecoder.createSource(context.contentResolver, item.uri)
                        ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.setTargetSize(240, 240) }
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(context.contentResolver, item.uri)
                    }
                }

                MediaKind.VIDEO -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.openFileDescriptor(item.uri, "r")?.use { fd ->
                            ThumbnailUtils.createVideoThumbnail(fd.fileDescriptor, Size(240, 240), null)
                        }
                    } else {
                        null
                    }
                }

                MediaKind.AUDIO -> null
            }
        }.getOrNull()
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center
        ) {
            when {
                thumbnail != null -> Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                item.kind == MediaKind.AUDIO -> Icon(
                    imageVector = Icons.Default.Audiotrack,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(30.dp)
                )

                else -> Icon(
                    imageVector = Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f)
                )
            }

            if (item.kind == MediaKind.VIDEO) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp).alpha(0.85f)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.title,
            color = Color.White.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun MediaPlayerDialog(item: DownloadedMedia, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var imagePreview by remember(item.uri.toString()) { mutableStateOf<ImageBitmap?>(null) }

    val exoPlayer = remember(item.uri.toString()) {
        ExoPlayer.Builder(context).build().apply {
            if (item.kind != MediaKind.IMAGE) {
                setMediaItem(MediaItem.fromUri(item.uri))
                prepare()
                playWhenReady = true
            }
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(item.uri.toString()) {
        if (item.kind == MediaKind.IMAGE) {
            imagePreview = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val source = ImageDecoder.createSource(context.contentResolver, item.uri)
                    ImageDecoder.decodeBitmap(source).asImageBitmap()
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, item.uri).asImageBitmap()
                }
            }.getOrNull()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            when (item.kind) {
                MediaKind.IMAGE -> {
                    if (imagePreview != null) {
                        Image(
                            bitmap = imagePreview!!,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxWidth().height(260.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text("Unable to preview image")
                    }
                }

                MediaKind.AUDIO,
                MediaKind.VIDEO -> AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(if (item.kind == MediaKind.AUDIO) 120.dp else 260.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
