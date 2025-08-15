package com.videorecorder

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class FrameContinuityTester(private val logDir: File) {
    private var frameCounter: Long = 0
    private var logWriter: FileWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var isTestingEnabled = false
    private var currentFileName = ""
    
    companion object {
        private const val TAG = "FrameContinuityTester"
        private const val LOG_FILE_NAME = "frame_continuity_log.csv"
    }
    
    fun startFrameLogging() {
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        
        try {
            val logFile = File(logDir, LOG_FILE_NAME)
            logWriter = FileWriter(logFile, true)
            
            // Write CSV header if file is new
            if (logFile.length() == 0L) {
                logWriter?.write("timestamp,frame_number,filename,system_time_ms,transition_flag\n")
            }
            
            frameCounter = 0
            isTestingEnabled = true
            Log.d(TAG, "Frame logging started")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start frame logging", e)
        }
    }
    
    fun stopFrameLogging() {
        isTestingEnabled = false
        try {
            logWriter?.close()
            logWriter = null
            Log.d(TAG, "Frame logging stopped")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to stop frame logging", e)
        }
    }
    
    fun onFrameAvailable(filename: String, isTransition: Boolean = false) {
        if (!isTestingEnabled || logWriter == null) return
        
        val currentTime = System.currentTimeMillis()
        val timestamp = dateFormat.format(Date(currentTime))
        val transitionFlag = if (isTransition) "1" else "0"
        
        try {
            logWriter?.write("$timestamp,$frameCounter,$filename,$currentTime,$transitionFlag\n")
            logWriter?.flush()
            
            if (frameCounter % 100 == 0L) { // Log every 100 frames
                Log.d(TAG, "Frame logged: $frameCounter - $filename")
            }
            
            frameCounter++
            currentFileName = filename
        } catch (e: IOException) {
            Log.e(TAG, "Failed to log frame", e)
        }
    }
    
    fun logFileTransition(fromFile: String, toFile: String, transitionTimeMs: Long) {
        if (!isTestingEnabled || logWriter == null) return
        
        val timestamp = dateFormat.format(Date())
        try {
            logWriter?.write("$timestamp,TRANSITION,$fromFile->$toFile,$transitionTimeMs,TRANSITION\n")
            logWriter?.flush()
            Log.d(TAG, "File transition logged: $fromFile -> $toFile (${transitionTimeMs}ms)")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to log file transition", e)
        }
    }
    
    fun getFrameCount(): Long = frameCounter
    
    fun getCurrentFileName(): String = currentFileName
    
    fun analyzeFrameContinuity(): TestResult {
        val logFile = File(logDir, LOG_FILE_NAME)
        if (!logFile.exists()) {
            return TestResult(emptyList(), 0, 0.0)
        }
        
        val gaps = mutableListOf<FrameGap>()
        var maxGap = 0L
        var totalTransitionTime = 0L
        var transitionCount = 0
        
        try {
            val lines = logFile.readLines()
            var previousTime = 0L
            var previousFrame = -1L
            
            for (line in lines.drop(1)) { // Skip header
                if (line.contains("TRANSITION")) {
                    val parts = line.split(",")
                    if (parts.size >= 4) {
                        val transitionTime = parts[3].toLongOrNull() ?: 0
                        totalTransitionTime += transitionTime
                        transitionCount++
                    }
                    continue
                }
                
                val parts = line.split(",")
                if (parts.size >= 4) {
                    val frameNumber = parts[1].toLongOrNull() ?: continue
                    val systemTime = parts[3].toLongOrNull() ?: continue
                    
                    if (previousTime > 0) {
                        val timeDiff = systemTime - previousTime
                        val frameDiff = frameNumber - previousFrame
                        
                        // Detect gaps (assuming 30fps = 33ms per frame)
                        if (timeDiff > 50 && frameDiff > 1) { // Allow some tolerance
                            val gap = FrameGap(previousTime, systemTime, timeDiff, frameDiff)
                            gaps.add(gap)
                            if (timeDiff > maxGap) {
                                maxGap = timeDiff
                            }
                        }
                    }
                    
                    previousTime = systemTime
                    previousFrame = frameNumber
                }
            }
            
            val averageTransitionTime = if (transitionCount > 0) {
                totalTransitionTime.toDouble() / transitionCount
            } else 0.0
            
            Log.d(TAG, "Analysis complete: ${gaps.size} gaps found, max gap: ${maxGap}ms")
            return TestResult(gaps, maxGap, averageTransitionTime)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to analyze frame continuity", e)
            return TestResult(emptyList(), 0, 0.0)
        }
    }
    
    data class FrameGap(
        val startTime: Long,
        val endTime: Long,
        val durationMs: Long,
        val framesLost: Long
    )
    
    data class TestResult(
        val gaps: List<FrameGap>,
        val maxGapMs: Long,
        val averageTransitionTimeMs: Double
    ) {
        val isSuccess: Boolean
            get() = gaps.isEmpty() || maxGapMs <= 33 // 30fps tolerance
            
        val totalFramesLost: Long
            get() = gaps.sumOf { it.framesLost }
    }
}