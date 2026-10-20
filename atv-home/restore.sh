#!/bin/bash
# Re-enables every package the debloat disabled, and restores the stock launcher.
# Usage: ./restore.sh [TV_IP]   (default 192.168.100.17)
TV="${1:-192.168.100.17}:5555"
cd "$(dirname "$0")"
adb connect "$TV" >/dev/null
for p in $(grep -v '^#' disabled-packages.txt) com.google.android.tvlauncher; do
  echo "enable $p"
  adb -s "$TV" shell pm enable --user 0 "$p"
done
adb -s "$TV" shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
# Switch off the launcher's power-controls accessibility service.
CUR=$(adb -s "$TV" shell settings get secure enabled_accessibility_services | tr -d '\r')
NEW=$(echo "$CUR" | tr ':' '\n' | grep -v com.zafri.atvlauncher | paste -sd: -)
if [ -z "$NEW" ]; then
  adb -s "$TV" shell settings delete secure enabled_accessibility_services
  adb -s "$TV" shell settings put secure accessibility_enabled 0
else
  adb -s "$TV" shell settings put secure enabled_accessibility_services "$NEW"
fi
echo "Done. Optional: adb -s $TV uninstall com.zafri.atvlauncher   # remove the custom launcher"
