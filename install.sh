#!/usr/bin/env bash
# Build, install and enable Digital Volume on a TV connected over adb.
#   ./install.sh               build, install, enable the accessibility service
#   ./install.sh --uninstall   disable the service and remove the app
# Needs JDK 17, the Android SDK (platform 34) and adb with the TV already connected (adb connect <tv ip>).
set -euo pipefail
cd "$(dirname "$0")"
PKG=io.github.jpablolazo.digitalvolume
SVC=$PKG/.VolumeService

current=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
[ "$current" = "null" ] && current=""

if [ "${1:-}" = "--uninstall" ]; then
  rest=$(printf '%s' "$current" | tr ':' '\n' | grep -v "^$PKG/" | paste -sd: - || true)
  adb shell settings put secure enabled_accessibility_services "\"$rest\""
  adb uninstall "$PKG"
  echo "Removed. The TV volume works as it came from the factory."
  exit 0
fi

./gradlew --console=plain assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
case ":$current:" in
  *":$SVC:"*) ;;
  *) adb shell settings put secure enabled_accessibility_services "${current:+$current:}$SVC" ;;
esac
adb shell settings put secure accessibility_enabled 1
echo "Installed. Open \"Digital Volume\" from the app grid to change its settings."
