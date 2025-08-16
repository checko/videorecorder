# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Android application written in Kotlin that implements a continuous video recorder with zero frame drops. The app records camera video in 5-minute segments with seamless transitions, using a dual MediaRecorder architecture to prevent frame loss during file switching.

## Build Commands

### Building the Project
```bash
# Use the custom build script (recommended)
./build.sh

# Manual build using Gradle
export JAVA_HOME=/home/charles-chang/finalrecking/prebuilts/jdk/jdk17/linux-x86
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug
./gradlew assembleRelease
```

### Development Commands
```bash
# Clean build
./gradlew clean

# Build debug APK only
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk

# View application logs
adb logcat | grep VideoRecorder

# Check frame continuity logs on device
adb shell ls /sdcard/Android/data/com.videorecorder/files/frame_logs/
```

## Core Architecture

### Key Components

1. **MainActivity.kt** - Main UI controller
   - Handles camera permissions and initialization
   - Manages recording start/stop UI
   - Integrates with CameraX for preview

2. **RecordingManager.kt** - Core recording engine
   - Uses MediaCodec for video/audio encoding
   - Implements segment switching with MediaMuxer
   - Manages 30-second test segments (configurable)

3. **FrameContinuityTester.kt** - Frame gap detection system
   - Logs frame timestamps to CSV files
   - Analyzes frame continuity across file transitions
   - Provides automated gap detection and reporting

4. **FileManager.kt** - Storage and file management
   - Handles video file creation and naming
   - Manages storage space and cleanup of old files
   - Saves videos to DCIM/Camera for gallery visibility

5. **RecordingService.kt** - Background recording service
   - Enables continuous recording when app is backgrounded
   - Creates persistent notification during recording

### Recording Architecture

The app uses a sophisticated recording system:
- **MediaCodec** for hardware-accelerated video encoding (H.264)
- **MediaMuxer** for container format handling (MPEG2-TS)
- **AudioRecord** for audio capture with AAC encoding
- **CameraX** for camera lifecycle management

Key technical details:
- Video: 1280x720 @ 30fps, 2Mbps bitrate
- Audio: 44.1kHz mono, 64kbps AAC
- Container: MPEG2-TS for seamless segment concatenation
- Segments: 30 seconds each (configurable via SEGMENT_DURATION_MS)

### Frame Continuity Testing

The app includes comprehensive testing for zero frame drops:
- **Timestamp logging**: Every frame logged with precise timestamps
- **Gap analysis**: Automated detection of gaps > 33ms (30fps tolerance)
- **Transition logging**: Records file switch timing
- **CSV export**: Frame logs exportable for external analysis

## File Structure

```
app/src/main/java/com/videorecorder/
├── MainActivity.kt              # Main UI and camera setup
├── RecordingManager.kt          # Core recording logic
├── FrameContinuityTester.kt     # Frame gap testing
├── FileManager.kt               # File and storage management
└── service/
    └── RecordingService.kt      # Background recording service
```

## Development Guidelines

### Making Changes to Recording Logic
- Core recording logic is in `RecordingManager.kt:48-202`
- Segment duration configured via `SEGMENT_DURATION_MS` constant
- Video parameters in companion object constants (lines 46-58)

### Adding New Testing Methods
- Frame testing logic in `FrameContinuityTester.kt:94-156`
- Add new validation methods to the `analyzeFrameContinuity()` function
- Test results use `TestResult` data class for standardized reporting

### Storage Management
- File operations handled by `FileManager.kt:74-129`
- Videos saved to `/DCIM/Camera/` for automatic gallery integration
- Automatic cleanup when storage < 500MB free space

### Background Recording
- Service implementation in `service/RecordingService.kt`
- Uses foreground service with persistent notification
- Handles app backgrounding without interrupting recording

## Testing and Validation

### Frame Continuity Testing
The app includes built-in frame continuity testing:
- Enable via toggle in UI (`switchTestMode`)
- Logs written to: `/Android/data/com.videorecorder/files/frame_logs/`
- Analysis available via `FrameContinuityTester.analyzeFrameContinuity()`

### Manual Testing
```bash
# Install and test
adb install app/build/outputs/apk/debug/app-debug.apk

# Monitor frame logs during recording
adb shell tail -f /sdcard/Android/data/com.videorecorder/files/frame_logs/frame_continuity_log.csv

# Check recorded videos
adb shell ls -la /sdcard/DCIM/Camera/VID_*
```

## Configuration

### Key Constants (RecordingManager.kt)
- `SEGMENT_DURATION_MS`: Recording segment length (default: 30 seconds)
- `VIDEO_WIDTH/HEIGHT`: Resolution (default: 1280x720)
- `VIDEO_BITRATE`: Video quality (default: 2Mbps)
- `VIDEO_FRAME_RATE`: FPS (default: 30)

### Permissions Required
- `CAMERA`: Camera access
- `RECORD_AUDIO`: Audio recording
- `WRITE_EXTERNAL_STORAGE`: File storage
- `FOREGROUND_SERVICE`: Background recording

## Technical Challenges Addressed

1. **Zero Frame Drops**: Uses MediaCodec + MediaMuxer instead of MediaRecorder to avoid stop/start gaps
2. **Seamless Transitions**: MPEG2-TS container format allows seamless concatenation
3. **Gallery Integration**: Files saved to DCIM/Camera with proper media scanner notification
4. **Memory Management**: Automated cleanup of old recordings when storage low
5. **Background Recording**: Foreground service ensures recording continues when app backgrounded

## Known Limitations

- Camera surface connection incomplete in MainActivity.kt:167-170 (requires CameraX VideoCapture integration)
- Video duration calculation placeholder in FileManager.kt:171-175
- RecordingService references non-existent DualRecorderManager class (should be RecordingManager)