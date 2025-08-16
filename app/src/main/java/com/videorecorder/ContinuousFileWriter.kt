package com.videorecorder

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Continuously writes TS packets to files with seamless switching for zero-gap recording.
 * This class ensures no packets are lost during file transitions.
 */
class ContinuousFileWriter(
    private val fileManager: FileManager,
    private val segmentDurationMs: Long = 60_000L, // 1 minute default
    private val frameTester: FrameContinuityTester? = null
) {
    
    companion object {
        private const val TAG = "ContinuousFileWriter"
    }
    
    // Current file writing state
    private var currentWriter: FileOutputStream? = null
    private var currentFile: File? = null
    private var segmentStartTime: Long = 0
    private var segmentCount = 0
    private var isWriting = false
    
    // Statistics tracking
    private var totalPacketsWritten = 0L
    private var totalBytesWritten = 0L
    private var fileTransitions = 0
    private var lastTransitionTime = 0L
    
    // Callback for status updates
    private var onStatusUpdate: ((String, String, Int) -> Unit)? = null
    
    /**
     * Sets callback for status updates during writing
     */
    fun setStatusUpdateCallback(callback: (String, String, Int) -> Unit) {
        onStatusUpdate = callback
    }
    
    /**
     * Starts continuous writing process
     */
    fun startWriting() {
        if (isWriting) {
            Log.w(TAG, "Already writing, ignoring start request")
            return
        }
        
        isWriting = true
        segmentCount = 0
        totalPacketsWritten = 0L
        totalBytesWritten = 0L
        fileTransitions = 0
        
        startNewSegment()
        Log.d(TAG, "Started continuous TS packet writing")
    }
    
    /**
     * Stops continuous writing and closes current file
     */
    fun stopWriting() {
        if (!isWriting) return
        
        isWriting = false
        
        try {
            currentWriter?.flush()
            currentWriter?.close()
            currentWriter = null
            
            // Notify media scanner for the last file
            currentFile?.let { file ->
                fileManager.notifyMediaScanner(file)
                Log.d(TAG, "Final file completed: ${file.name}")
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Error closing file during stop", e)
        }
        
        logFinalStatistics()
        Log.d(TAG, "Stopped continuous writing")
    }
    
    /**
     * Writes a single TS packet to the current file.
     * Handles seamless file transitions when segment duration is reached.
     */
    fun writePacket(packet: TsPacket): Boolean {
        if (!isWriting) {
            Log.w(TAG, "Not in writing state, ignoring packet")
            return false
        }
        
        try {
            // Initialize first file if needed
            if (currentWriter == null) {
                startNewSegment()
            }
            
            // Write packet data immediately
            currentWriter?.write(packet.data)
            totalPacketsWritten++
            totalBytesWritten += packet.data.size
            
            // Log frame for continuity testing
            frameTester?.onFrameAvailable(
                getCurrentFileName(),
                false
            )
            
            // Check if it's time to switch to next segment
            val elapsed = (packet.timestamp - segmentStartTime) / 1_000_000 // ns to ms
            if (elapsed >= segmentDurationMs) {
                switchToNextSegment(packet.timestamp)
            }
            
            // Log progress periodically
            if (totalPacketsWritten % 5000 == 0L) {
                val fileSizeMB = totalBytesWritten / (1024 * 1024)
                Log.d(TAG, "Written $totalPacketsWritten packets, ${fileSizeMB}MB total")
            }
            
            return true
            
        } catch (e: IOException) {
            Log.e(TAG, "Error writing TS packet", e)
            return false
        }
    }
    
    /**
     * Starts a new file segment
     */
    private fun startNewSegment() {
        try {
            val outputFile = fileManager.createVideoFile(segmentCount)
            
            currentWriter = FileOutputStream(outputFile)
            currentFile = outputFile
            segmentStartTime = System.nanoTime()
            
            Log.d(TAG, "Started new segment: ${outputFile.name}")
            
            // Update status
            onStatusUpdate?.invoke(
                "Recording", 
                outputFile.name, 
                segmentCount
            )
            
        } catch (e: IOException) {
            Log.e(TAG, "Error creating new segment", e)
            throw e
        }
    }
    
    /**
     * Performs seamless switch to next file segment
     */
    private fun switchToNextSegment(transitionTime: Long) {
        val switchStartTime = System.currentTimeMillis()
        val currentFileName = getCurrentFileName()
        val previousFile = currentFile
        
        try {
            // Log transition start for frame continuity testing
            frameTester?.logFileTransition(
                currentFileName,
                "segment_${segmentCount + 1}.ts",
                switchStartTime
            )
            
            // Flush and close current file
            currentWriter?.flush()
            currentWriter?.close()
            
            // Notify media scanner for completed file
            previousFile?.let { file ->
                fileManager.notifyMediaScanner(file)
            }
            
            // Start next segment immediately
            segmentCount++
            startNewSegment()
            
            // Track transition timing
            val transitionDuration = System.currentTimeMillis() - switchStartTime
            lastTransitionTime = transitionDuration
            fileTransitions++
            
            Log.d(TAG, "File transition completed in ${transitionDuration}ms: " +
                    "${previousFile?.name} → ${getCurrentFileName()}")
            
            // Log transition completion for frame continuity testing
            frameTester?.onFrameAvailable(
                getCurrentFileName(),
                true // Mark as transition frame
            )
            
        } catch (e: IOException) {
            Log.e(TAG, "Error during file transition", e)
            // Try to continue with current file rather than failing completely
        }
    }
    
    /**
     * Gets current file name for logging
     */
    private fun getCurrentFileName(): String {
        return currentFile?.name ?: "segment_${segmentCount}.ts"
    }
    
    /**
     * Gets current segment number
     */
    fun getCurrentSegment(): Int = segmentCount
    
    /**
     * Gets writing statistics
     */
    fun getStatistics(): WritingStatistics {
        val currentFileSizeBytes = try {
            currentFile?.length() ?: 0L
        } catch (e: Exception) {
            0L
        }
        
        return WritingStatistics(
            totalPacketsWritten = totalPacketsWritten,
            totalBytesWritten = totalBytesWritten,
            fileTransitions = fileTransitions,
            currentSegment = segmentCount,
            averageTransitionTime = if (fileTransitions > 0) {
                lastTransitionTime // Simplified: just show last transition
            } else 0L,
            currentFileSizeBytes = currentFileSizeBytes,
            isWriting = isWriting
        )
    }
    
    /**
     * Forces a flush of current buffers
     */
    fun flush() {
        try {
            currentWriter?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Error flushing writer", e)
        }
    }
    
    /**
     * Logs final statistics when stopping
     */
    private fun logFinalStatistics() {
        val stats = getStatistics()
        val avgPacketsPerFile = if (fileTransitions > 0) {
            totalPacketsWritten / (fileTransitions + 1)
        } else totalPacketsWritten
        
        Log.d(TAG, "Final Statistics:")
        Log.d(TAG, "  Total packets written: ${stats.totalPacketsWritten}")
        Log.d(TAG, "  Total bytes written: ${stats.totalBytesWritten}")
        Log.d(TAG, "  File transitions: ${stats.fileTransitions}")
        Log.d(TAG, "  Average packets per file: $avgPacketsPerFile")
        Log.d(TAG, "  Last transition time: ${stats.averageTransitionTime}ms")
    }
}

/**
 * Statistics about the continuous writing process
 */
data class WritingStatistics(
    val totalPacketsWritten: Long,
    val totalBytesWritten: Long,
    val fileTransitions: Int,
    val currentSegment: Int,
    val averageTransitionTime: Long,
    val currentFileSizeBytes: Long,
    val isWriting: Boolean
)