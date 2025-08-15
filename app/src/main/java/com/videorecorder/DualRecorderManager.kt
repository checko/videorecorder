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
    
    companion object {
        private const val TAG = "DualRecorderManager"
        private const val SEGMENT_DURATION_MS = 5 * 60 * 1000L // 5 minutes
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
        val primaryRecorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(VIDEO_QUALITY))
            .build()
        
        val secondaryRecorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(VIDEO_QUALITY))
            .build()
        
        primaryVideoCapture = VideoCapture.withOutput(primaryRecorder)
        secondaryVideoCapture = VideoCapture.withOutput(secondaryRecorder)
        
        Log.d(TAG, "Video captures setup complete")
        return Pair(primaryVideoCapture!!, secondaryVideoCapture!!)
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
        val videoCapture = when (recorderType) {
            RecorderType.PRIMARY -> primaryVideoCapture
            RecorderType.SECONDARY -> secondaryVideoCapture
        }
        
        val outputFile = createOutputFile()
        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        
        val recording = videoCapture?.output?.prepareRecording(context, outputOptions)
            ?.withAudioEnabled()
            ?.start(mainExecutor) { recordEvent ->
                handleRecordingEvent(recordEvent, outputFile.name, recorderType)
            }
        
        when (recorderType) {
            RecorderType.PRIMARY -> primaryRecording = recording
            RecorderType.SECONDARY -> secondaryRecording = recording
        }
        
        frameTester?.onFrameAvailable(outputFile.name, true)
        onStatusUpdate?.invoke("Recording", outputFile.name, segmentCount)
        
        Log.d(TAG, "Started recording with ${recorderType.name} recorder: ${outputFile.name}")
    }
    
    private fun scheduleNextRecording() {
        if (!isRecording) return
        
        recordingScope.launch {
            delay(SEGMENT_DURATION_MS - OVERLAP_DURATION_MS)
            
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
        
        // Start next recorder first
        startRecordingWithRecorder(nextRecorder)
        
        recordingScope.launch {
            delay(OVERLAP_DURATION_MS)
            
            // Stop current recorder after overlap
            val currentRecording = when (currentRecorder) {
                RecorderType.PRIMARY -> primaryRecording
                RecorderType.SECONDARY -> secondaryRecording
            }
            
            currentRecording?.stop()
            
            val transitionEndTime = System.currentTimeMillis()
            val transitionDuration = transitionEndTime - transitionStartTime
            
            frameTester?.logFileTransition(
                currentRecorder.name,
                nextRecorder.name,
                transitionDuration
            )
            
            currentRecorder = nextRecorder
            segmentCount++
            
            Log.d(TAG, "Seamless transition complete: ${currentRecorder.name} -> ${nextRecorder.name} (${transitionDuration}ms)")
            
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
        val timestamp = dateFormat.format(Date())
        val filename = "video_${timestamp}_segment_${String.format("%03d", segmentCount)}.mp4"
        val outputDir = File(context.getExternalFilesDir(null), "recordings")
        
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        
        return File(outputDir, filename)
    }
    
    fun getTestResults(): FrameContinuityTester.TestResult? {
        return frameTester?.analyzeFrameContinuity()
    }
    
    fun cleanup() {
        stopRecording()
        recordingJob.cancel()
    }
}