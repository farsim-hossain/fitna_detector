#!/bin/bash
set -e

ADB="/home/farsim/Android/Sdk/platform-tools/adb"
VIDEO_REMOTE="/sdcard/wiqayah_test_showcase.mp4"
VIDEO_LOCAL="/media/farsim/8CE85861E8584C182/projects/fitna_detector/wiqayah_test_showcase.mp4"
VIDEO_ALIAS="/media/farsim/8CE85861E8584C182/projects/fitna_detector/fitna_detector_test_showcase.mp4"

echo "=== Preparing Pre-Grant State for Recording ==="
# Close all background apps for fresh clean start
$ADB shell am force-stop com.google.android.youtube || true
$ADB shell am force-stop com.android.chrome || true
$ADB shell am force-stop com.google.android.apps.youtube.music || true
$ADB shell am force-stop com.example.fitna_detector || true

# Set accessibility permission to disabled to show 1-Tap grant flow
$ADB shell settings put secure enabled_accessibility_services '""' || true
$ADB shell settings put secure accessibility_enabled 0 || true

# Start Wiqayah cold - opens directly into SplashScreen
$ADB shell am start -n com.example.fitna_detector/.MainActivity
sleep 2.0

echo "=== Starting screenrecord on emulator ==="
$ADB shell rm -f $VIDEO_REMOTE
$ADB shell screenrecord --time-limit 180 --bit-rate 6000000 $VIDEO_REMOTE &
RECORD_PID=$!
sleep 2

echo "=== Scene 1: Beautiful Wiqayah Splash Screen with Quran Verse ==="
sleep 6.0 # Ample time to read Wiqayah branding and Surah An-Nur 24:30

echo "=== Scene 2: Entering Dashboard in Setup Mode ==="
$ADB shell input tap 500 1725 # Tap 'Enter Shield Dashboard'
sleep 4.0 # Shows 'Single-Permission Setup' card and 'Enable Wiqayah (1-Tap Grant)' button

echo "=== Scene 3: Enabling Permission (1-Tap Grant Flow) ==="
$ADB shell input tap 500 285 # Tap 'Enable Wiqayah (1-Tap Grant)'
sleep 1.5
# Grant accessibility permission in system
$ADB shell settings put secure enabled_accessibility_services com.example.fitna_detector/com.example.fitna_detector.service.FitnaAccessibilityService
$ADB shell settings put secure accessibility_enabled 1
# Bring back Wiqayah
$ADB shell am start -n com.example.fitna_detector/.MainActivity
sleep 5.0 # Display the vibrant Green 'Shield Active (Single Permission Granted)' card & HUD

echo "=== Scene 4: False Positive Test 1 - Scientific Article (Clean) ==="
$ADB shell am force-stop com.google.android.youtube
sleep 0.5
$ADB shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/results?search_query=How+to+write+a+scientific+article" com.google.android.youtube
sleep 5.0

echo "=== Scene 5: False Positive Test 2 - Kali Linux Tutorial (Clean) ==="
$ADB shell am force-stop com.google.android.youtube
sleep 0.5
$ADB shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/results?search_query=How+to+install+Kali+Linux+on+VirtualBox" com.google.android.youtube
sleep 5.0

echo "=== Scene 6: False Positive Test 3 - Cooking Recipe (Clean) ==="
$ADB shell am force-stop com.google.android.youtube
sleep 0.5
$ADB shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/results?search_query=Whole+roast+chicken+cooking+recipe" com.google.android.youtube
sleep 5.0

echo "=== Scene 7: False Positive Test 4 - Surah Recitation (Clean) ==="
$ADB shell am force-stop com.google.android.youtube
sleep 0.5
$ADB shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/results?search_query=Surah+Al+Mulk+Full+Mishary+Alafasy" com.google.android.youtube
sleep 3.5
$ADB shell input tap 500 600 # Tap video to start recitation
sleep 5.0 # Quran recitation plays cleanly without any block
$ADB shell input keyevent 3 # Home
sleep 2.0

echo "=== Scene 8: True Positive Test 1 - Hindu Idols Search (Pause & Reflect: Idol) ==="
$ADB shell am force-stop com.google.android.youtube
sleep 0.5
$ADB shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/results?search_query=hindu+idols" com.google.android.youtube
sleep 3.5 # Wait for YouTube to load results and trigger Pause & Reflect
sleep 6.0 # 6 full seconds displaying 'Pause & Reflect: Idol / Religious sculpture detected'
# Dismiss to home
$ADB shell input keyevent 3
sleep 2.5

echo "=== Scene 9: True Positive Test 2 - Visual Sculpture in Chrome (Pause & Reflect: Idol) ==="
$ADB shell am force-stop com.android.chrome
sleep 0.5
$ADB shell am start -a android.intent.action.VIEW -d "https://en.wikipedia.org/wiki/Sculpture" com.android.chrome
sleep 4.0 # Wait for Wikipedia page load
# Scroll down to marble statue
$ADB shell input swipe 540 1800 540 700 400
sleep 2.0 # Wait for ML Kit to detect statue
sleep 6.0 # 6 full seconds displaying 'Pause & Reflect' over the visual sculpture
# Dismiss to home
$ADB shell input keyevent 3
sleep 2.5

echo "=== Scene 10: True Positive Test 3 - Romantic Kiss Scene (Pause & Reflect: Sensual) ==="
$ADB shell am force-stop com.google.android.youtube
sleep 0.5
$ADB shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/results?search_query=romantic+couple+kiss+scene" com.google.android.youtube
sleep 3.5 # Wait for YouTube to load results and trigger Pause & Reflect
sleep 6.0 # 6 full seconds displaying 'Pause & Reflect: Prohibited visual content detected'
# Dismiss to home
$ADB shell input keyevent 3
sleep 2.5

echo "=== Scene 11: True Positive Test 4 - Active Music Playback (Pause & Reflect: Audio) ==="
$ADB shell am force-stop com.google.android.youtube
sleep 0.5
$ADB shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/results?search_query=official+music+video" com.google.android.youtube
sleep 3.5 # Wait for search results
$ADB shell input tap 500 600 # Select and actively PLAY the music video!
sleep 1.5 # Wait for audio playback to start
sleep 6.0 # 6 full seconds displaying 'Pause & Reflect: Music playback detected' while song plays!
# Dismiss to home
$ADB shell input keyevent 3
sleep 2.5

echo "=== Scene 12: Return to Wiqayah Dashboard ==="
$ADB shell am start -n com.example.fitna_detector/.MainActivity
sleep 4.0

echo "=== Stopping screenrecord cleanly ==="
PID=$($ADB shell pidof screenrecord || true)
if [ -n "$PID" ]; then
    echo "Sending SIGINT to screenrecord (PID: $PID)..."
    $ADB shell kill -2 $PID || true
fi
wait $RECORD_PID 2>/dev/null || true
sleep 4

echo "=== Pulling recorded video ==="
$ADB pull $VIDEO_REMOTE "$VIDEO_LOCAL"
cp "$VIDEO_LOCAL" "$VIDEO_ALIAS"
echo "=== Done! Video saved to $VIDEO_LOCAL and $VIDEO_ALIAS ==="
