#!/usr/bin/env bash
# Tangram dev harness (Windows Git Bash). Pinned to one emulator: DEV (borrowed with the factory's emu_borrow.py).
#   tools/tw.sh cmd NAME [ARG]     debug broadcast:  am broadcast -a io.embodit.tangramwood.DEBUG --es cmd NAME --es a ARG
#   tools/tw.sh shot OUT.png       still capture of the device (adb exec-out screencap)
#   tools/tw.sh log [REGEX]        logcat lines of tag Tangram (optionally filtered)
#   tools/tw.sh install            install the debug APK and start the app
set -u
DEV="${DEV:?set DEV=emulator-55xx}"
PKG=io.embodit.tangramwood
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
HERE="$(cd "$(dirname "$0")" && pwd)"
case "${1:-}" in
  cmd) "$ADB" -s "$DEV" shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$2" --es a "\"${3:-}\"" >/dev/null ;;
  shot) "$ADB" -s "$DEV" exec-out screencap -p > "$2" ;;
  log) "$ADB" -s "$DEV" logcat -d -s Tangram:V BoardInput:V | grep -E "${2:-.}" ;;
  install) "$ADB" -s "$DEV" install -r -d "$HERE/../app/build/outputs/apk/debug/app-debug.apk" && "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 ;;
  *) sed -n 2,7p "$0" ;;
esac
