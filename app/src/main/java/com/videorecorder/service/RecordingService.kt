package com.videorecorder.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.videorecorder.RecordingManager
import com.videorecorder.R

class RecordingService : Service() {
    private lateinit var recordingManager: RecordingManager
    private val binder = RecordingBinder()
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "VideoRecordingChannel"
        private const val CHANNEL_NAME = "Video Recording"
    }
    
    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        recordingManager = RecordingManager(this, true)
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        return START_STICKY
    }
    
    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Video Recording")
            .setContentText("Continuous recording in progress...")
            .setSmallIcon(R.drawable.ic_videocam)
            .setOngoing(true)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Channel for video recording notifications"
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
    
    fun startRecording() {
        recordingManager.startRecording()
    }
    
    fun stopRecording() {
        recordingManager.stopRecording()
        stopSelf()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        recordingManager.stopRecording()
    }
}