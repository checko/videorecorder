# Dual MediaMuxer Zero-Gap Recording Design

## Overview
This design document outlines the approach for achieving zero-gap video recording using dual MediaMuxer instances with overlap recording. Based on implementation findings, we've evolved from packet-based approaches to a dual MediaMuxer strategy that eliminates frame loss through controlled overlap periods.

## Implementation Evolution

### Initial Approach: Pure Packet-Based (DEPRECATED)
❌ **Problem Discovered**: Creating TS packets by wrapping raw H.264/AAC data doesn't produce valid MPEG-TS containers.
- Missing PAT (Program Association Table) and PMT (Program Map Table)
- No proper PES (Packetized Elementary Stream) wrapping
- Results in unplayable files despite correct packet structure

## Final Approach: Dual MediaMuxer with Overlap Recording

### Evolution Summary
1. ❌ **Pure Packet-Based**: Complex, unplayable files
2. ❌ **Single MediaMuxer Rapid Switching**: 2-8 frame loss during switches
3. ✅ **Dual MediaMuxer with Overlap**: Zero frame loss with brief overlap

### Core Concept
✅ **Solution**: Use two MediaMuxers with overlap recording to eliminate gaps entirely.

```
MediaCodec → Dual MediaMuxer (MP4) → Overlap Switching → Zero-Gap Recording
```

### Key Findings from Research

#### MediaMuxer MPEG-TS Limitations
- **MediaMuxer does NOT support MPEG-TS output format** (only MP4, WebM, OGG)
- **MediaRecorder supports MPEG_2_TS** but cannot achieve zero-gap switching
- **MP4 format is acceptable** for zero-gap recording with proper switching strategy

#### Device Compatibility Concerns
- **Hardware-dependent**: Multiple MediaMuxer instances may not work on all devices
- **API 26+ recommended**: Better support for multiple simultaneous streams
- **Graceful fallback required**: Show user message if dual muxers not supported

### Zero-Gap Strategy: Overlap Instead of Gap
Instead of creating gaps during switching, create brief overlaps:
1. **Pre-create standby muxer** when approaching switch time
2. **Start writing to standby muxer** (overlap begins)
3. **Stop primary muxer** in background (overlap ends)
4. **Continue with new primary** seamlessly

## New Architecture Design: Dual MediaMuxer

### High-Level Data Flow
```
┌─────────────┐    ┌──────────────┐    ┌─────────────────┐
│  CameraX    │────│  MediaCodec  │────│  Dual Muxer     │
│  Preview    │    │   Encoder    │    │  Manager        │
└─────────────┘    └──────────────┘    └─────────────────┘
                                                │
                                                ▼
                                    ┌─────────────────────┐
                                    │   Overlap Manager   │
                                    │                     │
                                    │ ┌─────────────────┐ │
                                    │ │  MuxerA (30s)   │ │
                                    │ │  MuxerB (30s)   │ │
                                    │ │  Overlap (1s)   │ │
                                    │ └─────────────────┘ │
                                    └─────────────────────┘
                                                │
                                                ▼
                          ┌─────────────────────────────────┐
                          │    Seamless File Output        │
                          │                                 │
                          │  ┌──────┐ ┌──────┐ ┌──────┐   │
                          │  │File 1│ │File 2│ │File 3│   │
                          │  │ .mp4 │ │ .mp4 │ │ .mp4 │   │
                          │  │ 30.5s│ │ 30.8s│ │ 30.2s│   │
                          │  └──────┘ └──────┘ └──────┘   │
                          └─────────────────────────────────┘
```

## Implementation Details

### Lessons Learned

#### What Didn't Work:
1. **Raw Packet Creation**: Simply wrapping H.264/AAC in TS packet headers
   - Missing container metadata (PAT/PMT tables)
   - No PES packetization
   - Files unplayable by Android media players

#### What Works:
1. **MediaMuxer with MPEG2_TS**: Creates proper container structure
2. **Rapid File Switching**: Minimizes gaps between files
3. **Keyframe-based Transitions**: Ensures clean file boundaries

### New Implementation Strategy: Dual MediaMuxer

#### Phase 1: Device Compatibility Check
```kotlin
fun checkDualMuxerSupport(): Boolean {
    return try {
        // Test creating two MediaMuxer instances
        val testMuxerA = MediaMuxer("temp_a.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val testMuxerB = MediaMuxer("temp_b.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        // Cleanup test instances
        testMuxerA.release()
        testMuxerB.release()
        true
    } catch (e: Exception) {
        Log.w(TAG, "Device does not support dual MediaMuxer: ${e.message}")
        false
    }
}
```

#### Phase 2: Dual Muxer Setup
```kotlin
fun setupDualMuxers() {
    // Create both muxers at recording start
    muxerA = MediaMuxer("file_000.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    muxerB = MediaMuxer("file_001.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    
    // Configure tracks for both
    setupTracksForMuxer(muxerA)
    setupTracksForMuxer(muxerB)
    
    // Start with MuxerA as active
    activeMuxer = muxerA
    standbyMuxer = muxerB
}
```

#### Phase 3: Overlap Switching Logic
```kotlin
fun performOverlapSwitch() {
    // 1. Start writing to standby muxer (overlap begins)
    val previousMuxer = activeMuxer
    activeMuxer = standbyMuxer
    isOverlapping = true
    
    // 2. Continue writing to both muxers briefly
    // (frames written to both during overlap)
    
    // 3. Stop previous muxer in background after delay
    Handler().postDelayed({
        previousMuxer.stop()
        previousMuxer.release()
        createNextStandbyMuxer()
        isOverlapping = false
    }, 1000) // 1 second overlap
}
```

#### Phase 4: Error Handling & Fallback
```kotlin
fun handleMuxerFailure() {
    if (!dualMuxerSupported) {
        // Fallback to single muxer with minimal gaps
        showUserMessage("Device limitation: Using single muxer mode")
        useSingleMuxerMode()
    }
}
```

#### Phase 5: File Management
- Save as .mp4 files (MediaMuxer supported format)
- Files have variable duration (30-31 seconds) due to overlap
- Copy to DCIM/Camera for gallery visibility
- Notify MediaScanner for each completed file

## Timeline Analysis

### Dual Muxer Recording Timeline
```
Time:     0s    10s    20s    29s    30s    31s    40s    50s    59s    60s    61s
MuxerA:   [────────────Recording────────────][Overlap][Stop] [─────Prepare─────][Recording──
MuxerB:   [─────────────Prepare─────────────][Recording─────────────][Overlap][Stop]

Result:   ████████████████████████████████████████████████████████████████████████████████
          ↑                                                                              ↑
      Zero frame loss                                               Zero frame loss
```

### File Output Results
- **File A**: 30.5 seconds (30s + 0.5s overlap)
- **File B**: 30.8 seconds (30s + 0.8s overlap) 
- **File C**: 30.2 seconds (30s + 0.2s overlap)

**Overlap duration varies** based on:
- Device performance during muxer.stop()
- System load at switch time
- File size and storage speed

## Expected Benefits

### ✅ **Zero Frame Loss**
- No gaps between files
- Continuous recording stream
- All frames captured and saved

### ✅ **Device Compatibility**
- Graceful fallback for unsupported devices
- Clear user messaging about limitations
- Single muxer mode as backup

### ✅ **Playable Files**
- MP4 format supported by all players
- Proper container structure with metadata
- Gallery visibility and compatibility

### ⚠️ **Trade-offs**
- **Variable file duration**: 30-31 seconds instead of exactly 30s
- **Storage overhead**: ~1-3% due to overlaps
- **Processing overhead**: Dual encoding during switches

## Testing & Validation Plan

### Device Compatibility Testing
1. **Test dual muxer support** on app startup
2. **Log device model** and support status
3. **Measure performance impact** during overlap periods
4. **Validate fallback behavior** on unsupported devices

### Frame Continuity Validation
1. **Frame counting** across file boundaries
2. **Timestamp analysis** to detect gaps/overlaps
3. **Visual inspection** of transition points
4. **Automated gap detection** in recorded videos

### Performance Monitoring
1. **Memory usage** during dual muxer operation
2. **CPU overhead** during overlap periods
3. **Storage I/O impact** of concurrent writing
4. **Battery consumption** comparison

### Success Criteria
- ✅ **Zero frame loss** across all file transitions
- ✅ **Playable files** in standard media players
- ✅ **Graceful degradation** on incompatible devices
- ✅ **Gallery visibility** of recorded videos
- ✅ **Stable operation** for extended recording periods

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