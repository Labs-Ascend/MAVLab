#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/ambrose/Android/Sdk}}"
AVD_NAME="${AVD_NAME:-MAVLab_API_35}"
APP_ID="${APP_ID:-com.ascend.mavlab}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_PROJECT="$PROJECT_ROOT/mavlab-android"
APK_PATH="$ANDROID_PROJECT/app/build/outputs/apk/debug/app-debug.apk"
EMULATOR="$SDK_ROOT/emulator/emulator"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR_FLAGS="${EMULATOR_FLAGS:--gpu swiftshader_indirect -accel on -cores 2 -memory 2048 -no-snapshot -no-boot-anim}"

if [[ ! -x "$EMULATOR" ]]; then
  echo "Android emulator not found at: $EMULATOR" >&2
  exit 1
fi

if [[ ! -x "$ADB" ]]; then
  echo "adb not found at: $ADB" >&2
  exit 1
fi

if ! "$ADB" devices | grep -q '^emulator-[0-9]\+[[:space:]]\+device'; then
  echo "Starting emulator: $AVD_NAME"
  read -r -a emulator_flags <<< "$EMULATOR_FLAGS"
  nohup "$EMULATOR" -avd "$AVD_NAME" "${emulator_flags[@]}" >/tmp/mavlab-emulator.log 2>&1 &
  echo "Emulator PID: $!"
else
  echo "Emulator already running."
fi

echo "Waiting for emulator boot..."
"$ADB" wait-for-device
ADB_SERIAL="${ADB_SERIAL:-$("$ADB" devices | awk '/^emulator-[0-9]+[[:space:]]+device/ { print $1; exit }')}"

if [[ -z "$ADB_SERIAL" ]]; then
  echo "No running emulator was found by adb." >&2
  exit 1
fi

for _ in {1..90}; do
  boot_completed="$("$ADB" -s "$ADB_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  if [[ "$boot_completed" == "1" ]]; then
    break
  fi
  sleep 2
done

if [[ "$("$ADB" -s "$ADB_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
  echo "Emulator did not finish booting in time. Check /tmp/mavlab-emulator.log" >&2
  exit 1
fi

echo "Waiting for Android package manager..."
for _ in {1..60}; do
  if "$ADB" -s "$ADB_SERIAL" shell cmd package list packages android >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found. Building debug APK..."
  (cd "$ANDROID_PROJECT" && GRADLE_USER_HOME="$ANDROID_PROJECT/.gradle" ./gradlew assembleDebug)
fi

echo "Installing APK..."
for attempt in {1..3}; do
  if "$ADB" -s "$ADB_SERIAL" install -r "$APK_PATH"; then
    break
  fi
  if [[ "$attempt" == "3" ]]; then
    echo "APK install failed after $attempt attempts." >&2
    exit 1
  fi
  echo "Install failed; retrying in 5 seconds..."
  sleep 5
done

echo "Launching $APP_ID..."
"$ADB" -s "$ADB_SERIAL" shell monkey -p "$APP_ID" 1 >/dev/null

echo "Done. Emulator log: /tmp/mavlab-emulator.log"
