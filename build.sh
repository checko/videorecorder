#!/bin/bash

# Android Video Recorder Build Script
# This script sets up the environment and builds the APK

set -e  # Exit on error

echo "=== Android Video Recorder Build Script ==="
echo "Setting up build environment..."

# Set Java environment
export JAVA_HOME=/home/charles-chang/finalrecking/prebuilts/jdk/jdk17/linux-x86
export PATH=$JAVA_HOME/bin:$PATH

echo "Using Java: $(java -version 2>&1 | head -1)"
echo "Android SDK: /home/charles-chang/Android/Sdk"

# Clean previous builds
echo "Cleaning previous builds..."
./gradlew clean

# Build debug APK
echo "Building debug APK..."
./gradlew assembleDebug

# Build release APK (optional)
echo "Building release APK..."
./gradlew assembleRelease

echo ""
echo "=== Build Complete ==="
echo "Debug APK: app/build/outputs/apk/debug/app-debug.apk"
echo "Release APK: app/build/outputs/apk/release/app-release.apk"
echo ""
echo "To install on device:"
echo "adb install app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "=== Features Implemented ==="
echo "✓ Dual MediaRecorder architecture for seamless file switching"
echo "✓ 5-minute video segments with 2-second overlap"
echo "✓ Frame continuity testing with timestamp logging"
echo "✓ Real-time frame gap detection"
echo "✓ Comprehensive file management"
echo "✓ Background recording service"
echo "✓ Camera permissions handling"
echo "✓ Storage management and cleanup"
echo ""
echo "=== Testing Methods Available ==="
echo "✓ Visual timestamp overlay"
echo "✓ Frame counter validation"
echo "✓ Audio-visual synchronization"
echo "✓ Moving object tracking"
echo "✓ Automated gap analysis"