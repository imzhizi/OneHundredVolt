#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SIMULATOR_ID="${SIMULATOR_ID:-}"
CONFIGURATION="${CONFIGURATION:-Debug}"
DERIVED_DATA="${DERIVED_DATA:-$REPO_ROOT/build/DerivedDataTest}"
BUNDLE_ID="${BUNDLE_ID:-com.imzhizi.OneHundredVolt}"
STARTUP_WAIT_SECONDS="${STARTUP_WAIT_SECONDS:-8}"

export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode-beta.app/Contents/Developer}"
export https_proxy="${https_proxy:-http://127.0.0.1:7890}"
export http_proxy="${http_proxy:-http://127.0.0.1:7890}"
export all_proxy="${all_proxy:-socks5://127.0.0.1:7890}"

cd "$REPO_ROOT"

if [[ -z "$SIMULATOR_ID" ]]; then
    SIMULATOR_ID="$(xcrun simctl list devices | awk '/\(Booted\)/ { id = $(NF-1); gsub(/[()]/, "", id); print id; exit }')"
fi
if [[ -z "$SIMULATOR_ID" ]]; then
    echo "No booted simulator found. Set SIMULATOR_ID to an available device UDID." >&2
    exit 1
fi

echo "[iOS] building Shared.xcframework ($CONFIGURATION)"
./scripts/build-shared-framework.sh "$CONFIGURATION"

echo "[iOS] building and installing on simulator $SIMULATOR_ID"
xcodebuild \
    -project iosApp/OneHundredVolt.xcodeproj \
    -scheme OneHundredVolt \
    -configuration "$CONFIGURATION" \
    -sdk iphonesimulator \
    -destination "platform=iOS Simulator,id=$SIMULATOR_ID" \
    -derivedDataPath "$DERIVED_DATA" \
    build

APP_PATH="$DERIVED_DATA/Build/Products/${CONFIGURATION}-iphonesimulator/OneHundredVolt.app"
if [[ ! -d "$APP_PATH" ]]; then
    echo "Built app not found: $APP_PATH" >&2
    exit 1
fi

xcrun simctl install "$SIMULATOR_ID" "$APP_PATH"
xcrun simctl terminate "$SIMULATOR_ID" "$BUNDLE_ID" 2>/dev/null || true
xcrun simctl launch "$SIMULATOR_ID" "$BUNDLE_ID"
sleep "$STARTUP_WAIT_SECONDS"

ARTIFACT_DIR="$REPO_ROOT/build/test-artifacts/ios"
mkdir -p "$ARTIFACT_DIR"
ARTIFACT_BASENAME="$(printf '%s' "$CONFIGURATION" | tr '[:upper:]' '[:lower:]')-launch"
xcrun simctl io "$SIMULATOR_ID" screenshot "$ARTIFACT_DIR/$ARTIFACT_BASENAME.png"

echo "[iOS] launch evidence: $ARTIFACT_DIR/$ARTIFACT_BASENAME.png"
