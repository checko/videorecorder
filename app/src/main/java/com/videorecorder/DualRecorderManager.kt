package com.videorecorder

import android.content.Context
import android.util.Log
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor

class DualRecorderManager(
    private val context: Context,
    private val frameTestingEnabled: Boolean = false
) {
    private var primaryVideoCapture: VideoCapture<Recorder>? = null
    private var secondaryVideoCapture: VideoCapture<Recorder>? = null
    private var primaryRecording: Recording? = null
    private var secondaryRecording: Recording? = null
    
    private var isRecording = false
    private var currentRecorder = RecorderType.PRIMARY
    private var segmentCount = 0
    private val recordingJob = SupervisorJob()
    private val recordingScope = CoroutineScope(Dispatchers.Main + recordingJob)
    
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    
    private var onStatusUpdate: ((String, String, Int) -> Unit)? = null
    private var frameTester: FrameContinuityTester? = null
    private val fileManager = FileManager(context)
    
    companion object {
        private const val TAG = "DualRecorderManager"
        private const val SEGMENT_DURATION_MS = 30 * 1000L // 30 seconds for testing
        private const val OVERLAP_DURATION_MS = 2000L // 2 seconds overlap
        private val VIDEO_QUALITY = Quality.HD
    }
    
    enum class RecorderType {
        PRIMARY, SECONDARY
    }
    
    init {
        if (frameTestingEnabled) {
            val logDir = File(context.getExternalFilesDir(null), "frame_logs")
            frameTester = FrameContinuityTester(logDir)
        }
    }
    
    fun setupVideoCaptures(): Pair<VideoCapture<Recorder>, VideoCapture<Recorder>> {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(VIDEO_QUALITY))
            .build()
        
        primaryVideoCapture = VideoCapture.withOutput(recorder)
        // Use same VideoCapture for both - we'll handle file switching manually
        secondaryVideoCapture = primaryVideoCapture
        
        Log.d(TAG, "Video captures setup complete (single recorder mode)")
        return Pair(primaryVideoCapture!!, primaryVideoCapture!!)
    }
    
    fun setStatusUpdateCallback(callback: (String, String, Int) -> Unit) {
        onStatusUpdate = callback
    }
    
    fun startRecording() {
        if (isRecording) return
        
        isRecording = true
        segmentCount = 0
        currentRecorder = RecorderType.PRIMARY
        
        frameTester?.startFrameLogging()
        
        startRecordingWithRecorder(RecorderType.PRIMARY)
        scheduleNextRecording()
        
        Log.d(TAG, "Dual recording started")
    }
    
    fun stopRecording() {
        if (!isRecording) return
        
        isRecording = false
        recordingJob.cancelChildren()
        
        primaryRecording?.stop()
        secondaryRecording?.stop()
        
        primaryRecording = null
        secondaryRecording = null
        
        frameTester?.stopFrameLogging()
        
        onStatusUpdate?.invoke("Recording stopped", "", segmentCount)
        Log.d(TAG, "Dual recording stopped")
    }
    
    private fun startRecordingWithRecorder(recorderType: RecorderType) {
        val outputFile = createOutputFile()
        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        
        val recording = primaryVideoCapture?.output?.prepareRecording(context, outputOptions)
            ?.withAudioEnabled()
            ?.start(mainExecutor) { recordEvent ->
                handleRecordingEvent(recordEvent, outputFile.name, recorderType)
            }
        
        primaryRecording = recording
        
        frameTester?.onFrameAvailable(outputFile.name, true)
        onStatusUpdate?.invoke("Recording", outputFile.name, segmentCount)
        
        Log.d(TAG, "Started recording segment ${segmentCount}: ${outputFile.name}")
    }
    
    private fun scheduleNextRecording() {
        if (!isRecording) return
        
        recordingScope.launch {
            delay(SEGMENT_DURATION_MS)
            
            if (isRecording) {
                val nextRecorder = if (currentRecorder == RecorderType.PRIMARY) {
                    RecorderType.SECONDARY
                } else {
                    RecorderType.PRIMARY
                }
                
                performSeamlessTransition(nextRecorder)
            }
        }
    }
    
    private fun performSeamlessTransition(nextRecorder: RecorderType) {
        val transitionStartTime = System.currentTimeMillis()
        
        recordingScope.launch {
            // Stop current recording
            primaryRecording?.stop()

            // Small delay to ensure recording stops cleanly
            delay(100)

            // Capture the previous file name for logging before starting a new one
            val previousFileName = frameTester?.getCurrentFileName().orEmpty()

            // Update segment count for the new recording
            segmentCount++

            // Start new recording immediately
            startRecordingWithRecorder(nextRecorder)

            val newFileName = frameTester?.getCurrentFileName().orEmpty()
            val transitionEndTime = System.currentTimeMillis()
            val transitionDuration = transitionEndTime - transitionStartTime

            // Log transition using actual filenames instead of recorder names
            frameTester?.logFileTransition(
                previousFileName,
                newFileName,
                transitionDuration
            )

            currentRecorder = nextRecorder

            Log.d(TAG, "Quick file transition complete: (${transitionDuration}ms)")

            // Schedule next transition
            scheduleNextRecording()
        }
    }
    
    private fun handleRecordingEvent(event: VideoRecordEvent, filename: String, recorderType: RecorderType) {
        when (event) {
            is VideoRecordEvent.Start -> {
                Log.d(TAG, "${recorderType.name} recording started: $filename")
            }
            is VideoRecordEvent.Finalize -> {
                if (event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                    Log.e(TAG, "${recorderType.name} recording error: ${event.error}")
                } else {
                    Log.d(TAG, "${recorderType.name} recording finalized: $filename")
                    
                    // Notify media scanner to make video visible in gallery
                    val videoFile = File(fileManager.getRecordingsDirectory(), filename)
                    if (videoFile.exists()) {
                        fileManager.notifyMediaScanner(videoFile)
                    }
                }
            }
            is VideoRecordEvent.Status -> {
                frameTester?.onFrameAvailable(filename)
                
                // Update UI periodically  
                if (event.recordingStats.recordedDurationNanos > 0) {
                    recordingScope.launch(Dispatchers.Main) {
                        onStatusUpdate?.invoke(
                            "Recording (${recorderType.name})",
                            filename,
                            segmentCount
                        )
                    }
                }
            }
            is VideoRecordEvent.Pause -> {
                Log.d(TAG, "${recorderType.name} recording paused")
            }
            is VideoRecordEvent.Resume -> {
                Log.d(TAG, "${recorderType.name} recording resumed")
            }
        }
    }
    
    private fun createOutputFile(): File {
        return fileManager.createVideoFile(segmentCount)
    }
    
    fun getTestResults(): FrameContinuityTester.TestResult? {
        return frameTester?.analyzeFrameContinuity()
    }
    
    fun cleanup() {
        stopRecording()
        recordingJob.cancel()
    }
}