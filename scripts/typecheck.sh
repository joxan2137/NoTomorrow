#!/bin/zsh
# Fast whole-module type check of the iOS app sources (no linking, no xcodebuild, safe to run in parallel).
# Usage: scripts/typecheck.sh            -> type-check the app module
#        scripts/typecheck.sh widgets    -> type-check the widget extension module
set -u
cd "$(dirname "$0")/.."
SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
TARGET="arm64-apple-ios17.0-simulator"
if [[ "${1:-}" == "widgets" ]]; then
  FILES=(NoTomorrowWidgets/**/*.swift Shared/**/*.swift)
  MODULE=NoTomorrowWidgets
else
  FILES=(NoTomorrow/**/*.swift Shared/**/*.swift)
  MODULE=NoTomorrow
fi
xcrun swiftc -typecheck -swift-version 5 -sdk "$SDK" -target "$TARGET" -module-name "$MODULE" \
  -Xfrontend -warn-long-expression-type-checking=400 \
  "${FILES[@]}" 2>&1 | grep -vE "^\s*$" | grep -E "error:|warning: unused|^[^:]+:[0-9]+:[0-9]+: (error|warning)" | head -80
STATUS=${pipestatus[1]}
if [[ $STATUS -eq 0 ]]; then echo "TYPECHECK OK ($MODULE)"; else echo "TYPECHECK FAILED ($MODULE)"; fi
exit $STATUS
