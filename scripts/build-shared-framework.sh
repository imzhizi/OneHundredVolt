#!/bin/bash
# 构建 KMP shared 模块为 xcframework
#
# 用法：
#   ./scripts/build-shared-framework.sh           # 默认 Release 配置
#   ./scripts/build-shared-framework.sh Debug     # Debug 配置
#
# 产物：
#   shared/build/Shared.xcframework
#
# 集成方式（手动一次）：
#   1. Xcode → File → Add Files... → 选择 shared/build/Shared.xcframework
#   2. Target → Frameworks, Libraries, and Embedded Content → Embed & Sign
#   3. 或用 SPM binaryTarget：参考 iosApp/Package.swift
#
# v1.6 改动：从单一架构 framework 升级为 xcframework（iOS ARM64 设备 +
#            fat simulator 包含 x86_64 + arm64）。

set -e

CONFIG="${1:-Release}"
case "$CONFIG" in
  Debug|Release) ;;
  debug) CONFIG="Debug" ;;
  release) CONFIG="Release" ;;
  *) echo "Unsupported configuration: $CONFIG (expected Debug or Release)" >&2; exit 1 ;;
esac
FRAMEWORK_DIR="$(echo "${CONFIG:0:1}" | tr '[:upper:]' '[:lower:]')${CONFIG:1}Framework"

if ! JAVA_21_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
    echo "JDK 21 is required to build the shared iOS framework." >&2
    exit 1
fi
export JAVA_HOME="$JAVA_21_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHARED_MODULE="$REPO_ROOT/shared"
OUTPUT_DIR="$SHARED_MODULE/build/Shared.xcframework"
WORK_DIR="$SHARED_MODULE/build/xcframework-work"
NEW_OUTPUT_DIR="$WORK_DIR/Shared.xcframework"

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"

echo "▶ 编译 KMP shared 模块（$CONFIG）..."
cd "$REPO_ROOT"
./gradlew \
    :shared:link"$CONFIG"FrameworkIosArm64 \
    :shared:link"$CONFIG"FrameworkIosX64 \
    :shared:link"$CONFIG"FrameworkIosSimulatorArm64 \
    --quiet

# 拷贝 device + 两个 simulator framework
DEVICE_FW="$WORK_DIR/device/Shared.framework"
SIM_X64_FW="$WORK_DIR/simulator-x64/Shared.framework"
SIM_ARM64_FW="$WORK_DIR/simulator-arm64/Shared.framework"

mkdir -p "$(dirname "$DEVICE_FW")" "$(dirname "$SIM_X64_FW")" "$(dirname "$SIM_ARM64_FW")"

cp -R "$SHARED_MODULE/build/bin/iosArm64/$FRAMEWORK_DIR/Shared.framework" "$DEVICE_FW"
cp -R "$SHARED_MODULE/build/bin/iosX64/$FRAMEWORK_DIR/Shared.framework" "$SIM_X64_FW"
cp -R "$SHARED_MODULE/build/bin/iosSimulatorArm64/$FRAMEWORK_DIR/Shared.framework" "$SIM_ARM64_FW"

# 合并两个 simulator slices 为 fat framework
FAT_SIM_FW="$WORK_DIR/simulator-fat/Shared.framework"
mkdir -p "$FAT_SIM_FW"
cp -R "$SIM_ARM64_FW/." "$FAT_SIM_FW/"
SIM_FAT_BIN="$WORK_DIR/simulator-fat/shared-tmp"
mkdir -p "$SIM_FAT_BIN"
lipo -create \
    "$SIM_X64_FW/Shared" \
    "$SIM_ARM64_FW/Shared" \
    -output "$SIM_FAT_BIN/Shared"
cp "$SIM_FAT_BIN/Shared" "$FAT_SIM_FW/Shared"

# 修正 simulator fat framework 的 MinimumOSVersion 取最大值
plutil -replace MinimumOSVersion -string "14.0" "$FAT_SIM_FW/Info.plist"

echo "▶ 生成 xcframework..."
xcodebuild -create-xcframework \
    -framework "$DEVICE_FW" \
    -framework "$FAT_SIM_FW" \
    -output "$NEW_OUTPUT_DIR"

rm -rf "$OUTPUT_DIR"
mv "$NEW_OUTPUT_DIR" "$OUTPUT_DIR"

# 清理中间产物
rm -rf "$WORK_DIR"

echo "✓ xcframework 已生成: $OUTPUT_DIR"
ls -la "$OUTPUT_DIR"
