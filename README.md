# Android Continuous Camera Video Recorder

## Project Overview
An Android application that continuously records camera video and saves segments to separate files every 5 minutes without dropping any frames during file transitions.

## Technical Challenge
The main challenge is avoiding frame drops and gaps when transitioning between video files. Standard MediaRecorder implementations have inherent delays during stop/start cycles that can cause frame loss.

## Proposed Solution: Dual MediaRecorder Architecture

### Approach 1: Alternating MediaRecorders (Recommended)
Use two MediaRecorder instances that alternate recording:
- **Recorder A** records to file_001.mp4 (0-5 minutes)
- While Recorder A is finishing, **Recorder B** starts recording to file_002.mp4
- Seamless handoff without frame loss

```
Timeline:
[Recorder A: 0:00 -------- 5:00]
                    [Recorder B: 4:59 -------- 9:59]
                                        [Recorder A: 9:58 -------- 14:58]
```

## FRAME CONTINUITY TESTING METHODOLOGY

### Method 1: Timestamp Analysis (Primary Method)
**Visual Timestamp Overlay:**
- Embed high-precision timestamp (milliseconds) into each frame as overlay text
- Use `Canvas.drawText()` on camera preview surface
- Format: `YYYY-MM-DD HH:MM:SS.mmm - Frame: XXXXX`

**Testing Process:**
1. Record continuous video across multiple file transitions
2. Extract frames from consecutive video files at transition points
3. Compare timestamps of last frame in file N with first frame in file N+1
4. **Success criteria:** No timestamp gaps > 33ms (30fps = 33.33ms per frame)

```java
// Timestamp overlay implementation
Canvas canvas = surface.lockCanvas();
Paint paint = new Paint();
paint.setColor(Color.YELLOW);
paint.setTextSize(24);
String timestamp = String.format("%s - Frame: %d", 
    System.currentTimeMillis(), frameCounter++);
canvas.drawText(timestamp, 10, 50, paint);
surface.unlockCanvasAndPost(canvas);
```

### Method 2: Moving Object Tracking
**Controlled Environment Test:**
- Use rotating object (e.g., clock with second hand, rotating fan)
- Record object motion across file transitions
- Analyze motion continuity between files

**Implementation:**
- Place digital clock or metronome in camera view
- Record during file transitions
- Check for motion gaps or jumps in consecutive files

### Method 3: Audio Sync Verification
**Audio-Visual Synchronization:**
- Record audio track simultaneously (beep every second)
- Use audio as timestamp reference
- Verify video-audio sync remains consistent across file boundaries

### Method 4: Frame Counter Validation
**Sequential Frame Numbering:**
- Implement frame counter in app
- Log frame numbers with precise timestamps
- Compare frame sequence across file transitions

```java
public class FrameContinuityTester {
    private long frameCounter = 0;
    private FileWriter logWriter;
    
    public void onFrameAvailable(long timestamp) {
        // Log frame with timestamp
        logWriter.write(String.format("%d,%d,%s\n", 
            frameCounter++, timestamp, getCurrentFileName()));
    }
    
    public boolean analyzeFrameContinuity() {
        // Parse log file and detect gaps
        return checkForSequentialFrames();
    }
}
```

### Method 5: Hardware-Assisted Testing
**External Reference Clock:**
- Use external device (phone/tablet) showing precise time
- Point camera at external clock during recording
- Verify time continuity in recorded files

## Architecture Components

### 1. Camera Management
- **CameraX** for modern camera API with lifecycle awareness
- Single camera session shared between two VideoCapture use cases
- Custom `ImageAnalysis` use case for frame timestamp logging

### 2. Recording Manager with Testing
```java
public class DualRecorderManager {
    private MediaRecorder primaryRecorder;
    private MediaRecorder secondaryRecorder;
    private FrameContinuityTester tester;
    private boolean isTestingEnabled = true;
    
    public void startRecording() {
        if (isTestingEnabled) {
            tester.startFrameLogging();
        }
        // Start recording logic
    }
    
    public void switchRecorders() {
        long switchStartTime = System.nanoTime();
        // Perform seamless switch
        long switchEndTime = System.nanoTime();
        
        if (isTestingEnabled) {
            tester.logSwitchTime(switchStartTime, switchEndTime);
        }
    }
}
```

### 3. Frame Buffer Strategy with Monitoring
- Use `MediaMuxer` with custom buffering if needed
- Monitor buffer levels during transitions
- Alert if buffer underrun detected

## Testing Implementation Plan

### Phase 1: Basic Frame Logging
- Implement timestamp overlay system
- Add frame counter and logging
- Create basic analysis tools

### Phase 2: Automated Testing Suite
```java
public class RecordingTestSuite {
    public TestResult testFrameContinuity(int durationMinutes) {
        // Start recording with testing enabled
        // Perform multiple file transitions
        // Analyze results automatically
        return new TestResult(gaps, maxGap, averageTransitionTime);
    }
    
    public void generateTestReport() {
        // Create detailed report with statistics
        // Include frame gap analysis
        // Performance metrics
    }
}
```

### Phase 3: Real-time Monitoring
- Live frame gap detection
- Real-time alerts if gaps detected
- Automatic adjustment of overlap timing

## Test Validation Criteria

### Success Metrics:
1. **Zero frame gaps** > 33ms (30fps threshold)
2. **Transition time** < 100ms consistently
3. **Audio-video sync** maintained across files
4. **No visible motion jumps** in continuous motion tests
5. **Frame sequence** perfectly sequential

### Performance Benchmarks:
- Test duration: Minimum 30 minutes continuous recording
- File transitions: Every 5 minutes (6 transitions minimum)
- Test environments: Various lighting conditions, motion scenarios
- Device coverage: High-end, mid-range, and budget Android devices

## Automated Testing Tools

### Test Script Features:
```bash
#!/bin/bash
# Automated testing script
echo "Starting frame continuity test..."

# 1. Start app with testing mode
# 2. Record for specified duration
# 3. Analyze generated log files
# 4. Extract frames at transition points
# 5. Generate comprehensive report

python analyze_frame_continuity.py --input /sdcard/test_logs/ --output report.html
```

### Analysis Tools:
- Python script for timestamp analysis
- FFmpeg integration for frame extraction
- Automated report generation
- Statistical analysis of gaps and transitions

## Discussion Points

1. **Testing Frequency**: How often should we run continuity tests during development?
2. **Acceptable Tolerance**: Is 33ms (1 frame at 30fps) acceptable, or do we need zero tolerance?
3. **Test Automation**: Should testing be integrated into CI/CD pipeline?
4. **Device Coverage**: Which specific device models should we prioritize for testing?
5. **Real-world Scenarios**: What specific use cases should we test (low light, fast motion, etc.)?

## Questions for Discussion

- What's your target frame rate (30fps, 60fps)?
- Do you need testing to be automated or manual verification is acceptable?
- Should the app include built-in frame continuity monitoring for end users?
- What's the acceptable file size for 5-minute segments?

Please review this enhanced approach with comprehensive testing methodology!