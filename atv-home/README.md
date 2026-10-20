# atv-home: Panasonic TH-40HS550K debloat + Apple TV style launcher

My own launcher and scripts, kept in this fork of [cleanyourtv](https://github.com/Gathin23/cleanyourtv).
It is a separate launcher (`com.zafri.atvlauncher`), written from scratch, not the repo's `launcher/`
(`com.gathin.tvhome`). Everything outside `atv-home/` is upstream and stays untouched, so the fork can
pull upstream updates without conflicts.

TV: Panasonic TH-40HS550K (Skyworth-built, MediaTek, model GS655 "shibuya"), Android TV 11.
Done 2026-10-19 over ADB at 192.168.100.17:5555. Everything here is reversible.

## What's in this folder

| Path | What it is |
|---|---|
| `disabled-packages.txt` | Every package that was disabled, grouped by batch |
| `restore.sh` | Re-enables everything in that list plus the stock launcher, and makes the stock launcher home again |
| `build-install.sh` | Builds the launcher, installs it, makes it default home, disables the stock launcher |
| `launcher/` | Launcher source (Java, AndroidX RecyclerView, minSdk 30) |
| `fetch-wallpapers.sh` | Downloads the wallpaper set listed in `wallpapers/photos.txt` into `wallpapers/photos/` |
| `push-wallpapers.sh` | Copies `wallpapers/photos/` to the TV |
| `TODO.md` | What's planned next |

Local only, never committed (see the repo's `.gitignore`): `backup-2026-10-19/` (package lists, settings,
`getprop`, the original HOME setup and backed-up APKs), `shots/` (screenshots from the TV) and
`wallpapers/photos/` (re-download with `./fetch-wallpapers.sh`).

All scripts take the TV's IP as an optional first argument (default `192.168.100.17`).

## Re-enable one app

```bash
adb connect 192.168.100.17:5555
adb -s 192.168.100.17:5555 shell pm enable --user 0 <package>
```

Package names are in `disabled-packages.txt`. For example, Prime Video is `com.amazon.amazonvideo.livingroom`
and the Google Assistant is `com.google.android.katniss`. `./restore.sh` re-enables everything at once.

Multi Downloader and Disney+ (`in.startv.hotstar.dplus.tv`) were **uninstalled** (not disabled). Disney+ is
easiest to get back from Play Store. Both APKs are also backed up:

```bash
cd backup-2026-10-19/apks/multi.downloader      # or apks/in.startv.hotstar.dplus.tv
adb -s 192.168.100.17:5555 install-multiple base.apk split_config.*.apk
```

## Change the dock (on the TV)

**Hold OK** on any app tile (or press the remote's Menu key, if it has one) to open its menu:

| On a grid app | On a dock app |
|---|---|
| Pin to dock | Move left / Move right |
| App info | Remove from dock |
| Force close | App info |
| | Force close |

- **Pin to dock** adds the app at the end if the dock has a free slot. If the dock is full, the dock lights
  up and asks "Replace *X* with *Y*?": Left/Right picks the slot, OK replaces it, Back cancels.
- **App info** opens Android's settings page for the app (storage, permissions, the system Force stop).
- **Force close** ends the app's process. Android doesn't let a launcher force-stop another app, but while the
  home screen is showing the app is in the background, so this closes it completely. An app playing audio in
  the background is the exception: use App info -> Force stop for that.

The dock order is saved on the TV and survives reboots and launcher updates. `dock_packages` in
`launcher/app/src/main/res/values/config.xml` is only the starting dock, and its length (5) is the dock size.
To go back to it, clear the launcher's data: Settings -> Apps -> Home -> Clear data.

`hidden_packages` in the same file hides apps from the grid without disabling them.

## Wallpapers

The launcher looks in its own folder on the TV, `/sdcard/Android/data/com.zafri.atvlauncher/files/`:

1. **`wallpapers/`**: if this folder has images (`.jpg`, `.png`, `.webp`), they **rotate**. The next one
   fades in each time the TV turns on, and every 30 minutes while the home screen is showing. The order is
   shuffled and never shows the same image twice in a row.
2. Otherwise a single **`wallpaper.jpg`** (or `.png` / `.webp`) is shown permanently.
3. Otherwise the background is pitch black.

The launcher never downloads anything; you push the images over ADB. The set in `wallpapers/photos/` has
46 Unsplash photos (plain/minimal, nature, city; credits in `wallpapers/CREDITS.md`) plus the generated red
orbs. The photos aren't committed; `wallpapers/photos.txt` lists them. To download them and copy them to the TV:

```bash
./fetch-wallpapers.sh
./push-wallpapers.sh
```

To change the set, add or delete files in `wallpapers/photos/` and run the script again. Keep images around
1280x720 (the TV's resolution and about 1 GB of RAM). More from the same source, any photo id from
https://picsum.photos/v2/list at TV size:

```bash
curl -L -o wallpapers/photos/nature-1015.jpg https://picsum.photos/id/1015/1280/720.jpg
```

Settings in `launcher/app/src/main/res/values/config.xml` (rebuild with `./build-install.sh` after changing):
`wallpaper_rotate_minutes` (0 = only when the TV turns on), `wallpaper_shuffle` (false = alphabetical order),
`wallpaper_dim_percent`. A soft dark gradient at the top and bottom keeps the clock and labels readable on
bright photos.

## Using the launcher

- **Dock:** the five apps in the band at the bottom. The focused one's name shows large above the dock, with a
  glow in a colour taken from its banner.
- **Down** from the dock goes into the 4-column grid of the other apps, which scrolls up one row per press.
  The grid is ordered **most used first** (time spent in each app over the last 30 days), then never-used apps
  alphabetically. It re-sorts when the TV wakes, so tiles don't move while you're using it. Set
  `grid_sort_by_usage` to `false` in `config.xml` for plain alphabetical order.
  **Up** goes back the same way. **Back** in the grid jumps to the dock.
- **Up** from the dock reaches the top-right pills: **Inputs** (HDMI 1/2, Composite, Live TV; the PS5 shows
  next to its HDMI port through HDMI-CEC), **Wi-Fi** (network name; opens Wi-Fi settings) and **Settings**.
- **Settings** pill: **Home settings** or **TV settings** (Android's). Home settings changes these on the TV,
  Left/Right (or OK) to change the value, Back to close; they override `config.xml`, which only holds the defaults:
  Apps per row (4/5/6, dock and grid together, the screen rebuilds when the panel closes), Wallpaper changes
  (on wake only / 15 / 30 / 60 min), Wallpaper order (shuffle / A-Z), Wallpaper darkness (20/35/50%),
  App order (most used / A-Z), Animations (on/off), and Reset dock to default.
- The grid uses the dock's columns, tile size and spacing, so every grid column sits under a dock tile.
- **Power** pill (right of Settings): **Sleep** puts the TV in standby like the remote's power button;
  **Free up memory** ends the background processes of every app on the home screen (not Live TV) and shows
  how much memory came back; use it when apps start crashing or restarting. **Restart or shut down…** opens
  the TV's own power menu (Power off / Restart). Android doesn't let apps do
  either directly, so these go through a small accessibility service in the launcher, "Home power controls",
  which reads nothing on screen. `build-install.sh` switches it on. Force-stopping the Home app switches it off
  again: if Sleep then sends you to Accessibility settings, turn "Home power controls" back on there.
- The last focused tile is remembered when you come back from an app. Pressing Home while already on the
  home screen returns to the dock.

## Notes

- The stock launcher's top bar lost its Inputs/Wi-Fi icons when `leanbacklauncher.partnercustomizer` was
  disabled. That only matters if you go back to the stock launcher; `restore.sh` brings them back.
- On this TV `screencap` returns an empty file while Live TV or an HDMI input is on screen (protected video).
- When you're done with ADB: Settings -> Device Preferences -> About... -> Developer options -> turn off USB
  debugging (and network debugging).
