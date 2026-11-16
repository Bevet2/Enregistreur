#!/usr/bin/env bash
# Lancé dans l'émulateur par android-emulator-runner.
set -uo pipefail
adb logcat -c
./gradlew --no-daemon -Pe2e connectedReleaseAndroidTest
status=$?
echo "================ LOGCAT (filtré) ================"
adb logcat -d -v time | grep -E "E2E|Transcription|sherpa|onnx|AndroidRuntime|FATAL|DEBUG|libc|crash|Abort|TestRunner" | tail -400
exit $status
