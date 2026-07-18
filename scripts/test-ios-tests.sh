#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SIMULATOR_ID="${SIMULATOR_ID:-}"
CONFIGURATION="${CONFIGURATION:-Debug}"
DERIVED_DATA="${DERIVED_DATA:-$REPO_ROOT/build/DerivedDataTests}"
ARTIFACT_DIR="${ARTIFACT_DIR:-$REPO_ROOT/build/test-artifacts/ios}"
TEST_TIMEOUT_SECONDS="${TEST_TIMEOUT_SECONDS:-300}"
RESULT_GRACE_SECONDS="${RESULT_GRACE_SECONDS:-15}"
POST_STOP_GRACE_SECONDS="${POST_STOP_GRACE_SECONDS:-10}"

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

mkdir -p "$ARTIFACT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
REQUESTED_RESULT_BUNDLE="${RESULT_BUNDLE:-}"
ARTIFACT_RESULT_BUNDLE="$ARTIFACT_DIR/ios-tests-$STAMP.xcresult"
LOG_FILE="${LOG_FILE:-$ARTIFACT_DIR/ios-tests-$STAMP.log}"
SUMMARY_FILE="$ARTIFACT_DIR/ios-tests-$STAMP.summary.txt"

XCODEBUILD_ARGS=(
    -project iosApp/OneHundredVolt.xcodeproj
    -scheme OneHundredVolt
    -configuration "$CONFIGURATION"
    -sdk iphonesimulator
    -destination "platform=iOS Simulator,id=$SIMULATOR_ID"
    -derivedDataPath "$DERIVED_DATA"
    CODE_SIGNING_ALLOWED=NO
)
if [[ -n "$REQUESTED_RESULT_BUNDLE" ]]; then
    XCODEBUILD_ARGS+=(-resultBundlePath "$REQUESTED_RESULT_BUNDLE")
fi
XCODEBUILD_ARGS+=(test)
TEST_START_EPOCH="$(date +%s)"

echo "[iOS] running tests on simulator $SIMULATOR_ID"
xcodebuild "${XCODEBUILD_ARGS[@]}" > "$LOG_FILE" 2>&1 &
BUILD_PID=$!
START_SECONDS="$SECONDS"

resolve_result_bundle() {
    local result_bundle="$REQUESTED_RESULT_BUNDLE"
    if [[ -z "$result_bundle" ]]; then
        result_bundle="$(find "$DERIVED_DATA/Logs/Test" -maxdepth 1 -type d -name '*.xcresult' -print 2>/dev/null | while IFS= read -r candidate; do
            mtime="$(stat -f %m "$candidate/Info.plist" 2>/dev/null || echo 0)"
            if [[ "$mtime" =~ ^[0-9]+$ ]] && (( mtime >= TEST_START_EPOCH )); then
                printf '%s\n' "$candidate"
            fi
        done | sort | tail -1)"
    fi
    printf '%s\n' "$result_bundle"
}

summary_result() {
    local result_bundle
    result_bundle="$(resolve_result_bundle)"
    [[ -f "$result_bundle/Info.plist" ]] || return 1
    xcrun xcresulttool get test-results summary --path "$result_bundle" 2>/dev/null
}

wait_for_summary() {
    local deadline=$((SECONDS + RESULT_GRACE_SECONDS))
    local summary
    while (( SECONDS < deadline )); do
        summary="$(summary_result || true)"
        if [[ -n "$summary" ]]; then
            printf '%s\n' "$summary"
            return 0
        fi
        sleep 2
    done
    return 1
}

publish_result() {
    local result_bundle
    result_bundle="$(resolve_result_bundle)"
    if [[ -f "$result_bundle/Info.plist" ]]; then
        rm -rf "$ARTIFACT_RESULT_BUNDLE"
        cp -R "$result_bundle" "$ARTIFACT_RESULT_BUNDLE"
        return 0
    fi
    return 1
}

publish_log_evidence() {
    {
        printf 'result=Passed\n'
        rg 'Test run with [0-9]+ tests .* passed' "$LOG_FILE" | tail -1
        printf 'test_log=%s\n' "$LOG_FILE"
        printf 'result_bundle=unavailable (Xcode beta did not finalize Info.plist)\n'
    } > "$SUMMARY_FILE"
}

report_result_artifact() {
    if [[ -f "$ARTIFACT_RESULT_BUNDLE/Info.plist" ]]; then
        echo "[iOS] result bundle: $ARTIFACT_RESULT_BUNDLE"
    else
        echo "[iOS] result bundle: unavailable"
    fi
}

stop_runner() {
    kill -TERM "$BUILD_PID" 2>/dev/null || true
    pkill -TERM -f "$DERIVED_DATA" 2>/dev/null || true
    for _ in 1 2 3 4 5; do
        kill -0 "$BUILD_PID" 2>/dev/null || break
        sleep 1
    done
    kill -KILL "$BUILD_PID" 2>/dev/null || true
    wait "$BUILD_PID" 2>/dev/null || true
}

while kill -0 "$BUILD_PID" 2>/dev/null; do
    if rg -q 'Test run with [0-9]+ tests .* passed' "$LOG_FILE" 2>/dev/null; then
        SUMMARY="$(wait_for_summary || true)"
        stop_runner
        if [[ -z "$SUMMARY" ]]; then
            for _ in 1 2 3 4 5; do
                SUMMARY="$(summary_result || true)"
                [[ -n "$SUMMARY" ]] && break
                sleep 2
            done
        fi
        if [[ -n "$SUMMARY" ]] && publish_result; then
            echo "$SUMMARY" | rg '"(passedTests|failedTests|totalTestCount|result)"'
        else
            publish_log_evidence
            echo "[iOS] test log reports all tests passed; Xcode beta did not finalize xcresult"
            echo "[iOS] summary: $SUMMARY_FILE"
        fi
        echo "[iOS] tests passed; xcodebuild teardown was terminated after result was recorded"
        report_result_artifact
        echo "[iOS] test log: $LOG_FILE"
        exit 0
    fi
    if rg -q 'Test run with [0-9]+ tests .* failed' "$LOG_FILE" 2>/dev/null; then
        stop_runner
        sleep 2
        cat "$LOG_FILE"
        echo "[iOS] tests failed; result bundle: $ARTIFACT_RESULT_BUNDLE" >&2
        exit 1
    fi
    SUMMARY="$(summary_result || true)"
    if printf '%s\n' "$SUMMARY" | rg -q '"result"[[:space:]]*:[[:space:]]*"Passed"'; then
        stop_runner
        if ! publish_result; then
            sleep "$POST_STOP_GRACE_SECONDS"
            if ! publish_result; then
                publish_log_evidence
            fi
        fi
        echo "$SUMMARY" | rg '"(passedTests|failedTests|totalTestCount|result)"'
        echo "[iOS] tests passed; xcodebuild teardown was terminated after result was recorded"
        report_result_artifact
        echo "[iOS] test log: $LOG_FILE"
        exit 0
    fi
    if printf '%s\n' "$SUMMARY" | rg -q '"result"[[:space:]]*:[[:space:]]*"Failed"'; then
        stop_runner
        sleep 2
        cat "$LOG_FILE"
        echo "[iOS] tests failed; result bundle: $ARTIFACT_RESULT_BUNDLE" >&2
        exit 1
    fi
    if (( SECONDS - START_SECONDS >= TEST_TIMEOUT_SECONDS )); then
        stop_runner
        tail -80 "$LOG_FILE" >&2
        echo "[iOS] tests timed out after ${TEST_TIMEOUT_SECONDS}s" >&2
        exit 124
    fi
    sleep 2
done

BUILD_STATUS=0
wait "$BUILD_PID" || BUILD_STATUS=$?
SUMMARY="$(summary_result || true)"
if printf '%s\n' "$SUMMARY" | rg -q '"result"[[:space:]]*:[[:space:]]*"Passed"'; then
    if publish_result; then
        echo "$SUMMARY" | rg '"(passedTests|failedTests|totalTestCount|result)"'
    else
        publish_log_evidence
        echo "[iOS] test summary passed; Xcode beta did not finalize xcresult"
        echo "[iOS] summary: $SUMMARY_FILE"
    fi
    report_result_artifact
    echo "[iOS] test log: $LOG_FILE"
    exit 0
fi
if rg -q 'Test run with [0-9]+ tests .* passed' "$LOG_FILE" 2>/dev/null; then
    publish_log_evidence
    echo "[iOS] test log reports all tests passed; Xcode beta did not finalize xcresult"
    echo "[iOS] summary: $SUMMARY_FILE"
    echo "[iOS] test log: $LOG_FILE"
    exit 0
fi

tail -80 "$LOG_FILE" >&2
echo "[iOS] tests failed with exit code $BUILD_STATUS" >&2
exit "$BUILD_STATUS"
