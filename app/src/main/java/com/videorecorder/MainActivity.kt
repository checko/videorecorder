package com.videorecorder

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.videorecorder.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var recordingManager: RecordingManager

    private var isRecording = false
    private var frameTestingEnabled = true
    private var frameCounter = 0L
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    companion object {
        private const val TAG = "MainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value) {
                    permissionGranted = false
                }
            }
            if (!permissionGranted) {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupUI()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            activityResultLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun setupUI() {
        binding.btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        binding.switchTestMode.setOnCheckedChangeListener { _, isChecked ->
            frameTestingEnabled = isChecked
            if (::recordingManager.isInitialized) {
                recordingManager.stopRecording()
            }
            initializeRecordingManager()
        }

        updateTimestamp()
    }

    private fun updateTimestamp() {
        lifecycleScope.launch {
            while (true) {
                val timestamp = timestampFormat.format(Date())
                binding.tvTimestamp.text = "Frame: $frameCounter | $timestamp"
                delay(100) // Update every 100ms
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            initializeRecordingManager()
            
            // VideoCapture removed - using direct MediaCodec approach

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview
                )

                Log.d(TAG, "Camera started successfully")

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun initializeRecordingManager() {
        recordingManager = RecordingManager(
            context = this,
            frameTestingEnabled = frameTestingEnabled
        )

        recordingManager.setStatusUpdateCallback { status, filename, transitions ->
            runOnUiThread {
                binding.tvStatus.text = status
                binding.tvCurrentFile.text = if (filename.isNotEmpty()) {
                    getString(R.string.current_file, filename)
                } else ""
                binding.tvTransitions.text = getString(R.string.transition_count, transitions)
            }
        }
    }

    private fun startRecording() {
        if (!::recordingManager.isInitialized) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = true
        frameCounter = 0
        
        recordingManager.startRecording()
        
        // Connect camera to MediaCodec surface for recording
        val surface = recordingManager.videoEncoderSurface
        if (surface != null) {
            connectCameraToRecordingSurface(surface)
        } else {
            Log.e(TAG, "Video encoder surface is null")
        }

        binding.btnRecord.apply {
            text = getString(R.string.stop_recording)
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.green))
        }

        Log.d(TAG, "Recording started with frame testing: $frameTestingEnabled")
    }
    
    private fun connectCameraToRecordingSurface(recordingSurface: Surface) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            // Keep preview for UI
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            
            // Create a second preview that feeds the MediaCodec surface
            val recordingPreview = Preview.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .build()
            
            // Set surface provider for recording
            recordingPreview.setSurfaceProvider { request ->
                request.provideSurface(recordingSurface, cameraExecutor) { result ->
                    Log.d(TAG, "MediaCodec surface connected: $result")
                }
            }
            
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    recordingPreview
                )
                
                Log.d(TAG, "Camera connected to MediaCodec surface for recording")
                
            } catch (exc: Exception) {
                Log.e(TAG, "Failed to connect camera to recording surface", exc)
            }
            
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopRecording() {
        isRecording = false

        recordingManager.stopRecording()

        binding.btnRecord.apply {
            text = getString(R.string.start_recording)
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.red))
        }

        Log.d(TAG, "Recording stopped")
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::recordingManager.isInitialized) {
            recordingManager.stopRecording()
        }
        cameraExecutor.shutdown()
    }
}