#!/bin/bash
# ── Build, bundle, sign, install and launch the iOS app on a simulator ──────
#
# The KMP/iOS port ships NO Xcode project and NO CocoaPods (Phase 1 decision).
# `:iosApp` links a plain Kotlin/Native Mach-O executable; everything Xcode
# would otherwise do around it is these ~40 lines:
#
#   1. gradle link       -> BetterTrack.kexe
#   2. assemble          -> BetterTrack.app/{BetterTrack,Info.plist}
#   3. codesign --sign - -> ad-hoc signature (simulators accept it; devices do not)
#   4. simctl install/launch
#
# Usage: tools/ios/build_ios_app.sh [simulator-udid]
set -euo pipefail

DEVICE_ID="${1:-794CE269-5106-4515-9936-37E22894AEB7}"  # iPhone 16, iOS 18.4
BUNDLE_ID="at.bettertrack.app"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KEXE="$REPO_ROOT/iosApp/build/bin/iosSimulatorArm64/debugExecutable/BetterTrack.kexe"
APP_DIR="$REPO_ROOT/iosApp/build/ios/BetterTrack.app"

echo "==> 1/5 linking Kotlin/Native executable"
"$REPO_ROOT/gradlew" -p "$REPO_ROOT" :iosApp:linkDebugExecutableIosSimulatorArm64

echo "==> 2/5 assembling $APP_DIR"
rm -rf "$APP_DIR"
mkdir -p "$APP_DIR"
# CFBundleExecutable is "BetterTrack", so the binary must be named exactly that
# inside the bundle — the .kexe suffix would make CFBundleExecutable a lie and
# the app would fail to launch.
cp "$KEXE" "$APP_DIR/BetterTrack"
cp "$REPO_ROOT/iosApp/Info.plist" "$APP_DIR/Info.plist"

echo "==> 3/5 ad-hoc codesigning"
codesign --force --sign - "$APP_DIR"

echo "==> 4/5 installing on $DEVICE_ID"
xcrun simctl boot "$DEVICE_ID" 2>/dev/null || true   # already booted is fine
xcrun simctl install "$DEVICE_ID" "$APP_DIR"

echo "==> 5/5 launching $BUNDLE_ID"
xcrun simctl launch "$DEVICE_ID" "$BUNDLE_ID"
echo "OK"
