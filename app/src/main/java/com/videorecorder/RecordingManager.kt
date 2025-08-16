package com.videorecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.Handler
import android.os.HandlerThread
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
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1

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

    // Packet-based recording components
    private var packetExtractor: TsPacketExtractor? = null
    private var fileWriter: ContinuousFileWriter? = null

    private lateinit var audioHandler: Handler
    private lateinit var audioHandlerThread: HandlerThread
    
    // Timestamp synchronization
    private var recordingStartTime: Long = 0
    private var muxerStarted = false

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
        
        // Initialize packet-based components
        packetExtractor = TsPacketExtractor()
        fileWriter = ContinuousFileWriter(fileManager, SEGMENT_DURATION_MS, frameTester)
        fileWriter?.setStatusUpdateCallback { status, filename, transitions ->
            onStatusUpdate?.invoke(status, filename, transitions)
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
            fileWriter?.startWriting()

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

        // Stop packet-based writing first
        fileWriter?.stopWriting()

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
            // Only stop muxer if it was actually started
            if (muxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
            muxer = null
            muxerStarted = false
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping muxer", e)
        }

        try {
            audioHandlerThread.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio handler", e)
        }
        
        frameTester?.stopFrameLogging()

        // Log final statistics
        val extractorStats = packetExtractor?.getStatistics()
        val writerStats = fileWriter?.getStatistics()
        Log.d(TAG, "Recording stopped - Extracted: ${extractorStats?.totalPacketsExtracted} packets, " +
                "Written: ${writerStats?.totalPacketsWritten} packets")

        onStatusUpdate?.invoke("Recording stopped", "", writerStats?.fileTransitions ?: 0)
    }

    @SuppressLint("MissingPermission")
    private fun setupCodecs() {
        // Video Codec
        val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT)
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
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
        // Skip MediaMuxer for now - write directly to files
        Log.d(TAG, "Skipping MediaMuxer - writing directly to continuous files")
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
                Log.d(TAG, "Video MediaCodec format changed - ready for direct writing")
                muxerStarted = true
            }
            
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                // No output available
            }
            
            else -> {
                if (outputBufferIndex >= 0) {
                    val outputBuffer = videoCodec?.getOutputBuffer(outputBufferIndex)
                    
                    if (outputBuffer != null && info.size > 0) {
                        // Write video data directly to our continuous files
                        writeVideoDataDirectly(outputBuffer, info)
                        Log.v(TAG, "Video frame written: size=${info.size}, timestamp=${info.presentationTimeUs}")
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
                Log.d(TAG, "Audio MediaCodec format changed")
            }
            
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                // No output available
            }
            
            else -> {
                if (outputBufferIndex >= 0) {
                    val outputBuffer = audioCodec?.getOutputBuffer(outputBufferIndex)
                    
                    if (outputBuffer != null && info.size > 0) {
                        // Write audio data directly
                        writeAudioDataDirectly(outputBuffer, info)
                        Log.v(TAG, "Audio frame written: size=${info.size}, timestamp=${info.presentationTimeUs}")
                    }
                    
                    audioCodec?.releaseOutputBuffer(outputBufferIndex, false)
                }
            }
        }
    }
    
    /**
     * Write video data directly to continuous files
     */
    private fun writeVideoDataDirectly(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        try {
            // Create a simple packet with video data
            val dataSize = info.size
            val packetData = ByteArray(dataSize)
            buffer.position(info.offset)
            buffer.get(packetData, 0, dataSize)
            
            val packet = TsPacket(packetData, info.presentationTimeUs * 1000, 0) // Convert to nanoseconds
            fileWriter?.writePacket(packet)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error writing video data directly", e)
        }
    }
    
    /**
     * Write audio data directly to continuous files
     */
    private fun writeAudioDataDirectly(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        try {
            // Create a simple packet with audio data
            val dataSize = info.size
            val packetData = ByteArray(dataSize)
            buffer.position(info.offset)
            buffer.get(packetData, 0, dataSize)
            
            val packet = TsPacket(packetData, info.presentationTimeUs * 1000, 0) // Convert to nanoseconds
            fileWriter?.writePacket(packet)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio data directly", e)
        }
    }

}