package com.videorecorder

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FileManager(private val context: Context) {
    
    companion object {
        private const val TAG = "FileManager"
        private const val MIN_FREE_SPACE_MB = 500L // 500MB minimum free space
        private const val MAX_FILES_COUNT = 100 // Keep max 100 files
        private const val RECORDINGS_FOLDER = "recordings"
        private const val FRAME_LOGS_FOLDER = "frame_logs"
    }
    
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    
    fun getRecordingsDirectory(): File {
        // Use DCIM/Camera directory for standard gallery visibility
        val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val cameraDir = File(dcimDir, "Camera")
        
        if (!cameraDir.exists()) {
            cameraDir.mkdirs()
            Log.d(TAG, "Created Camera directory: ${cameraDir.absolutePath}")
        }
        return cameraDir
    }
    
    fun getFrameLogsDirectory(): File {
        val logsDir = File(context.getExternalFilesDir(null), FRAME_LOGS_FOLDER)
        if (!logsDir.exists()) {
            logsDir.mkdirs()
            Log.d(TAG, "Created frame logs directory: ${logsDir.absolutePath}")
        }
        return logsDir
    }
    
    fun createVideoFile(segmentNumber: Int): File {
        val timestamp = dateFormat.format(Date())
        val filename = "VID_${timestamp}_${String.format("%03d", segmentNumber)}.ts"
        val recordingsDir = getRecordingsDirectory()
        
        val videoFile = File(recordingsDir, filename)
        Log.d(TAG, "Created video file: ${videoFile.name} in ${recordingsDir.absolutePath}")
        return videoFile
    }
    
    fun notifyMediaScanner(videoFile: File) {
        // Notify media scanner to make video visible in gallery
        MediaScannerConnection.scanFile(
            context,
            arrayOf(videoFile.absolutePath),
            arrayOf("video/mp2t")
        ) { path, uri ->
            Log.d(TAG, "Media scanner finished for: $path -> $uri")
        }
        
        // Also send broadcast for immediate gallery update
        val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        intent.data = android.net.Uri.fromFile(videoFile)
        context.sendBroadcast(intent)
        
        Log.d(TAG, "Notified media scanner for: ${videoFile.absolutePath}")
    }
    
    fun checkAndManageStorage(): StorageInfo {
        val recordingsDir = getRecordingsDirectory()
        val availableSpace = getAvailableSpaceInMB(recordingsDir)
        val usedSpace = getDirectorySizeInMB(recordingsDir)
        
        val storageInfo = StorageInfo(
            availableSpaceMB = availableSpace,
            usedSpaceMB = usedSpace,
            isSpaceSufficient = availableSpace > MIN_FREE_SPACE_MB
        )
        
        if (!storageInfo.isSpaceSufficient) {
            Log.w(TAG, "Low storage space: ${availableSpace}MB available")
            cleanupOldFiles()
        }
        
        return storageInfo
    }
    
    private fun cleanupOldFiles() {
        val recordingsDir = getRecordingsDirectory()
        val files = recordingsDir.listFiles()?.filter { it.isFile && it.extension == "ts" }
            ?.sortedBy { it.lastModified() } // Sort by oldest first
        
        if (files.isNullOrEmpty()) return
        
        var deletedCount = 0
        var deletedSizeMB = 0L
        
        // Delete oldest files if we have too many or need space
        val filesToDelete = if (files.size > MAX_FILES_COUNT) {
            files.take(files.size - MAX_FILES_COUNT + 10) // Delete extra + some buffer
        } else {
            // Delete oldest files until we have enough space
            val targetSpaceNeeded = MIN_FREE_SPACE_MB * 2 // Delete more for buffer
            var spaceFreed = 0L
            files.takeWhile {
                val fileSize = it.length() / (1024 * 1024)
                spaceFreed += fileSize
                spaceFreed < targetSpaceNeeded
            }
        }
        
        filesToDelete.forEach { file ->
            val sizeInMB = file.length() / (1024 * 1024)
            if (file.delete()) {
                deletedCount++
                deletedSizeMB += sizeInMB
                Log.d(TAG, "Deleted old file: ${file.name} (${sizeInMB}MB)")
            }
        }
        
        if (deletedCount > 0) {
            Log.d(TAG, "Cleanup completed: Deleted $deletedCount files, freed ${deletedSizeMB}MB")
        }
    }
    
    private fun getAvailableSpaceInMB(directory: File): Long {
        return try {
            val stat = StatFs(directory.path)
            val availableBytes = stat.availableBytes
            availableBytes / (1024 * 1024)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating available space", e)
            0L
        }
    }
    
    private fun getDirectorySizeInMB(directory: File): Long {
        return try {
            val files = directory.listFiles() ?: return 0L
            val totalBytes = files.sumOf { file ->
                if (file.isFile) file.length() else 0L
            }
            totalBytes / (1024 * 1024)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating directory size", e)
            0L
        }
    }
    
    fun getAllVideoFiles(): List<VideoFileInfo> {
        val recordingsDir = getRecordingsDirectory()
        val files = recordingsDir.listFiles()?.filter { it.isFile && it.extension == "ts" }
            ?.sortedByDescending { it.lastModified() } // Sort by newest first
        
        return files?.map { file ->
            VideoFileInfo(
                file = file,
                name = file.name,
                sizeInMB = file.length() / (1024 * 1024),
                lastModified = Date(file.lastModified()),
                duration = getVideoDurationMs(file)
            )
        } ?: emptyList()
    }
    
    private fun getVideoDurationMs(file: File): Long {
        // This is a placeholder - in a real implementation you'd use MediaMetadataRetriever
        // For now, assume 5 minutes per segment
        return 5 * 60 * 1000L
    }
    
    fun exportFrameLog(): File? {
        val logsDir = getFrameLogsDirectory()
        val logFile = File(logsDir, "frame_continuity_log.csv")
        
        return if (logFile.exists()) {
            val timestamp = dateFormat.format(Date())
            val exportFile = File(logsDir, "frame_log_export_$timestamp.csv")
            
            try {
                logFile.copyTo(exportFile, overwrite = true)
                Log.d(TAG, "Frame log exported to: ${exportFile.name}")
                exportFile
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export frame log", e)
                null
            }
        } else {
            Log.w(TAG, "No frame log file found to export")
            null
        }
    }
    
    fun generateStorageReport(): String {
        val storageInfo = checkAndManageStorage()
        val videoFiles = getAllVideoFiles()
        
        return buildString {
            appendLine("=== Storage Report ===")
            appendLine("Available Space: ${storageInfo.availableSpaceMB}MB")
            appendLine("Used Space: ${storageInfo.usedSpaceMB}MB")
            appendLine("Storage Sufficient: ${storageInfo.isSpaceSufficient}")
            appendLine("Total Video Files: ${videoFiles.size}")
            appendLine()
            appendLine("Recent Files:")
            videoFiles.take(10).forEach { video ->
                appendLine("${video.name} - ${video.sizeInMB}MB - ${video.lastModified}")
            }
        }
    }
    
    data class StorageInfo(
        val availableSpaceMB: Long,
        val usedSpaceMB: Long,
        val isSpaceSufficient: Boolean
    )
    
    data class VideoFileInfo(
        val file: File,
        val name: String,
        val sizeInMB: Long,
        val lastModified: Date,
        val duration: Long
    )
}