# MPEG-TS Packet-Based Zero-Gap Recording Design

## Overview
This design leverages the packet-based structure of MPEG-TS containers to achieve true zero-gap video recording by writing individual TS packets continuously and switching files seamlessly without interrupting the encoding process.

## Core Concept

### MPEG-TS Structure
```
MPEG-TS Stream = Continuous sequence of 188-byte packets
┌─────────┬─────────┬─────────┬─────────┬─────────┬─────────┐
│Packet 1 │Packet 2 │Packet 3 │Packet 4 │Packet 5 │   ...   │
│188 bytes│188 bytes│188 bytes│188 bytes│188 bytes│         │
└─────────┴─────────┴─────────┴─────────┴─────────┴─────────┘

Each packet:
- Header: 4 bytes (sync byte + flags + PID + continuity counter)
- Payload: 184 bytes (video/audio elementary stream data)
- Self-contained unit that can be written independently
```

### Key Insight
Since TS packets are atomic units, we can:
1. Write packets continuously to disk
2. Switch output files between any two packets
3. Maintain perfect continuity without encoding interruption

## Architecture Design

### High-Level Data Flow
```
┌─────────────┐    ┌──────────────┐    ┌─────────────────┐
│   Camera2   │────│  MediaCodec  │────│   MediaMuxer    │
│   Surface   │    │   Encoder    │    │  (MPEG2-TS)     │
└─────────────┘    └──────────────┘    └─────────────────┘
                                                │
                                                ▼
                                   ┌─────────────────────┐
                                   │   TS Packet        │
                                   │   Extractor        │
                                   └─────────────────────┘
                                                │
                                                ▼
                          ┌─────────────────────────────────┐
                          │    Continuous File Writer      │
                          │                                 │
                          │  ┌──────┐ ┌──────┐ ┌──────┐   │
                          │  │File 1│ │File 2│ │File 3│   │
                          │  │0-1min│ │1-2min│ │2-3min│   │
                          │  └──────┘ └──────┘ └──────┘   │
                          └─────────────────────────────────┘
```

### Detailed Component Architecture

#### 1. MediaCodec Pipeline (Unchanged)
```kotlin
// Existing components remain the same:
- Camera2 captures frames → MediaCodec input Surface
- MediaCodec encodes H.264 video + AAC audio
- MediaMuxer outputs MPEG2-TS container format
```

#### 2. TS Packet Extractor (New Component)
```kotlin
class TsPacketExtractor {
    companion object {
        const val TS_PACKET_SIZE = 188
        const val TS_SYNC_BYTE = 0x47.toByte()
    }
    
    fun extractPackets(muxerOutput: ByteBuffer): List<TsPacket> {
        val packets = mutableListOf<TsPacket>()
        
        while (muxerOutput.remaining() >= TS_PACKET_SIZE) {
            val packetData = ByteArray(TS_PACKET_SIZE)
            muxerOutput.get(packetData)
            
            // Validate sync byte
            if (packetData[0] == TS_SYNC_BYTE) {
                packets.add(TsPacket(packetData, System.nanoTime()))
            }
        }
        
        return packets
    }
}

data class TsPacket(
    val data: ByteArray,
    val timestamp: Long
)
```

#### 3. Continuous File Writer (New Component)
```kotlin
class ContinuousFileWriter(
    private val fileManager: FileManager,
    private val segmentDurationMs: Long = 60_000L // 1 minute
) {
    private var currentWriter: FileOutputStream? = null
    private var segmentStartTime: Long = 0
    private var segmentCount = 0
    private var packetCount = 0L
    
    fun writePacket(packet: TsPacket) {
        // Initialize first file if needed
        if (currentWriter == null) {
            startNewSegment()
        }
        
        // Write packet immediately
        currentWriter?.write(packet.data)
        packetCount++
        
        // Check if segment duration reached
        val elapsed = (packet.timestamp - segmentStartTime) / 1_000_000 // ns to ms
        if (elapsed >= segmentDurationMs) {
            switchToNextSegment(packet.timestamp)
        }
    }
    
    private fun switchToNextSegment(transitionTime: Long) {
        // Log transition for frame continuity testing
        frameTester?.logFileTransition(
            "segment_${segmentCount}.ts",
            "segment_${segmentCount + 1}.ts",
            transitionTime
        )
        
        // Close current file
        currentWriter?.close()
        
        // Start next segment immediately
        segmentCount++
        startNewSegment()
        
        // Zero gap transition - next packet continues seamlessly
    }
    
    private fun startNewSegment() {
        val outputFile = fileManager.createVideoFile(segmentCount)
        currentWriter = FileOutputStream(outputFile)
        segmentStartTime = System.nanoTime()
        
        // Notify media scanner for previous file
        if (segmentCount > 0) {
            val previousFile = fileManager.createVideoFile(segmentCount - 1)
            fileManager.notifyMediaScanner(previousFile)
        }
    }
}
```

#### 4. Modified RecordingManager
```kotlin
class RecordingManager {
    private var packetExtractor: TsPacketExtractor? = null
    private var fileWriter: ContinuousFileWriter? = null
    
    // Modified muxer output processing
    private fun processMuxerOutput() {
        val outputBufferIndex = muxer?.dequeueOutputBuffer(info, 0) ?: -1
        
        if (outputBufferIndex >= 0) {
            val outputBuffer = muxer?.getOutputBuffer(outputBufferIndex)
            
            if (outputBuffer != null && info.size > 0) {
                // Extract TS packets from muxer output
                val packets = packetExtractor?.extractPackets(outputBuffer)
                
                // Write each packet individually
                packets?.forEach { packet ->
                    fileWriter?.writePacket(packet)
                    
                    // Frame continuity testing
                    frameTester?.onFrameAvailable(
                        "segment_${fileWriter?.currentSegment}.ts",
                        packet.timestamp
                    )
                }
            }
            
            muxer?.releaseOutputBuffer(outputBufferIndex)
        }
    }
}
```

## Timing and Synchronization

### Segment Duration Calculation
```kotlin
class SegmentTimer {
    companion object {
        // Target: 1 minute segments
        const val SEGMENT_DURATION_MS = 60_000L
        
        // Estimate packets per minute (depends on bitrate)
        fun estimatePacketsPerMinute(videoBitrate: Int, audioBitrate: Int): Long {
            val totalBitrate = videoBitrate + audioBitrate
            val bytesPerSecond = totalBitrate / 8
            val packetsPerSecond = bytesPerSecond / 188
            return packetsPerSecond * 60L
        }
    }
}
```

### Frame Continuity Validation
```kotlin
class TsFrameContinuityTester : FrameContinuityTester {
    fun validateTsTransition(file1: File, file2: File): Boolean {
        // Read last few packets from file1
        val lastPackets = readLastPackets(file1, count = 10)
        
        // Read first few packets from file2  
        val firstPackets = readFirstPackets(file2, count = 10)
        
        // Validate continuity counters and timestamps
        return validateContinuity(lastPackets, firstPackets)
    }
    
    private fun validateContinuity(
        lastPackets: List<TsPacket>, 
        firstPackets: List<TsPacket>
    ): Boolean {
        // Check continuity counter progression
        // Check timestamp progression
        // Ensure no gaps or duplicates
        return true // Implementation details
    }
}
```

## File Management

### File Naming Convention
```
VID_20240116_143022_001.ts  // Segment 1: 0-1 minute
VID_20240116_143022_002.ts  // Segment 2: 1-2 minutes  
VID_20240116_143022_003.ts  // Segment 3: 2-3 minutes
...
```

### Storage Optimization
```kotlin
class TsFileManager : FileManager {
    override fun createVideoFile(segmentNumber: Int): File {
        val timestamp = dateFormat.format(Date())
        val filename = "VID_${timestamp}_${String.format("%03d", segmentNumber)}.ts"
        return File(getRecordingsDirectory(), filename)
    }
    
    override fun notifyMediaScanner(videoFile: File) {
        // Use "video/mp2t" MIME type for .ts files
        MediaScannerConnection.scanFile(
            context,
            arrayOf(videoFile.absolutePath),
            arrayOf("video/mp2t")
        ) { path, uri ->
            Log.d(TAG, "TS file scanned: $path -> $uri")
        }
    }
}
```

## Advantages of This Design

### 1. True Zero-Gap Recording
- No encoding interruption during file switches
- Packet-level precision ensures no lost frames
- File transition is just changing output stream

### 2. Reliability
- Independent of MediaMuxer.setOutputNextFile() API availability
- Direct control over container format
- Minimal dependency on Android version differences

### 3. Performance
- Continuous encoding - no start/stop overhead
- Immediate packet writing - minimal buffering
- Efficient file I/O operations

### 4. Testability
- Frame continuity can be validated at packet level
- Precise timestamp tracking for gap detection
- TS format allows easy concatenation for testing

## Implementation Phases

### Phase 1: Core Infrastructure
1. Implement TsPacketExtractor class
2. Create ContinuousFileWriter class
3. Modify RecordingManager to use packet-based output

### Phase 2: Integration
1. Update MediaCodec output processing
2. Integrate with existing Camera2/MediaCodec pipeline
3. Update FileManager for .ts format handling

### Phase 3: Testing & Validation
1. Implement TS-specific frame continuity testing
2. Validate zero-gap transitions
3. Performance testing and optimization

### Phase 4: Polish
1. Error handling and recovery
2. Storage management for .ts files
3. UI updates for segment display

This design achieves the core requirement of zero-gap .ts recording by leveraging the fundamental structure of the MPEG-TS container format itself.