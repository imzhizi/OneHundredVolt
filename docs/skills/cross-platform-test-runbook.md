# Cross-Platform Test Runbook

## Purpose

This project-level skill is the execution guide for validating OneHundredVolt on Android and iOS. Use it with [TEST_PLAN.md](../TEST_PLAN.md): the test plan defines coverage; this runbook defines a repeatable workflow, evidence requirements, and environment triage.

Do not treat a build, a simulator boot, or an API request alone as a passing functional test. A feature passes only when its user-visible result and its persisted state have both been checked.

## Safety Boundaries

- The tester completes WebView login manually. Do not put account credentials, cookies, tokens, audio URLs, or copied browser storage into source code, scripts, logs, screenshots, commits, or reports.
- Do not run logout, clear data, clear cache, delete episode metadata, or fixture replacement against a useful account without explicit approval for that destructive test cycle.
- Keep proxy settings local to the shell. Do not persist a personal proxy endpoint in app configuration.
- Keep Gradle and Xcode generated directories ignored. Build artifacts are evidence only when saved under `build/test-artifacts/`; they are not source files.

## Required Evidence

Record the following for every test run:

1. Git revision, build variant, device model, OS version, and run time.
2. The input state: fresh install, existing logged-in data, or fixture name.
3. The visible result: screenshot, UI dump, or Xcode/Android Studio accessibility text.
4. The persisted result when applicable: creator, album, audio, queue, progress, cache, or sync timestamp counts before and after the action.
5. Failures: exact action sequence, expected and actual result, relevant app log excerpt, and whether the failure reproduces.

Do not call a simulator audio-stack warning an app failure unless playback state, progress, or user-visible audio behavior is also wrong.

## Environment Preflight

Run the checks before starting a test matrix. Resolve environment failures before changing product code.

```bash
cd /Users/wangzhiyu04/apus/OneHundredVolt
git status --short --branch

export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export https_proxy=http://127.0.0.1:7890
export http_proxy=http://127.0.0.1:7890
export all_proxy=socks5://127.0.0.1:7890

"$JAVA_HOME/bin/java" -version
adb devices
```

Notes:

- Use JDK 21 for Android/Kotlin Multiplatform work. Do not alter the developer's global default JDK just to run this project.
- Only export the proxy variables when the network requires them. Keep them out of committed scripts and app source.
- Confirm the working tree before testing so generated output and pre-existing edits are not mistaken for test results.
- Select exactly one iOS test channel below and set its own `DEVELOPER_DIR`; do not infer the selected Xcode from the machine default.

## Android Debug Workflow

### 1. Start from a known emulator state

```bash
adb devices
./gradlew :shared:testDebugUnitTest :androidApp:assembleDebug
./scripts/test-android-debug.sh
```

The script builds, installs, launches, and writes a screenshot and UI dump beneath `build/test-artifacts/android/`. It does not log in or clear data.

If Gradle fails before task configuration, first confirm JDK 21 and proxy reachability. If the wrapper cache is corrupt or locked, use a new ignored local Gradle cache rather than committing cache files.

### 2. Exercise the user journey

1. On a fresh install, confirm there is a login path and no login-bypass path.
2. Complete login manually, select a creator, and run full sync.
3. Verify the homepage uses the synced data, not merely a success toast or in-memory state.
4. Open an album, start an episode, then verify pause, seek, speed, sleep timer, queue changes, and restart restoration.
5. Run the Debug fixture cases from `TEST_PLAN.md` only after recording the baseline counts.

### 3. Capture failures correctly

```bash
adb logcat -d -v time | rg 'AndroidRuntime|OneHundredVolt|Media3'
adb shell uiautomator dump /sdcard/ohv-window.xml
adb exec-out cat /sdcard/ohv-window.xml
```

Separate app crashes and functional errors from UIAutomator helper noise. When Android validation is complete, shut down the emulator if the next task needs its resources.

## iOS Test Channels

### Channel A: Stable macOS + XCTest

Use this channel as the release-quality gate. It requires a stable macOS/Xcode installation and a compatible simulator runtime.

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
"$DEVELOPER_DIR/usr/bin/xcodebuild" -version
./scripts/build-shared-framework.sh Debug
./scripts/test-ios-tests.sh
```

Archive the `.xcresult` and test summary. A test run is passing only when XCTest reports its result; a successful GUI launch or manual walkthrough is not a substitute. Maintain a fast PR test plan and a wider release test plan so the same scheme can have different explicit scopes and diagnostics.

### Channel B: beta macOS/Xcode + Computer Use

Use this channel to inspect beta runtime behavior and manually authenticated workflows that the stable XCTest channel does not cover.

```bash
export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
"$DEVELOPER_DIR/usr/bin/xcodebuild" -version
./scripts/build-shared-framework.sh Debug
```

Run the app with Xcode or DeviceHub, then use Computer Use for the visible user journey. Save screenshots, state counts, and exact interaction steps. Mark the result `exploratory passed`, `failed`, `blocked`, or `not run`; never report it as `XCTest passed`.

If `simctl`, CoreSimulatorService, or the simulator disk-image service is unavailable while the Xcode GUI still works, continue only the GUI/Computer Use flow. CLI XCTest and `simctl` evidence remain `not run` until the stable toolchain can execute them.

## iOS Debug Workflow

### 1. Build the shared framework before the app

```bash
./scripts/build-shared-framework.sh Debug
SIMULATOR_ID=<booted-simulator-udid> ./scripts/test-ios-simulator.sh
./scripts/test-ios-tests.sh
```

The framework build must successfully produce compatible device and simulator slices before treating the iOS app build as valid. The shared-framework script builds into temporary output and swaps it only after a successful build; preserve that behavior. Use the selected channel's `DEVELOPER_DIR`; its default is only a fallback.

### 2. beta simulator triage

Use the configured beta toolchain through `DEVELOPER_DIR`. A CoreSimulator command-line failure, such as an invalid CoreSimulator service connection or unavailable simulator disk-image service, is an environment blocker, not evidence of an app defect.

When the command-line simulator service is broken but Xcode's GUI can boot and run a device:

1. Use Xcode or DeviceHub to select a booted simulator and run the app.
2. Record the simulator model and OS version from the GUI.
3. Perform the manual login and core user journey in that app instance.
4. Keep CLI-only assertions, including `xcodebuild test` and `simctl` screenshots, marked as `not run` rather than inferring their results from GUI launch success.

If neither CLI nor Xcode GUI can boot a compatible runtime, install or select the matching runtime before continuing. Do not rewrite app code to compensate for an Xcode/runtime mismatch.

### 3. iOS-specific regressions to verify

- Login must survive a cold restart through Keychain and must not move to the home screen until onboarding sync has actually completed.
- Confirming a WebView login must validate only the expected Afdian-domain cookie. A similarly named third-party cookie must not be accepted.
- A KMP exception that reaches Swift/Objective-C is a crash risk. API/serialization/transport failures must be normalized to an error the Swift UI can render.
- After sync, cold-start the app and verify that the home list refreshes from the database callback, not only the Settings counts.
- Test playback state changes even if simulator audio hardware logs warnings. Verify the clock/state transition and real-device audio separately when release confidence requires it.

## Sync and Network Triage

Use this order to classify a failed sync:

1. Confirm login state and creator selection.
2. Record sync stage and data counts before retrying.
3. Distinguish `401`/not-logged-in from network, response-decoding, and rate-limit failures.
4. Check retry behavior for `429` and transient `5xx` responses. Retry must be bounded and delayed; it must not become an unbounded background loop.
5. For catalog fetching, preserve the service's rate limit. A sync must not report success while an incomplete catalog has been committed.
6. After a retry succeeds, cold-start the app and confirm the persisted database is visible on the home screen.

Never log the authentication token while investigating. Log operation, endpoint path, status category, retry number, page or album identifier, and count deltas instead.

## Fixture and Incremental-Update Workflow

Use the Debug diagnostic panel for deterministic catalog scenarios. Fixtures are process-local and must be cleared after each case.

1. Establish the baseline: current catalog count, `lastCheckedAt`, unread count, selected episode progress, and cache status.
2. Apply one fixture only: added item, changed item, empty catalog, duplicate ID, missing final item, request error, or timeout.
3. Trigger incremental update and capture the displayed outcome plus persisted counts.
4. Verify expected invariants:
   - added or changed items update only the target album;
   - existing progress and cached URL remain intact;
   - duplicate IDs do not create a second record;
   - empty, incomplete, error, or timeout responses do not advance `lastCheckedAt` or delete existing local episodes.
5. Clear the fixture, restart the app, and confirm the remote catalog path is restored.

## Pass Criteria and Handoff

Report each category as `passed`, `failed`, or `not run`; never collapse `not run` into `passed`.

- `passed`: evidence proves both visible behavior and required persistence.
- `failed`: include reproduction, expected/actual behavior, and severity.
- `not run`: include the exact blocker, such as destructive-operation approval, a missing simulator runtime, or unavailable XCTest infrastructure.

Release readiness requires all P0 and P1 scenarios in `TEST_PLAN.md` to pass through the stable XCTest channel where they are automatable, with beta Computer Use evidence reported separately. The remaining gaps after the current core Debug regression are destructive settings flows, physical-device audio output, and any XCTest execution blocked by the local Xcode beta simulator service.

## Useful Commands

```bash
# Shared KMP tests
./gradlew :shared:testDebugUnitTest

# Android build, install, launch, UI evidence
./scripts/test-android-debug.sh

# iOS shared framework, app launch, and XCTest evidence
./scripts/build-shared-framework.sh Debug
SIMULATOR_ID=<booted-simulator-udid> ./scripts/test-ios-simulator.sh
./scripts/test-ios-tests.sh

# Repository hygiene before commit
git diff --check
git status --short
```
