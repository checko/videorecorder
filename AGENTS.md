# Repository Guidelines

## Project Structure & Module Organization
- App module: `app/`
  - Source: `app/src/main/java/com/videorecorder/*` (Kotlin)
  - UI/Assets: `app/src/main/res/*` (layouts, drawables, strings, themes)
  - Manifest: `app/src/main/AndroidManifest.xml`
  - Config: `app/build.gradle`, `proguard-rules.pro`
- Key classes: `DualRecorderManager`, `FrameContinuityTester`, `FileManager`, `RecordingService`, `MainActivity`.
- See `ARCHITECTURE_CONSIDERATIONS.md` and `DUAL_MUXER_DESIGN.md` for design notes.

## Build, Test, and Development Commands
- Build debug APK: `./gradlew assembleDebug` (outputs `app/build/outputs/apk/debug/app-debug.apk`)
- Install on device: `./gradlew installDebug`
- Unit tests: `./gradlew testDebugUnitTest`
- Instrumented tests: `./gradlew connectedDebugAndroidTest` (requires device/emulator)
- Clean build: `./gradlew clean`
- One-step local build script: `./build.sh` (uses Java 17; see script for env vars)

## Coding Style & Naming Conventions
- Language: Kotlin (Android). Use Android Studio formatter; 4-space indentation.
- Classes/Objects: `UpperCamelCase`; functions/vars: `lowerCamelCase`; constants: `UPPER_SNAKE_CASE`.
- Packages: lowercase, no underscores (e.g., `com.videorecorder`).
- Resources: snake_case (e.g., `activity_main.xml`, `ic_videocam.xml`, `record_button_text`).
- ViewBinding is enabled; prefer binding over `findViewById`.
- Lint: Android Lint runs via Gradle; fix or suppress with rationale. No ktlint/detekt configured.

## Testing Guidelines
- Frameworks: JUnit4 (`test/`) and AndroidX + Espresso (`androidTest/`).
- Locations: place unit tests in `app/src/test/...` and instrumented tests in `app/src/androidTest/...`.
- Naming: mirror source and suffix with `Test` (e.g., `DualRecorderManagerTest.kt`).
- Coverage: aim to cover segment rollover behavior, timestamp continuity, and service lifecycle.

## Commit & Pull Request Guidelines
- Commits: imperative, concise subject (<=72 chars), include context in body when needed.
  - Examples: `Fix frame gap detection on segment rollover`, `Add TsPacketExtractor parsing tests`.
- PRs: include summary, linked issues, testing steps (device/API level), screenshots/logs if UI-affecting.
  - Checklist: builds cleanly, tests pass, no new warnings, updated docs if behavior changes.

## Security & Configuration Tips
- Requires Java 17 and Android SDK (compileSdk 34, minSdk 24). Set `sdk.dir` in `local.properties`.
- App uses camera, microphone, and storage; verify runtime permissions and background service behavior.
