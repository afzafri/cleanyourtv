#!/bin/bash
# Copies the rotating wallpaper set (wallpapers/photos/) to the TV, replacing what's there.
# The launcher picks the new set up the next time Home comes to the front.
# Usage: ./push-wallpapers.sh [TV_IP]   (default 192.168.100.17)
set -e
TV="${1:-192.168.100.17}:5555"
DIR=/sdcard/Android/data/com.zafri.atvlauncher/files/wallpapers
cd "$(dirname "$0")"
adb connect "$TV" >/dev/null
adb -s "$TV" shell rm -rf "$DIR"
adb -s "$TV" shell mkdir -p "$DIR"
adb -s "$TV" push wallpapers/photos/. "$DIR/"
echo "Pushed $(ls wallpapers/photos | wc -l | tr -d ' ') wallpapers."
