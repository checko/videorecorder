# Notes on the TS Format Implementation

This document details the design and implementation process for changing the video recording format to MPEG-2 Transport Stream (`.ts`) in the `feature/ts-output-format` branch.

## Goal

The primary objective of this task was to modify the application to save video recordings in the `.ts` container format instead of `.mp4`, while maintaining the seamless, no-gap recording feature.

## Evolution of the Design

### Original Thought: Dual `MediaMuxer`

The initial plan was to mirror the existing dual-recorder architecture, but with `MediaMuxer` instances instead of `MediaRecorder`. The idea was to have two `MediaMuxer`s, where one would start recording just before the other finished, to ensure a seamless transition.

This approach was abandoned due to a fundamental incompatibility between the high-level CameraX `Recorder` API and the low-level `MediaMuxer` API. The `Recorder` API does not expose the raw encoded data streams that `MediaMuxer` requires.

### Revised Approach: Single `MediaMuxer` with File Switching

The plan was revised to use a single `MediaMuxer` instance and leverage its `setOutputNextFile()` method (available since API 26) to switch to a new file segment without stopping the recording. This is the officially recommended approach for segmented recording.

This approach required a more significant architectural change, moving from the high-level CameraX APIs to the lower-level `MediaCodec` and `AudioRecord` APIs.

## Implementation Details

### Finished Parts

*   **Branch Creation:** A new branch, `feature/ts-output-format`, was created for this work.
*   **`minSdk` Update:** The `minSdk` in `app/build.gradle` was changed from 24 to 30 to allow the use of `MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG2TS`.
*   **File Management:** The `FileManager.kt` was updated to use the `.ts` file extension and the corresponding `video/mp2t` MIME type.
*   **`RecordingManager` Creation:** The `DualRecorderManager.kt` was deleted and replaced with a new `RecordingManager.kt`. This new class was designed to manage a single `MediaMuxer` instance.
*   **Basic Codec and Muxer Setup:** The `RecordingManager.kt` includes the basic setup for:
    *   `MediaCodec` for both video and audio.
    *   `MediaMuxer` configured for `.ts` output.
    *   `AudioRecord` for capturing raw audio from the microphone.

### Unfinished and Problematic Parts

The implementation is **incomplete and not functional**. The primary blocker is the complexity of integrating the low-level media APIs with the CameraX framework.

1.  **The Core Problem: Connecting the Camera to `MediaCodec`**
    *   The most significant challenge is providing the camera's video output to the `MediaCodec`'s input `Surface`. While `RecordingManager` creates a `Surface` from the video `MediaCodec`, the `MainActivity` does not have a straightforward way to tell the CameraX `VideoCapture` use case to send its data to this `Surface`.
    *   This is an advanced use case that falls outside the typical, simplified workflows that CameraX is designed for.

2.  **Incomplete `MediaCodec` Data Flow:**
    *   The logic for processing the output from the `MediaCodec`s and writing it to the `MediaMuxer` is not fully implemented or tested. The callbacks for the video `MediaCodec` are missing.
    *   Ensuring correct presentation timestamps for the audio and video streams is a complex task that has not been fully addressed.

3.  **`MainActivity` Integration:**
    *   The `MainActivity.kt` has been updated to use the new `RecordingManager`, but the camera setup logic is incomplete due to the core problem mentioned above.

## Technical Considerations for Future Work

To complete this implementation, a deep understanding of the following Android media APIs is required. It is highly recommended to consult the official Android documentation and relevant examples.

*   **`MediaCodec`:** This is the core component for encoding video and audio. Its lifecycle, buffer management (both input and output), and callback handling are complex. Refer to the [MediaCodec documentation](https://developer.android.com/reference/android/media/MediaCodec).

*   **`MediaMuxer`:** This is responsible for writing the encoded data into the `.ts` container format. The key method for this implementation is `setOutputNextFile()`. Refer to the [MediaMuxer documentation](https://developer.android.com/reference/android/media/MediaMuxer).

*   **`AudioRecord`:** This is used to capture raw audio from the microphone. Refer to the [AudioRecord documentation](https://developer.android.com/reference/android/media/AudioRecord).

*   **CameraX and Custom Sinks:** For connecting the camera output to a `MediaCodec`, you will likely need to investigate more advanced CameraX features, such as creating a custom `Surface` provider or using a lower-level CameraX API if available. The official [CameraX documentation](https://developer.android.com/training/camerax) and code samples are the best resources for this.

*   **Audio/Video Synchronization:** Pay close attention to the presentation timestamps (`presentationTimeUs`) for each buffer of data written to the `MediaMuxer`. Incorrect timestamps will result in playback issues.
