#!/bin/bash
# Downloads the wallpaper set listed in wallpapers/photos.txt (Unsplash photos via Lorem Picsum,
# 1280x720) into wallpapers/photos/, plus the generated red orbs. Then run ./push-wallpapers.sh.
set -e
cd "$(dirname "$0")/wallpapers"
mkdir -p photos
while read -r name; do
  [ -z "$name" ] && continue
  id="${name##*-}"
  [ -s "photos/$name.jpg" ] || curl -sfL --retry 3 -o "photos/$name.jpg" "https://picsum.photos/id/$id/1280/720.jpg"
done < photos.txt
cp red-orbs.jpg photos/minimal-red-orbs.jpg
echo "$(ls photos | wc -l | tr -d ' ') wallpapers in wallpapers/photos/"
