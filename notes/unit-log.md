# Unit log: Orca T5-UP-F52LA (K2501, Allwinner sun50iw9, Android 10 32-bit)

Firmware K2501_UPGZ_S212A70.20240428.092948, MCU V6.0-UP01-20231206-A02.

## 2026-10-24: before optimizing (after ~7 h without a real reboot)
- 1,392 processes, 722 MB free RAM, chips at 75 °C
- `android.hardware.sensors@2.0-service` at 100% CPU and `logd` at 91% CPU:
  the driver logs `AccelSensor: unknown code (value=1, code=252)` ~15,000 times/s
- Wi-Fi on 2.4 GHz with always-on scanning

## 2026-10-24: after optimize.sh + AccelSensors log mute + reboot (baseline)
Uptime 19 min: 263 processes, 1,564 threads, 2,942 MB free, 64-66 °C (CPU 50 °C).
Wi-Fi 5 GHz (phone hotspot), scanning off.

| Cold start | Time | Compiled |
|---|---|---|
| Spotify 9.1.88 | 2.1 s | speed |
| YouTube 21.37.42 | 3.5 s | speed |
| Netflix 9.83.0 | 5.7 s | speed |

Most threads per process: system_server 139, YouTube 130, TXZ 97, GMS 92,
Play Store background 64, System UI 49, Gboard 48, Maps 48.

Decisions: keep Google Maps fully usable (other drivers use it), so it is not
background-restricted. Voice only through ZLink (Siri / Google Assistant), so TXZ is
disabled. Switch Gboard to Simple Keyboard. Firmware update from the seller is not possible.

TXZ: `pm disable-user` is undone at boot by the manufacturer's software, force-stop
is ignored, and com.txznet.smartadapter (restarted immediately) keeps TXZService bound.
Mic revoked + background restricted did not stop it. Both are /data/app apps, so
scripts/remove-app.sh backs up the APKs and uninstalls them.

2026-10-24: after uninstalling TXZ, the manufacturer's NwdManagerService
(nwdapp_AutoSlientInstall, inside system_server) reinstalled every missing app from
/mnt/media_rw/sdcard/preset_install at boot. Play Protect blocked TXZSmartWakeUp
("can't verify developer") and TXZOverseaAdapter ("unsafe app blocked"). The two APKs
were renamed to .apk.off (folder is group sdcard_rw, writable from adb). restore.sh
renames any .apk.off back. TXZAIPal (factory install, not running) left as is.
Other preset apps: Ambient, Electronic Money Card Balance (NFC e-money), EasyConnect,
YouTube 19.07.39 (older than installed), com.launcher2.

ZLink v5.4.39 with a Xiaomi phone on HyperOS (wireless Android Auto): slow to connect,
drops and reconnects, audio stutters. Cause: single-radio Wi-Fi doing double duty
(unit joined to the phone's hotspot while running ZLink's own hotspot, phone the
same in reverse) plus HyperOS Wi-Fi assistant leaving the internet-less AA network.
Fix: phone hotspot off during AA (or USB cable), Wi-Fi assistant off, Android Auto and
Play services unrestricted. ZLink 5.4.39 has no band setting (wireless AA uses 5 GHz).

Still open:
- sensor driver busy-loop (firmware bug; quiet right after reboot, watch whether it returns)
- TXZ voice assistant: ~13% CPU and 234 MB while idle
- leak check: run `./scripts/diagnose.sh` again after 2-3 days without rebooting and compare

## Orca Home launcher (launcher/, scripts/launcher.sh)
- Car Lab-style dashboard, own code and icons, CAKRA Labs logo; 7 themes, Midnight Drive default.
- Left panel: live map (OpenStreetMap tiles, darkened on the unit for dark themes, own tiny tile
  viewer, no traffic layer, no API key; CARTO was dropped in 2.2 because its free tiles now
  say "API key required") with speed bubble; nav card from Google Maps / Waze
  notifications; favourites row. Right: date + clock, Open-Meteo weather, music card. Dock below.
- Rule from the owner: it must never make the unit slower. Everything stops when home isn't
  visible; launcher.sh stops the old launcher and compares memory (launcher.sh check).
- First install in the car (2026-10-24, from the phone with Termux): Orca Home ran (map, GPS,
  weather, favourites found) but Home kept opening the factory launcher, although Android's
  default home app was Orca Home. This firmware picks the launcher itself
  (ro.nwd.start.launcher.by.fw=true) from persist.nwd.launcher.default, factory value
  com.android.launchertheme (there is also com.android.launcher). `setprop
  persist.nwd.launcher.default com.orcahome` fixed it; launcher.sh now does this, records the
  factory value in backup/nwd-home-before.txt, and `launcher.sh off` / restore.sh put it back.
  Termux needed `pkg upgrade` first (adb failed with "CANNOT LINK EXECUTABLE").
- Decided against: Google Maps embedded (not possible for a normal app; heavy), Mapbox (no 32-bit
  build, card required), TomTom traffic layer (works in Indonesia, 200k free tiles/month, but the
  owner chose map only).

## ZLink mode
The owner does not want Android Auto / CarPlay to connect by itself at car start: run the
optimizer with `--manual-projection` (ZLink only runs when opened from the home screen).
Without the flag, ZLink waits in the background and auto-connects (the default for others).
Navigation in Orca Home never opens ZLink; it stays a normal app tile.

## 2026-10-24 (afternoon): the real cause of the slowness, Google Play services start loop
- After reboots the unit had ~1,390 processes again (same as the "before optimizing" reading this
  morning), ~700 MB free RAM, slow app starts (YouTube 6.5 s, Spotify 4.9 s, all still
  speed-compiled), Play Store stuck on its logo.
- 1,100+ processes named <pre-initialized>, all uid 10019 = com.google.android.gms. Logcat:
  "Start proc ...gms for broadcast ...chimera.GmsIntentOperationService$PersistentTrustedReceiver"
  every 10 s, then "Killing ... (adj -10000): start timeout"; the killed copies stay (state S,
  epoll_wait). Happened with the factory launcher too, so not Orca Home.
- Play services had updated itself on 2026-10-06 to 26.36.66 (factory: 23.35.15). The
  morning baseline (263 processes) was also on 26.36, so the loop doesn't happen at every boot.
- Uninstalling the Play services updates (back to 23.35.15) gave 0 stuck processes, but Google
  sign-in no longer worked (too old). Installed the current 26.41.31 (APKMirror,
  040300 variant, armeabi-v7a, nodpi; Google-signed, so Android accepts it as an update)
  with `adb install -r` from the phone: clean boot (271 processes, 2,974 MB free), sign-in
  works after re-entering the password in YouTube. If the loop comes back, reboot; if it
  comes back at every boot, look at what starts it (logcat "Start proc ...gms").
  diagnose.sh flags this loop and names the app.
- Side findings with Orca Home as home on this NWD firmware: the boot "Select a Home app" box
  lists only the two factory launchers, and NwdBackgroundManagerService blocked Simple
  Keyboard ("skipService ... because of activity not started"); both went away with the
  factory launcher back as home. Orca Home stays installed but not as home for now.
