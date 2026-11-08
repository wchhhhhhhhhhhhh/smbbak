#!/bin/bash
# 在 macOS 上构建易备份（SmbBackup）APK
# 依赖：JDK 21、Android SDK(platform 34 + build-tools 34.0.0)、Gradle 8.9
# 本机已装在：/Users/a123456/AndroidTools
set -e

export JAVA_HOME="${JAVA_HOME:-/Users/a123456/AndroidTools/jdk-21.0.13+9/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/Users/a123456/AndroidTools/sdk}"
export GRADLE="${GRADLE:-/Users/a123456/AndroidTools/gradle-8.9/bin/gradle}"

cd "$(dirname "$0")"

TARGET="${1:-all}"
case "$TARGET" in
  debug)   TASKS="assembleDebug" ;;
  release) TASKS="assembleRelease" ;;
  *)       TASKS="assembleDebug assembleRelease" ;;
esac

"$GRADLE" --no-daemon --console=plain $TASKS

echo
echo "产物："
ls -la app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk 2>/dev/null || true
