package com.tapstream.downloader.ui

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.tapstream.downloader.domain.model.ErrorType
import com.tapstream.downloader.domain.model.VideoDownloadResult
import com.tapstream.downloader.domain.usecase.DownloadVideoUseCase
import com.tapstream.downloader.domain.usecase.ScheduleDownloadUseCase
import com.tapstream.downloader.domain.usecase.UrlValidationResult
import com.tapstream.downloader.domain.usecase.ValidateUrlUseCase
import com.tapstream.downloader.utils.DownloadNotificationManager
import com.tapstream.downloader.worker.DownloadWorkManager
import com.tapstream.downloader.worker.DownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for MainActivity
 * Handles all business logic and state management
 * 
 * Now supports both immediate and WorkManager-based downloads
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val downloadVideoUseCase: DownloadVideoUseCase,
    private val scheduleDownloadUseCase: ScheduleDownloadUseCase,
    private val validateUrlUseCase: ValidateUrlUseCase,
    private val downloadWorkManager: DownloadWorkManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    private val tag = "OneTap_MainViewModel"
    
    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Idle)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    private val _activeDownloads = MutableStateFlow<Map<UUID, WorkInfo>>(emptyMap())
    val activeDownloads: StateFlow<Map<UUID, WorkInfo>> = _activeDownloads.asStateFlow()
    
    private val notificationManager = DownloadNotificationManager(context)
    
    // Toggle between immediate and WorkManager downloads
    private var useWorkManager = true
    
    fun handleTapToDownload() {
        viewModelScope.launch {
            try {
                // Get clipboard content
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                
                // Validate URL
                when (val validationResult = validateUrlUseCase(clipText)) {
                    is UrlValidationResult.Empty -> {
                        _uiState.value = MainUiState.Error("empty clipboard", ErrorType.EMPTY_CLIPBOARD)
                        delay(2000)
                        _uiState.value = MainUiState.Idle
                        return@launch
                    }
                    is UrlValidationResult.Invalid -> {
                        _uiState.value = MainUiState.Error("invalid url", ErrorType.INVALID_URL)
                        delay(2000)
                        _uiState.value = MainUiState.Idle
                        return@launch
                    }
                    is UrlValidationResult.Valid -> {
                        // Proceed with download
                        if (useWorkManager) {
                            startWorkManagerDownload(validationResult.normalizedUrl)
                        } else {
                            startDownload(validationResult.normalizedUrl)
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(tag, "Download failed", e)
                notificationManager.showDownloadError(e.message ?: "error")
                _uiState.value = MainUiState.Error("error", ErrorType.UNKNOWN)
                delay(2000)
                _uiState.value = MainUiState.Idle
            }
        }
    }
    
    /**
     * Start download using WorkManager (reliable, survives app death)
     */
    private suspend fun startWorkManagerDownload(url: String) {
        _uiState.value = MainUiState.Downloading
        
        // For now, we need to get the download URL first
        // In a real implementation, you'd extract this from the video info
        // This is a simplified version that schedules the work
        
        val filename = "video_${System.currentTimeMillis()}.mp4"
        
        // Check if already downloading
        if (scheduleDownloadUseCase.isDownloadInProgress(filename)) {
            _uiState.value = MainUiState.Error("already downloading", ErrorType.UNKNOWN)
            delay(2000)
            _uiState.value = MainUiState.Idle
            return
        }
        
        // For demonstration, we'll fall back to immediate download
        // In production, you'd schedule the work after getting video info
        startDownload(url)
    }
    
    /**
     * Monitor a WorkManager download
     */
    fun monitorDownload(workId: UUID) {
        viewModelScope.launch {
            downloadWorkManager.getWorkInfoLiveData(workId).observeForever { workInfo ->
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getInt(DownloadWorker.PROGRESS_PERCENTAGE, 0)
                        _uiState.value = MainUiState.Downloading
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val filePath = workInfo.outputData.getString(DownloadWorker.KEY_OUTPUT_FILE_PATH)
                        _uiState.value = MainUiState.Success("complete")
                        viewModelScope.launch {
                            delay(1000)
                            _uiState.value = MainUiState.Idle
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val error = workInfo.outputData.getString(DownloadWorker.KEY_OUTPUT_ERROR) ?: "Unknown error"
                        _uiState.value = MainUiState.Error(error, ErrorType.UNKNOWN)
                        viewModelScope.launch {
                            delay(2000)
                            _uiState.value = MainUiState.Idle
                        }
                    }
                    WorkInfo.State.CANCELLED -> {
                        _uiState.value = MainUiState.Error("cancelled", ErrorType.UNKNOWN)
                        viewModelScope.launch {
                            delay(2000)
                            _uiState.value = MainUiState.Idle
                        }
                    }
                    else -> {
                        // ENQUEUED or BLOCKED
                    }
                }
            }
        }
    }
    
    /**
     * Original immediate download method
     */
    private suspend fun startDownload(url: String) {
        _uiState.value = MainUiState.Downloading
        notificationManager.showDownloadStarted()
        
        when (val result = downloadVideoUseCase(url, retryCount = 2)) {
            is VideoDownloadResult.Success -> {
                _uiState.value = MainUiState.Success("complete")
                notificationManager.showDownloadComplete()
                delay(1000)
                _uiState.value = MainUiState.Idle
            }
            is VideoDownloadResult.BatchInitiated -> {
                _uiState.value = MainUiState.Success("batch started")
                delay(1000)
                _uiState.value = MainUiState.Idle
            }
            is VideoDownloadResult.Duplicate -> {
                notificationManager.showDownloadError("Already downloaded ${result.timeSince}")
                _uiState.value = MainUiState.Error("duplicate", ErrorType.UNKNOWN)
                delay(2000)
                _uiState.value = MainUiState.Idle
            }
            is VideoDownloadResult.UnsupportedContent -> {
                notificationManager.showDownloadError(result.message)
                _uiState.value = MainUiState.Error("not supported", ErrorType.UNSUPPORTED_CONTENT)
                delay(2000)
                _uiState.value = MainUiState.Idle
            }
            is VideoDownloadResult.Error -> {
                notificationManager.showDownloadError(result.message)
                val errorMessage = when (result.errorType) {
                    ErrorType.TIMEOUT -> "server timeout"
                    ErrorType.NETWORK -> "connection failed"
                    ErrorType.SERVER -> "server error"
                    else -> "failed"
                }
                _uiState.value = MainUiState.Error(errorMessage, result.errorType)
                delay(2000)
                _uiState.value = MainUiState.Idle
            }
        }
    }
    
    /**
     * Cancel all active downloads
     */
    fun cancelAllDownloads() {
        downloadWorkManager.cancelAllDownloads()
    }
}

/**
 * UI State for MainActivity
 */
sealed class MainUiState {
    object Idle : MainUiState()
    object Downloading : MainUiState()
    data class Success(val message: String) : MainUiState()
    data class Error(val message: String, val errorType: ErrorType) : MainUiState()
}
