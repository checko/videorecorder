package com.videorecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.VideoCapture
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
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
    private var frameTester: FrameContinuityTester? = null
    private val fileManager = FileManager(context)

    private lateinit var audioHandler: Handler
    private lateinit var audioHandlerThread: HandlerThread

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
    }

    fun setStatusUpdateCallback(callback: (String, String, Int) -> Unit) {
        onStatusUpdate = callback
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return

        isRecording = true
        segmentCount = 0

        frameTester?.startFrameLogging()

        setupCodecs()
        startMuxer()
        startAudioRecording()
        scheduleNextSegment()

        Log.d(TAG, "Recording started")
    }

    fun stopRecording() {
        if (!isRecording) return

        isRecording = false
        recordingJob.cancelChildren()

        videoCodec?.stop()
        videoCodec?.release()
        audioCodec?.stop()
        audioCodec?.release()
        audioRecord?.stop()
        audioRecord?.release()

        muxer?.stop()
        muxer?.release()
        muxer = null

        audioHandlerThread.quitSafely()

        frameTester?.stopFrameLogging()

        onStatusUpdate?.invoke("Recording stopped", "", segmentCount)
        Log.d(TAG, "Recording stopped")
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
        val bufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioRecord.CHANNEL_IN_MONO, AudioRecord.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AudioRecord.CHANNEL_IN_MONO, AudioRecord.ENCODING_PCM_16BIT, bufferSize)

        audioHandlerThread = HandlerThread("AudioHandlerThread")
        audioHandlerThread.start()
        audioHandler = Handler(audioHandlerThread.looper)
    }

    private fun startMuxer() {
        val outputFile = fileManager.createVideoFile(segmentCount)
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG2TS)

        videoTrackIndex = muxer?.addTrack(videoCodec!!.outputFormat) ?: -1
        audioTrackIndex = muxer?.addTrack(audioCodec!!.outputFormat) ?: -1
        muxer?.start()
    }

    private fun startAudioRecording() {
        audioRecord?.startRecording()
        audioHandler.post { processAudio() }
    }

    private fun processAudio() {
        if (!isRecording) return

        val buffer = ByteBuffer.allocateDirect(1024)
        val size = audioRecord?.read(buffer, 1024) ?: 0
        if (size > 0) {
            val info = MediaCodec.BufferInfo()
            info.offset = 0
            info.size = size
            info.presentationTimeUs = (System.nanoTime() / 1000)
            info.flags = 0

            val inputBufferIndex = audioCodec?.dequeueInputBuffer(-1) ?: -1
            if (inputBufferIndex >= 0) {
                val inputBuffer = audioCodec?.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(buffer)
                audioCodec?.queueInputBuffer(inputBufferIndex, 0, size, info.presentationTimeUs, 0)
            }
        }

        val outputBufferIndex = audioCodec?.dequeueOutputBuffer(info, 0) ?: -1
        if (outputBufferIndex >= 0) {
            val outputBuffer = audioCodec?.getOutputBuffer(outputBufferIndex)
            muxer?.writeSampleData(audioTrackIndex, outputBuffer!!, info)
            audioCodec?.releaseOutputBuffer(outputBufferIndex, false)
        }

        if (isRecording) {
            audioHandler.post { processAudio() }
        }
    }

    private fun scheduleNextSegment() {
        if (!isRecording) return

        recordingScope.launch {
            delay(SEGMENT_DURATION_MS)

            if (isRecording) {
                segmentCount++
                val nextOutputFile = fileManager.createVideoFile(segmentCount)
                muxer?.setOutputNextFile(nextOutputFile)
                onStatusUpdate?.invoke("Recording", nextOutputFile.name, segmentCount)
                scheduleNextSegment()
            }
        }
    }
}