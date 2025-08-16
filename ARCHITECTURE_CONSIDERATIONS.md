# Architecture Considerations for TS Format Implementation

This document outlines the technical challenges encountered during the implementation of `.ts` format recording and the reasoning for the change in the architectural approach.

## Initial Plan: Dual `MediaMuxer` Architecture

The initial plan was to replace the dual `MediaRecorder` setup with a dual `MediaMuxer` setup. The idea was to have two `MediaMuxer` instances running in parallel, with one starting just before the other stops, to ensure a seamless transition between video segments.

### Challenges Encountered

During the implementation of this plan, several significant technical challenges were identified:

1.  **Incompatibility with CameraX `Recorder`:** The high-level `Recorder` API provided by CameraX is designed for ease of use and does not expose the raw encoded video and audio streams. The `MediaMuxer`, on the other hand, requires these raw streams to function. This fundamental incompatibility makes it impossible to use the CameraX `Recorder` with `MediaMuxer` directly.

2.  **Complexity of Manual `MediaCodec` Management:** To work around the `Recorder` limitation, the next logical step was to use the `MediaCodec` API directly. However, this introduces a great deal of complexity:
    *   **Manual Pipeline:** It requires building and managing the entire media encoding pipeline manually, for both video and audio.
    *   **CameraX Integration:** Integrating a manual `MediaCodec` pipeline with CameraX's `VideoCapture` use case is not a straightforward or officially supported pattern.
    *   **Synchronization:** The synchronization of video and audio streams, along with the presentation timestamps, becomes a manual and error-prone task.

3.  **Increased Complexity and Risk:** The dual `MediaMuxer` approach, while theoretically possible, would require a near-complete rewrite of the application's recording logic. This would mean abandoning the benefits of the high-level CameraX APIs and venturing into a much more complex and risk-prone implementation.

## Revised Plan: Single `MediaMuxer` with File Switching

Given the challenges of the dual-muxer approach, a simpler and more robust solution is proposed.

**Key API:** `MediaMuxer.setOutputNextFile(File)`

This method, available since API level 26, allows you to switch the output file of a `MediaMuxer` instance *while it is running*. This is the officially recommended way to handle segmented recording.

### Advantages of the Revised Plan

*   **Simplicity:** This approach is vastly simpler than managing two separate `MediaMuxer` instances and the complex synchronization between them.
*   **Resource Efficiency:** It is more resource-efficient as it only requires one `MediaMuxer` and one set of `MediaCodec`s to be active at any given time.
*   **Maintainability:** The resulting code will be cleaner, easier to understand, and less prone to bugs.

## Conclusion

The single-muxer approach with file switching is the more feasible, robust, and maintainable solution for implementing segmented recording in the `.ts` format. It aligns with modern Android development best practices and avoids the significant complexities and risks associated with the dual-muxer approach.
