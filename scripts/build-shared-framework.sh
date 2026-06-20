#!/bin/bash
# Xcode Build Phase 脚本：将 KMP shared 模块编译为 Framework 并嵌入 iOS 项目
# 在 Xcode → Build Phases → Run Script 中添加此脚本
#
# 使用方法：
# 1. 在 Xcode 项目中添加 Run Script Build Phase（放在 Compile Sources 之前）
# 2. 脚本内容：bash "$SRCROOT/../scripts/build-shared-framework.sh"
# 3. 取消勾选 "Based on dependency analysis"（每次都运行）
#
# v1.6 起该脚本将替换为 SPM binaryTarget 引用 Shared.xcframework（见 plans/v1.6）
# 本脚本作为过渡期使用。

set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHARED_MODULE="$REPO_ROOT/shared"

# 根据 Xcode 构建目标选择编译架构
if [ "$PLATFORM_NAME" = "iphonesimulator" ]; then
    KOTLIN_TARGET="iosSimulatorArm64"
elif [ "$PLATFORM_NAME" = "iphoneos" ]; then
    KOTLIN_TARGET="iosArm64"
else
    KOTLIN_TARGET="iosSimulatorArm64"
fi

echo "▶ 编译 KMP shared 模块（目标：$KOTLIN_TARGET）..."
cd "$REPO_ROOT"
./gradlew ":shared:link${KOTLIN_TARGET^}FrameworkReleaseIos" --quiet

FRAMEWORK_PATH="$SHARED_MODULE/build/bin/$KOTLIN_TARGET/releaseFramework/Shared.framework"
DEST="$BUILT_PRODUCTS_DIR/$FRAMEWORKS_FOLDER_PATH/Shared.framework"

echo "▶ 复制 Framework 到 $DEST"
rm -rf "$DEST"
cp -R "$FRAMEWORK_PATH" "$DEST"

echo "✓ shared 模块嵌入完成"
