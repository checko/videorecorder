package com.videorecorder

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
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
        // Use app-specific external storage (no permissions needed)
        val appMoviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val recordingsDir = File(appMoviesDir, "VideoRecorder")
        
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
            Log.d(TAG, "Created recordings directory: ${recordingsDir.absolutePath}")
        }
        return recordingsDir
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
        val baseFilename = "VID_${timestamp}_${String.format("%03d", segmentNumber)}"
        val recordingsDir = getRecordingsDirectory()
        
        // Generate unique filename to avoid conflicts
        var counter = 0
        var videoFile: File
        do {
            val suffix = if (counter == 0) "" else "_$counter"
            val filename = "$baseFilename$suffix.mp4"  // Changed to .mp4 since we're using MediaMuxer MP4 format
            videoFile = File(recordingsDir, filename)
            counter++
        } while (videoFile.exists() && counter < 1000) // Prevent infinite loop
        
        Log.d(TAG, "Created video file: ${videoFile.name} in ${recordingsDir.absolutePath}")
        
        return videoFile
    }
    
    private fun addToMediaStore(videoFile: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp2t")
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            }
            
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            Log.d(TAG, "Added to MediaStore: ${videoFile.name} -> $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add to MediaStore", e)
        }
    }
    
    fun notifyMediaScanner(videoFile: File) {
        // Copy file to public gallery location for visibility
        copyToGallery(videoFile)
        
        // Simple media scanner notification for original file
        MediaScannerConnection.scanFile(
            context,
            arrayOf(videoFile.absolutePath),
            arrayOf("video/mp2t")
        ) { path, uri ->
            Log.d(TAG, "Media scanner finished for: $path -> $uri")
        }
        
        Log.d(TAG, "Notified media scanner for: ${videoFile.absolutePath}")
    }
    
    private fun copyToGallery(sourceFile: File) {
        try {
            // Use MediaStore to create a file visible in gallery
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, sourceFile.name) // Keep original .mp4 name
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Video.Media.IS_PENDING, 0) // Mark as not pending for immediate visibility
            }
            
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    sourceFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d(TAG, "Video copied to gallery: ${sourceFile.name} -> $uri")
            } else {
                Log.e(TAG, "Failed to create MediaStore entry")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy video to gallery", e)
        }
    }
    
    private fun createGalleryCompatibleCopy(tsFile: File) {
        try {
            // Create an .mp4 file that points to the same content for gallery visibility
            val mp4Name = tsFile.name.replace(".ts", ".mp4")
            val mp4File = File(tsFile.parent, mp4Name)
            
            // Copy the file with .mp4 extension
            tsFile.copyTo(mp4File, overwrite = true)
            
            // Notify media scanner about the .mp4 file
            MediaScannerConnection.scanFile(
                context,
                arrayOf(mp4File.absolutePath),
                arrayOf("video/mp4")
            ) { path, uri ->
                Log.d(TAG, "MP4 copy added to gallery: $path -> $uri")
            }
            
            Log.d(TAG, "Created gallery-compatible copy: ${mp4File.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create gallery-compatible copy", e)
        }
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
        val files = recordingsDir.listFiles()?.filter { it.isFile && it.extension == "mp4" } // Changed from "ts" to "mp4"
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
        val files = recordingsDir.listFiles()?.filter { it.isFile && it.extension == "mp4" } // Changed from "ts" to "mp4"
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