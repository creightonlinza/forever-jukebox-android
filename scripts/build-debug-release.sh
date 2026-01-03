#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

APK_SRC="${ANDROID_DIR}/app/build/outputs/apk/debug/app-debug.apk"
RELEASES_DIR="${ANDROID_DIR}/releases"

"${ANDROID_DIR}/gradlew" -p "${ANDROID_DIR}" assembleDebug

mkdir -p "${RELEASES_DIR}"
cp "${APK_SRC}" "${RELEASES_DIR}/forever-jukebox-debug.apk"

echo "Debug APK created at ${RELEASES_DIR}/forever-jukebox-debug.apk"
