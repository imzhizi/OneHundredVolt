#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB_SERIAL="${ADB_SERIAL:-emulator-5554}"
PACKAGE="${PACKAGE:-com.imzhizi.onehundredvolt}"
ACTIVITY="${ACTIVITY:-com.ohv.android.MainActivity}"
STARTUP_MAX_WAIT_SECONDS="${STARTUP_MAX_WAIT_SECONDS:-30}"

export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export https_proxy="${https_proxy:-http://127.0.0.1:7890}"
export http_proxy="${http_proxy:-http://127.0.0.1:7890}"
export all_proxy="${all_proxy:-socks5://127.0.0.1:7890}"

cd "$REPO_ROOT"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "JDK 21 not found: $JAVA_HOME" >&2
    exit 1
fi

echo "[Android] running shared unit tests and assembling Debug APK"
./gradlew :shared:testDebugUnitTest :androidApp:assembleDebug

APK_PATH="$(find androidApp/build/outputs/apk/debug -maxdepth 1 -type f -name '*.apk' -print -quit)"
if [[ -z "$APK_PATH" ]]; then
    echo "Debug APK not found under androidApp/build/outputs/apk/debug" >&2
    exit 1
fi

echo "[Android] installing $APK_PATH on $ADB_SERIAL"
adb -s "$ADB_SERIAL" install -r "$APK_PATH"
adb -s "$ADB_SERIAL" shell am force-stop "$PACKAGE"
adb -s "$ADB_SERIAL" shell am start -n "$PACKAGE/$ACTIVITY"

ARTIFACT_DIR="$REPO_ROOT/build/test-artifacts/android"
mkdir -p "$ARTIFACT_DIR"
for ((second = 0; second < STARTUP_MAX_WAIT_SECONDS; second++)); do
    adb -s "$ADB_SERIAL" shell uiautomator dump /sdcard/ohv-window.xml >/dev/null 2>&1 || true
    adb -s "$ADB_SERIAL" exec-out cat /sdcard/ohv-window.xml > "$ARTIFACT_DIR/debug-launch.uia.xml" || true
    if rg -q 'text="(登录爱发电账户|播放列表|设置)"' "$ARTIFACT_DIR/debug-launch.uia.xml"; then
        break
    fi
    sleep 1
done
sleep 1
adb -s "$ADB_SERIAL" exec-out screencap -p > "$ARTIFACT_DIR/debug-launch.png"

echo "[Android] launch evidence: $ARTIFACT_DIR/debug-launch.png"
echo "[Android] UI dump: $ARTIFACT_DIR/debug-launch.uia.xml"
