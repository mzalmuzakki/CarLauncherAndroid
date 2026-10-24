# Orca Home versions

Every version that worked in the car is kept in `releases/`. To go back to one:
`adb install -r releases/orca-home-<version>.apk` (same signing key, so it installs over
any newer version; Android may ask to allow a downgrade: add `-d` after `-r`).

## 2.3 — first version working in the car (24 Oct 2026)
`releases/orca-home-2.3.apk`, built from commit fb4d62f.
- Live OpenStreetMap map (dark with dark themes), GPS speed bubble, weather, Spotify
  music card, favourite apps, 7 themes.
- On NWD firmware: becomes the home screen through the firmware's own launcher setting, and
  opens itself after a reboot over the firmware's "Select a Home app" box.
- `scripts/launcher.sh off` / `scripts/restore.sh` go back to the factory launcher.
