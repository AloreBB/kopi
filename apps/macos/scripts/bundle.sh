#!/bin/sh
# Assembles build/Kopi.app from the release binary and signs it ad hoc.
# Run after `swift build -c release` (see `make mac`).
set -eu

cd "$(dirname "$0")/.."

BIN_DIR="$(swift build -c release --show-bin-path)"
APP="build/Kopi.app"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_DIR/Kopi" "$APP/Contents/MacOS/Kopi"
cp Resources/Info.plist "$APP/Contents/Info.plist"
plutil -lint "$APP/Contents/Info.plist" >/dev/null

codesign --force --deep -s - "$APP"
echo "Built $(pwd)/$APP"
