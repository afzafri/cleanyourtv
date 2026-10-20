# TODO

- [x] **Rotating wallpaper**: coded and built 2026-10-19, with 47 wallpapers in `wallpapers/photos/`.
- [x] **Deployed** 2026-10-19 with `./build-install.sh` + `./push-wallpapers.sh`; confirmed working on the TV.
- [ ] **Deploy and test** (built 2026-10-20 while the TV was off): run `./build-install.sh`, then check
      - **Free up memory** (Power menu): open a few apps, come home, check the "Freed X MB" message.
      - **Smart grid order**: grid starts with the most used apps; `appops get com.zafri.atvlauncher GET_USAGE_STATS`
        says allow.
- [ ] **Name in the greeting** ("Good evening, Afif"): take the name from the TV's device name ("Afif's TV",
      Settings -> Device Preferences -> About -> Device name; `Settings.Global.DEVICE_NAME`, no permission
      needed). Google account display names aren't readable by apps. Open question: is "Afif" the right name, or
      use a fixed name from `config.xml` instead?
- [ ] **Continue watching** (on hold until further notice): fill the empty space on the right of the home screen, beside the
      clock, with a "Continue watching" card for the most recently used app only (one item, not a row).
- [ ] **Go to home on power-on when the PS5 is off**: the firmware has no "Power on behavior" setting, so the
      launcher would do it on wake: if Live TV is in front and the PS5's HDMI input isn't active, jump to home.
      Open questions: is antenna/broadcast Live TV ever used? OK to test with an ADB sleep/wake?
