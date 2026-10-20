#!/bin/bash
# Builds the launcher, installs it on the TV, makes it the default home and disables the stock launcher.
# Usage: ./build-install.sh [TV_IP]   (default 192.168.100.17)
set -e
TV="${1:-192.168.100.17}:5555"
PKG=com.zafri.atvlauncher
cd "$(dirname "$0")/launcher"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
./gradlew -q assembleDebug

adb connect "$TV" >/dev/null
# Push + pm install instead of `adb install`, which can hang waiting for an on-screen confirmation.
adb -s "$TV" push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/launcher.apk
adb -s "$TV" shell pm install -r /data/local/tmp/launcher.apk
adb -s "$TV" shell rm /data/local/tmp/launcher.apk
# Android only reveals the Wi-Fi name to apps with location permission.
adb -s "$TV" shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
# Usage access, for ordering the grid by most used.
adb -s "$TV" shell appops set $PKG GET_USAGE_STATS allow
adb -s "$TV" shell cmd package set-home-activity $PKG/.MainActivity
adb -s "$TV" shell pm disable-user --user 0 com.google.android.tvlauncher
# Must stay after the install: force-stopping the app switches its accessibility service off.
# Switch on the power controls (Sleep / Restart or shut down), keeping any other accessibility services.
SVC=$PKG/$PKG.PowerService
CUR=$(adb -s "$TV" shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in
  *"$SVC"*) ;;
  ""|null) adb -s "$TV" shell settings put secure enabled_accessibility_services "$SVC" ;;
  *) adb -s "$TV" shell settings put secure enabled_accessibility_services "$CUR:$SVC" ;;
esac
adb -s "$TV" shell settings put secure accessibility_enabled 1
adb -s "$TV" shell input keyevent KEYCODE_HOME
echo "Installed. Home apps now:"
adb -s "$TV" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
