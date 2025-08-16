package com.videorecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

class RecordingManager(
    private val context: Context,
    private val frameTestingEnabled: Boolean = false
) {
    // Dual MediaMuxer approach for overlap-based zero-gap recording
    private var muxerA: MediaMuxer? = null
    private var muxerB: MediaMuxer? = null
    private var activeMuxer: MediaMuxer? = null
    private var standbyMuxer: MediaMuxer? = null
    
    private var videoTrackIndexA: Int = -1
    private var audioTrackIndexA: Int = -1
    private var videoTrackIndexB: Int = -1
    private var audioTrackIndexB: Int = -1
    
    private var activeVideoTrack: Int = -1
    private var activeAudioTrack: Int = -1
    
    // Dual muxer state management
    private var isOverlapping = false
    private var usingMuxerA = true
    private var dualMuxerSupported = true
    private var muxerAFile: File? = null
    private var muxerBFile: File? = null

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    var videoEncoderSurface: Surface? = null

    private var isRecording = false
    private var segmentCount = 0
    private val recordingJob = SupervisorJob()
    private val recordingScope = CoroutineScope(Dispatchers.Main + recordingJob)

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private var onStatusUpdate: ((String, String, Int) -> Unit)? = null
    var frameTester: FrameContinuityTester? = null
    private val fileManager = FileManager(context)

    private lateinit var audioHandler: Handler
    private lateinit var audioHandlerThread: HandlerThread
    
    // Timestamp synchronization
    private var recordingStartTime: Long = 0
    private var muxerStarted = false
    
    // Rapid file switching
    private var segmentStartTime: Long = 0
    private var lastKeyFrameTime: Long = 0

    companion object {
        private const val TAG = "RecordingManager"
        private const val SEGMENT_DURATION_MS = 30 * 1000L // 30 seconds for testing
        private const val VIDEO_MIME_TYPE = "video/avc"
        private const val AUDIO_MIME_TYPE = "audio/mp4a-latm"
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val AUDIO_BITRATE = 64000
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_BITRATE = 2000000
        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_I_FRAME_INTERVAL = 1
    }

    init {
        if (frameTestingEnabled) {
            val logDir = File(context.getExternalFilesDir(null), "frame_logs")
            frameTester = FrameContinuityTester(logDir)
        }
        
        // Check device compatibility for dual MediaMuxer approach
        dualMuxerSupported = checkDualMuxerSupport()
        Log.d(TAG, "Dual MediaMuxer supported: $dualMuxerSupported")
    }
    
    /**
     * Phase 1: Device Compatibility Check
     * Tests if device supports multiple MediaMuxer instances simultaneously
     */
    private fun checkDualMuxerSupport(): Boolean {
        return try {
            val tempDir = context.getExternalFilesDir(null)
            val testFileA = File(tempDir, "test_muxer_a.mp4")
            val testFileB = File(tempDir, "test_muxer_b.mp4")
            
            // Test creating two MediaMuxer instances
            val testMuxerA = MediaMuxer(testFileA.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val testMuxerB = MediaMuxer(testFileB.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            // Cleanup test instances immediately
            testMuxerA.release()
            testMuxerB.release()
            
            // Cleanup test files
            testFileA.delete()
            testFileB.delete()
            
            Log.d(TAG, "Device supports dual MediaMuxer instances")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Device does not support dual MediaMuxer: ${e.message}")
            false
        }
    }

    fun setStatusUpdateCallback(callback: (String, String, Int) -> Unit) {
        onStatusUpdate = callback
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return

        try {
            isRecording = true
            recordingStartTime = System.nanoTime()
            muxerStarted = false

            frameTester?.startFrameLogging()

            setupCodecs()
            startMuxer()
            startCodecs()
            startAudioRecording()

            Log.d(TAG, "Packet-based recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            isRecording = false
            throw e
        }
    }

    fun stopRecording() {
        if (!isRecording) return

        isRecording = false
        recordingJob.cancelChildren()

        // Clean up MediaMuxer

        try {
            videoCodec?.stop()
            videoCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping video codec", e)
        }
        
        try {
            audioCodec?.stop()
            audioCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio codec", e)
        }
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record", e)
        }

        try {
            // Stop dual muxers if supported, otherwise single muxer
            if (dualMuxerSupported) {
                if (muxerStarted) {
                    muxerA?.stop()
                    muxerB?.stop()
                }
                muxerA?.release()
                muxerB?.release()
                muxerA = null
                muxerB = null
            } else {
                if (muxerStarted) {
                    muxerA?.stop()
                }
                muxerA?.release()
                muxerA = null
            }
            muxerStarted = false
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping muxer(s)", e)
        }

        try {
            audioHandlerThread.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio handler", e)
        }
        
        frameTester?.stopFrameLogging()

        // Notify media scanner about final files
        recordingScope.launch(Dispatchers.IO) {
            if (dualMuxerSupported) {
                // In dual muxer mode, notify about both final files
                val activeFile = if (usingMuxerA) muxerAFile else muxerBFile
                val standbyFile = if (usingMuxerA) muxerBFile else muxerAFile
                
                activeFile?.let { fileManager.notifyMediaScanner(it) }
                standbyFile?.let { fileManager.notifyMediaScanner(it) }
            } else {
                // Single muxer mode
                muxerAFile?.let { fileManager.notifyMediaScanner(it) }
            }
        }
        
        // Log final statistics
        Log.d(TAG, "Recording stopped - Total segments: $segmentCount")
        onStatusUpdate?.invoke("Recording stopped", "", segmentCount)
    }

    @SuppressLint("MissingPermission")
    private fun setupCodecs() {
        // Video Codec with Android Gallery compatibility settings
        val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT)
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
        
        // Add Android Gallery compatibility settings
        videoFormat.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        videoFormat.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        
        videoCodec = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE)
        videoCodec?.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoEncoderSurface = videoCodec?.createInputSurface()

        // Audio Codec
        val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT)
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
        audioCodec = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE)
        audioCodec?.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val bufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)

        audioHandlerThread = HandlerThread("AudioHandlerThread")
        audioHandlerThread.start()
        audioHandler = Handler(audioHandlerThread.looper)
    }

    private fun startMuxer() {
        try {
            if (dualMuxerSupported) {
                setupDualMuxers()
                Log.d(TAG, "Dual MediaMuxer approach for zero-gap recording")
            } else {
                setupSingleMuxer()
                Log.d(TAG, "Fallback to single MediaMuxer (device limitation)")
                onStatusUpdate?.invoke("Device limitation: Using single muxer mode", "", 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating MediaMuxer", e)
            throw e
        }
    }
    
    /**
     * Phase 2: Dual Muxer Setup
     * Creates both MediaMuxer instances for overlap recording
     */
    private fun setupDualMuxers() {
        // Create files for both muxers
        muxerAFile = fileManager.createVideoFile(segmentCount)
        muxerBFile = fileManager.createVideoFile(segmentCount + 1)
        
        // Create both MediaMuxer instances
        muxerA = MediaMuxer(muxerAFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerB = MediaMuxer(muxerBFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        // Reset all track indices
        videoTrackIndexA = -1
        audioTrackIndexA = -1
        videoTrackIndexB = -1
        audioTrackIndexB = -1
        muxerStarted = false
        
        // Start with MuxerA as active
        activeMuxer = muxerA
        standbyMuxer = muxerB
        usingMuxerA = true
        
        Log.d(TAG, "Dual MediaMuxer setup completed: A=${muxerAFile!!.name}, B=${muxerBFile!!.name}")
        onStatusUpdate?.invoke("Recording to ${muxerAFile!!.name}", muxerAFile!!.name, segmentCount)
    }
    
    /**
     * Fallback: Single Muxer Setup for unsupported devices
     */
    private fun setupSingleMuxer() {
        val videoFile = fileManager.createVideoFile(segmentCount)
        
        muxerA = MediaMuxer(videoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        activeMuxer = muxerA
        muxerAFile = videoFile
        
        videoTrackIndexA = -1
        audioTrackIndexA = -1
        muxerStarted = false
        
        Log.d(TAG, "Single MediaMuxer setup: ${videoFile.name}")
        onStatusUpdate?.invoke("Recording to ${videoFile.name}", videoFile.name, segmentCount)
    }
    
    private fun tryStartMuxer() {
        if (dualMuxerSupported) {
            tryStartDualMuxers()
        } else {
            tryStartSingleMuxer()
        }
    }
    
    private fun tryStartDualMuxers() {
        val videoReady = videoTrackIndexA >= 0 && videoTrackIndexB >= 0
        val audioReady = audioTrackIndexA >= 0 && audioTrackIndexB >= 0
        
        if (videoReady && audioReady && !muxerStarted) {
            // Start both muxers
            muxerA?.start()
            muxerB?.start()
            
            muxerStarted = true
            segmentStartTime = System.currentTimeMillis()
            
            // Set active tracks to MuxerA initially
            activeVideoTrack = videoTrackIndexA
            activeAudioTrack = audioTrackIndexA
            
            Log.d(TAG, "Dual MediaMuxer started with both tracks")
        }
    }
    
    private fun tryStartSingleMuxer() {
        if (videoTrackIndexA >= 0 && audioTrackIndexA >= 0 && !muxerStarted) {
            muxerA?.start()
            muxerStarted = true
            segmentStartTime = System.currentTimeMillis()
            
            activeVideoTrack = videoTrackIndexA
            activeAudioTrack = audioTrackIndexA
            
            Log.d(TAG, "Single MediaMuxer started with both tracks")
        }
    }
    
    private fun startCodecs() {
        videoCodec?.start()
        audioCodec?.start()
        
        // Start processing codec outputs
        recordingScope.launch(Dispatchers.IO) {
            processVideoAndAudioOutput()
        }
        
        Log.d(TAG, "MediaCodecs started")
    }

    private fun startAudioRecording() {
        audioRecord?.startRecording()
        audioHandler.post { processAudio() }
    }

    private fun processAudio() {
        if (!isRecording) return

        try {
            val buffer = ByteBuffer.allocateDirect(1024)
            val size = audioRecord?.read(buffer, 1024) ?: 0
            
            if (size > 0) {
                val presentationTimeUs = (System.nanoTime() - recordingStartTime) / 1000
                
                val inputBufferIndex = audioCodec?.dequeueInputBuffer(0) ?: -1
                if (inputBufferIndex >= 0) {
                    val inputBuffer = audioCodec?.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(buffer)
                    audioCodec?.queueInputBuffer(inputBufferIndex, 0, size, presentationTimeUs, 0)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio input", e)
        }

        if (isRecording) {
            audioHandler.post { processAudio() }
        }
    }
    
    /**
     * Process both video and audio codec outputs and extract TS packets
     */
    private fun processVideoAndAudioOutput() {
        val videoInfo = MediaCodec.BufferInfo()
        val audioInfo = MediaCodec.BufferInfo()
        
        while (isRecording) {
            try {
                // Process video output
                processVideoOutput(videoInfo)
                
                // Process audio output  
                processAudioOutput(audioInfo)
                
                // Small delay to prevent busy waiting
                Thread.sleep(1)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in codec output processing", e)
                break
            }
        }
    }
    
    private fun processVideoOutput(info: MediaCodec.BufferInfo) {
        val outputBufferIndex = videoCodec?.dequeueOutputBuffer(info, 0) ?: -1
        
        when (outputBufferIndex) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val videoFormat = videoCodec?.outputFormat
                if (videoFormat != null) {
                    if (dualMuxerSupported) {
                        // Add video track to both muxers
                        videoTrackIndexA = muxerA!!.addTrack(videoFormat)
                        videoTrackIndexB = muxerB!!.addTrack(videoFormat)
                        Log.d(TAG, "Video track added to both muxers: A=$videoTrackIndexA, B=$videoTrackIndexB")
                    } else {
                        // Single muxer fallback
                        videoTrackIndexA = muxerA!!.addTrack(videoFormat)
                        Log.d(TAG, "Video track added to single muxer: A=$videoTrackIndexA")
                    }
                    
                    // Start muxer(s) when both tracks are added
                    tryStartMuxer()
                }
            }
            
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                // No output available
            }
            
            else -> {
                if (outputBufferIndex >= 0 && muxerStarted) {
                    val outputBuffer = videoCodec?.getOutputBuffer(outputBufferIndex)
                    
                    if (outputBuffer != null && info.size > 0) {
                        // Check for keyframe and potential file switching
                        val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        if (isKeyFrame) {
                            lastKeyFrameTime = info.presentationTimeUs / 1000 // Convert to ms
                            Log.v(TAG, "Keyframe detected at ${lastKeyFrameTime}ms")
                            
                            // Check if we should switch files (only in dual muxer mode)
                            if (dualMuxerSupported) {
                                val segmentDuration = System.currentTimeMillis() - segmentStartTime
                                if (segmentDuration >= SEGMENT_DURATION_MS) {
                                    Log.d(TAG, "Switching muxer at keyframe after ${segmentDuration}ms")
                                    performOverlapSwitch()
                                }
                            }
                        }
                        
                        // Write frame to active muxer(s)
                        writeVideoFrame(outputBuffer, info)
                        Log.v(TAG, "Video frame written: size=${info.size}, keyframe=$isKeyFrame")
                    }
                    
                    videoCodec?.releaseOutputBuffer(outputBufferIndex, false)
                }
            }
        }
    }
    
    private fun processAudioOutput(info: MediaCodec.BufferInfo) {
        val outputBufferIndex = audioCodec?.dequeueOutputBuffer(info, 0) ?: -1
        
        when (outputBufferIndex) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val audioFormat = audioCodec?.outputFormat
                if (audioFormat != null) {
                    if (dualMuxerSupported) {
                        // Add audio track to both muxers
                        audioTrackIndexA = muxerA!!.addTrack(audioFormat)
                        audioTrackIndexB = muxerB!!.addTrack(audioFormat)
                        Log.d(TAG, "Audio track added to both muxers: A=$audioTrackIndexA, B=$audioTrackIndexB")
                    } else {
                        // Single muxer fallback
                        audioTrackIndexA = muxerA!!.addTrack(audioFormat)
                        Log.d(TAG, "Audio track added to single muxer: A=$audioTrackIndexA")
                    }
                    
                    // Start muxer(s) when both tracks are added
                    tryStartMuxer()
                }
            }
            
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                // No output available
            }
            
            else -> {
                if (outputBufferIndex >= 0 && muxerStarted) {
                    val outputBuffer = audioCodec?.getOutputBuffer(outputBufferIndex)
                    
                    if (outputBuffer != null && info.size > 0) {
                        // Write frame to active muxer(s)
                        writeAudioFrame(outputBuffer, info)
                        Log.v(TAG, "Audio frame written: size=${info.size}")
                    }
                    
                    audioCodec?.releaseOutputBuffer(outputBufferIndex, false)
                }
            }
        }
    }
    
    /**
     * Phase 3: Overlap Switching Logic
     * Switches from current muxer to standby muxer with overlap period
     */
    private fun performOverlapSwitch() {
        try {
            Log.d(TAG, "Starting overlap switch from ${if (usingMuxerA) "A" else "B"} to ${if (!usingMuxerA) "A" else "B"}")
            
            // Switch active muxer and tracks
            if (usingMuxerA) {
                // Switch to MuxerB
                activeMuxer = muxerB
                activeVideoTrack = videoTrackIndexB
                activeAudioTrack = audioTrackIndexB
                usingMuxerA = false
            } else {
                // Switch to MuxerA
                activeMuxer = muxerA
                activeVideoTrack = videoTrackIndexA
                activeAudioTrack = audioTrackIndexA
                usingMuxerA = true
            }
            
            // Start overlap period
            isOverlapping = true
            segmentStartTime = System.currentTimeMillis()
            segmentCount++
            
            val activeFile = if (usingMuxerA) muxerAFile else muxerBFile
            onStatusUpdate?.invoke("Recording to ${activeFile?.name}", activeFile?.name ?: "", segmentCount)
            
            // Stop previous muxer after minimal overlap delay
            Handler(Looper.getMainLooper()).postDelayed({
                completeOverlapSwitch()
            }, 200) // 200ms overlap (minimal for safety)
            
            Log.d(TAG, "Overlap switch initiated, now using ${if (usingMuxerA) "MuxerA" else "MuxerB"}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during overlap switch", e)
            isOverlapping = false
        }
    }
    
    /**
     * Completes the overlap switch by stopping the previous muxer
     */
    private fun completeOverlapSwitch() {
        try {
            val previousMuxer = if (usingMuxerA) muxerB else muxerA
            val previousFile = if (usingMuxerA) muxerBFile else muxerAFile
            
            // Stop and release previous muxer
            previousMuxer?.stop()
            previousMuxer?.release()
            
            // Notify about completed file
            previousFile?.let { file ->
                fileManager.notifyMediaScanner(file)
                Log.d(TAG, "Completed file: ${file.name}")
            }
            
            // Create new standby muxer for next switch
            createNextStandbyMuxer()
            
            isOverlapping = false
            Log.d(TAG, "Overlap switch completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error completing overlap switch", e)
            isOverlapping = false
        }
    }
    
    /**
     * Creates the next standby muxer for future switching
     */
    private fun createNextStandbyMuxer() {
        try {
            val nextFile = fileManager.createVideoFile(segmentCount + 1)
            val nextMuxer = MediaMuxer(nextFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            // Get current codec formats
            val videoFormat = videoCodec?.outputFormat
            val audioFormat = audioCodec?.outputFormat
            
            if (videoFormat != null && audioFormat != null) {
                val videoTrack = nextMuxer.addTrack(videoFormat)
                val audioTrack = nextMuxer.addTrack(audioFormat)
                nextMuxer.start()
                
                // Update standby muxer references
                if (usingMuxerA) {
                    // MuxerA is active, setup MuxerB as standby
                    muxerB = nextMuxer
                    muxerBFile = nextFile
                    videoTrackIndexB = videoTrack
                    audioTrackIndexB = audioTrack
                    standbyMuxer = muxerB
                } else {
                    // MuxerB is active, setup MuxerA as standby
                    muxerA = nextMuxer
                    muxerAFile = nextFile
                    videoTrackIndexA = videoTrack
                    audioTrackIndexA = audioTrack
                    standbyMuxer = muxerA
                }
                
                Log.d(TAG, "Next standby muxer created: ${nextFile.name}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating next standby muxer", e)
        }
    }
    
    /**
     * Writes video frame to appropriate muxer(s)
     */
    private fun writeVideoFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        try {
            if (isOverlapping) {
                // During overlap, write to both muxers
                muxerA?.writeSampleData(videoTrackIndexA, buffer, info)
                buffer.rewind() // Reset buffer position for second write
                muxerB?.writeSampleData(videoTrackIndexB, buffer, info)
            } else {
                // Normal operation, write to active muxer only
                activeMuxer?.writeSampleData(activeVideoTrack, buffer, info)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing video frame", e)
        }
    }
    
    /**
     * Writes audio frame to appropriate muxer(s)
     */
    private fun writeAudioFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        try {
            if (isOverlapping) {
                // During overlap, write to both muxers
                muxerA?.writeSampleData(audioTrackIndexA, buffer, info)
                buffer.rewind() // Reset buffer position for second write
                muxerB?.writeSampleData(audioTrackIndexB, buffer, info)
            } else {
                // Normal operation, write to active muxer only
                activeMuxer?.writeSampleData(activeAudioTrack, buffer, info)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio frame", e)
        }
    }

}