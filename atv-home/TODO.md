# TODO

- [x] **Rotating wallpaper**: coded and built 2026-10-19, with 47 wallpapers in `wallpapers/photos/`.
- [x] **Deployed** 2026-10-19 with `./build-install.sh` + `./push-wallpapers.sh`; confirmed working on the TV.
- [ ] **Deploy and test** (built 2026-10-20 while the TV was off): run `./build-install.sh`, then check
      - **Free up memory** (Power menu): open a few apps, come home, check the "Freed X MB" message.
      - **Smart grid order**: grid starts with the most used apps; `appops get com.zafri.atvlauncher GET_USAGE_STATS`
        says allow.
      - **Uniform grid**: 5 columns, each lined up under a dock tile; Down from each dock tile lands right below it.
      - **Home settings** (Settings pill -> Home settings): every row changes with Left/Right/OK; Apps per row 4/6
        rebuilds the screen on Back; darkness changes live; Reset dock works.
      - Then commit to the fork (not committed yet).
- [x] **Name in the greeting** ("Good evening, Afif"): from the device name ("Afif's TV"), or `greeting_name`
      in `config.xml`.
- [ ] **Continue watching** (on hold until further notice): fill the empty space on the right of the home screen, beside the
      clock, with a "Continue watching" card for the most recently used app only (one item, not a row).
- [ ] **Go to home on power-on when the PS5 is off**: the firmware has no "Power on behavior" setting, so the
      launcher would do it on wake: if Live TV is in front and the PS5's HDMI input isn't active, jump to home.
      Open questions: is antenna/broadcast Live TV ever used? OK to test with an ADB sleep/wake?
