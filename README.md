# Orca Android Head Unit Optimization

Make a slow Orca (or similar Chinese-platform: UIS7862/TS10, FYT, MTK, Allwinner) Android head unit fast again:
faster UI and app launch (Spotify, YouTube, Netflix…), a more stable Wi-Fi, and more reliable wireless
Android Auto / Apple CarPlay. **No root needed, and every scripted change can be undone.**

Do the steps in order. Steps 2 and 3 alone fix most units.

---

## 1. Why it is slow (read this first)

| Cause | Symptom | Fix |
|---|---|---|
| Too little real RAM (many units advertise 4–8 GB but have 2 GB) | Apps reload every time you switch | Fewer apps, debloat (step 4), lighter apps (step 6) |
| Storage more than 80% full | Everything lags, apps take 10+ s to open | Free space (step 3) |
| Too many apps auto-starting | Slow after every ignition-on | Debloat, disable auto-start (step 3) |
| Wi-Fi sleeping or scanning constantly | Wireless CarPlay/AA drops, laggy audio | Step 5 + script |
| Heat (unit behind dashboard, sun) | Gets slower the longer you drive | Check temperatures with `diagnose.sh` |
| Old firmware / old ZLink | CarPlay/AA won't connect or freezes | Update (step 5) |

## 2. Quick wins on the unit (5 minutes, no computer)

1. **Settings → Apps**: uninstall every app you don't use in the car (games, social media, second browsers, "cleaner"/"booster" apps — those make it *slower*).
2. **Settings → Storage**: keep at least **25% free**. Clear the cache of big apps (Spotify, YouTube, maps). In Spotify set *Storage → Downloads* to the SD card or reduce offline downloads.
3. **Factory / Car settings** (Settings → Car settings / Factory settings — the password is usually printed in the manual or given by the seller; common ones are `8888`, `1234`, `3368`, `1617`):
   - Turn **off** "kill apps when ACC off / sleep" only if you want quick resume; turn **on** "fast boot / sleep mode" if the unit offers it, so it doesn't cold-boot every time.
   - Disable auto-start for apps you don't need at ignition.
4. **Developer options** (Settings → About → tap *Build number* 7 times):
   - Window / transition / animator animation scale → **0.5x**
   - *Background process limit* → **At most 3 processes** (only if you have ≤2 GB RAM; makes switching apps reload more, but stops the whole system lagging).
5. **Restart the unit fully** once a week (long-press power → Reboot), not just ignition off.

## 3. Run the optimizer scripts (computer or phone, no root)

The scripts use **ADB**. You need a laptop (Windows/Mac/Linux) or an Android phone with a terminal, on the same Wi-Fi as the head unit.

**Connect:**
1. Install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (gives you `adb`). On Windows, run the scripts in *Git Bash* or WSL.
2. On the head unit: Developer options → enable **USB debugging** and, on Android 11+, **Wireless debugging**.
3. Connect the head unit and the laptop to the same Wi-Fi (e.g. your phone hotspot). Find the unit's IP in Settings → Wi-Fi → (network).
4. Connect:
   - Android 11+: Wireless debugging → *Pair device with pairing code*, then `adb pair <ip>:<pair-port>` and `adb connect <ip>:<port>`
   - Older firmware (most units allow this): `adb connect <ip>:5555`
5. Accept the "Allow debugging" prompt on the head unit screen. Check with `adb devices`.

**Run:**
```bash
./scripts/diagnose.sh               # read-only: shows real RAM, storage, heat, Wi-Fi band, memory hogs
./scripts/optimize.sh               # UI, Wi-Fi, CarPlay/AA keep-alive, app precompile, storage trim
./scripts/optimize.sh --dry-run     # list which unused apps would be disabled
./scripts/optimize.sh --debloat     # also disable them (edit scripts/packages-debloat.txt first)
./scripts/optimize.sh --keep-projection=zlink   # keep only ZLink alive, calm other phone-link apps
./scripts/optimize.sh --manual-projection   # ZLink etc. only run when you open them (no auto-connect at car start)
./scripts/app-speed.sh youtube --fix   # time one app's start, precompile it, time again
./scripts/wifi-speed.sh --measure      # time how long Wi-Fi takes to reconnect
./scripts/remove-app.sh <package>   # back up an app's APK to backup/apks/, then remove it (for apps the manufacturer keeps re-enabling, e.g. the TXZ voice assistant)
#   NWD-based units reinstall missing apps at boot from /mnt/media_rw/sdcard/preset_install;
#   rename that app's file there to .apk.off to stop it (restore.sh renames it back).
./scripts/zlink-check.sh            # while CarPlay/Android Auto runs: Wi-Fi double duty, CPU, heat
./scripts/launcher.sh               # install Orca Home, the light custom launcher (launcher/), and make it the home screen
                                    #   (also the unit's own launcher setting, persist.nwd.launcher.default, on NWD firmware,
                                    #   and Orca Home's start-up helper, which opens it over the firmware's boot-time
                                    #   "Select a Home app" box)
./scripts/keyboard.sh               # switch to the light "Simple Keyboard" and disable Gboard (install it from Play Store first)
./scripts/restore.sh                # undo all of it (checks each step; if the link drops, run it again)
```
Then **reboot the head unit**. **Turn the volume down first:** `adb reboot` restarts only the Android side while the amplifier stays powered, so the speakers give a loud "pop" (harmless now and then; a normal ignition off/on mutes the amplifier first). The first boot after optimizing can take a little longer; after that it is faster.

What `optimize.sh` does:
- **UI**: animation scales to 0.5x.
- **Wi-Fi**: turns off always-on Wi-Fi/Bluetooth scanning, "open network" notifications and auto-wake; stops Wi-Fi from sleeping when the screen dims (the classic cause of wireless CarPlay/AA drop-outs); skips the "is there internet?" check so networks are used straight away; turns off scan throttling.
- **CarPlay / Android Auto**: by default exempts ZLink / TLink / Carlinkit / EasyConnection / Android Auto from background killing and doze, so the phone connects by itself when you start the car. With `--manual-projection` they may not run in the background at all and only start when you open them (no auto-connect, less running in the background).
- **App launch**: fully pre-compiles Spotify, YouTube, YouTube Kids, YouTube Music, Netflix and the projection apps (`cmd package compile -m speed`) so they open much faster, then optimizes all other apps.
- **Firmware log spam**: if the sensor driver floods the log with `AccelSensor: unknown code` (seen on Allwinner T5/"UP" units: ~15,000 messages per second, keeping `logd` on a full CPU core), the `AccelSensors` log tag is muted. Only the messages are affected, not the sensor. If a technician ever needs to debug the tilt sensor, run `restore.sh` (or `adb shell setprop persist.log.tag.AccelSensors ""`) first.
- **Storage**: trims app caches and runs `fstrim` so the flash chip writes at full speed again.
- **Debloat** (opt-in): disables (not uninstalls) unused preinstalled apps. Apps listed in `scripts/packages-keep.txt` (Spotify, YouTube, YouTube Kids, Netflix, Google voice, ZLink by default) are never disabled. CAN-bus, NFC, Bluetooth, launcher, Google Play services and projection apps are always protected.

Changes are recorded in `backup/` — keep that folder if you want to restore later.

## 4. Wireless Android Auto & Apple CarPlay

Wireless CarPlay/AA on these units works over the unit's **own Wi-Fi hotspot (5 GHz) + Bluetooth**, usually via the **ZLink** (or TLink / Carlink) app.

1. **Update first**: newest firmware from Orca/the seller, and the newest ZLink app version for your platform.
2. **Clean pairing**: on the phone delete the car (iPhone: Settings → General → CarPlay → Forget; Android: Android Auto settings → Previously connected cars → Forget, and remove the unit in Bluetooth). On the unit delete the phone in Bluetooth and in ZLink. Then clear ZLink's storage (Settings → Apps → ZLink → Storage → Clear data) and pair again.
3. **Don't connect the head unit to your phone's hotspot while using wireless CarPlay/AA** (`./scripts/zlink-check.sh` warns when it happens) — most units have a single Wi-Fi radio and both at once causes lag/drops. Use the phone's internet through CarPlay/AA instead.
4. In ZLink settings:
   - Wi-Fi band / channel: **5 GHz**, try a fixed channel (36, 40, 149) if it keeps dropping.
   - Resolution / frame rate: lower it (e.g. 800×480 or 1024×600, 30 fps) — the biggest fix for laggy maps and touch.
   - Decoder: **hardware**.
   - Audio: set the output to the unit's own audio channel and turn off "phone call via unit mic" if calls echo.
5. On iPhone: Settings → General → CarPlay → (car) → toggle *Allow CarPlay While Locked* off and on once, and disable Wi-Fi *Low Data Mode*. On Android: Android Auto → developer settings (tap version 10×) → Wireless projection, set video to 480p/720p.
6. If wireless is still unstable, **use the cable**: a short, good USB data cable to the unit's main USB port is always faster and more reliable than wireless.
7. Battery saver on the phone kills the connection — exempt Android Auto from battery optimization.

## 5. Wi-Fi (slow to connect, drops, slow internet)

**Measure first:**
```bash
./scripts/wifi-speed.sh                 # signal strength, 2.4/5 GHz, saved networks
./scripts/wifi-speed.sh --measure       # turns Wi-Fi off/on and times the reconnect
./scripts/wifi-speed.sh --forget-others # keep only the network you're connected to now (asks first)
```
Under ~10 s to reconnect is normal for a head unit. On Android 11+ wireless ADB switches off when Wi-Fi drops during `--measure`; the script tells you how to read the result if that happens.

**Most common causes of slow connecting, and the fixes:**
1. **Too many saved networks.** After every start the unit scans for and tries each saved network (home, office, cafés…) before your phone. Keep **only your phone hotspot**: `--forget-others`, or Settings → Wi-Fi → Saved networks.
2. **The phone hotspot goes to sleep.** This is the #1 cause with iPhones.
   - **iPhone:** the hotspot stops being visible when nothing is connected. Turn on **Maximize Compatibility** (Settings → Personal Hotspot) and turn on *Allow Others to Join*. If the unit still can't find it, open the Personal Hotspot screen on the iPhone for a moment.
   - **Android phone:** Hotspot settings → turn **off** *Turn off hotspot automatically*. Set the band to 2.4 GHz if the unit doesn't see it. If the phone has *Wi-Fi 6 / WPA3* hotspot options, set WPA2 — many head units don't handle WPA3.
3. **Waiting for an IP address (DHCP).** Give the unit a fixed IP for your hotspot: Settings → Wi-Fi → long-press the network → Modify → Advanced → IP settings: **Static**.
   - **iPhone hotspot:** IP `172.20.10.5`, gateway `172.20.10.1`, prefix length `28`, DNS `1.1.1.1`.
   - **Android hotspot:** only if the phone always uses the same address range (Android 11+ phones often change it each time; if the unit then gets "no internet", switch back to DHCP).
4. **The "is there internet?" check.** Android tests every new connection before using it, which adds seconds and fails on slow mobile data. `optimize.sh` turns this check off (`captive_portal_mode 0`) and stops scan throttling.
5. **Privacy MAC.** On the unit, set the network's *Privacy* to **Use device MAC**, so the phone always recognises the unit.
6. **Full reboot at every ignition-on.** If the unit cold-boots each time, Wi-Fi only starts after Android has booted (30–60 s). Enable *sleep mode / fast boot* in the car/factory settings so it resumes instead.
7. **Weak signal** (`wifi-speed.sh` shows below −70 dBm): check the Wi-Fi antenna cable behind the unit (often loose after installation) and don't keep the phone in a pocket or a metal holder.
8. **2.4 GHz and Bluetooth share one antenna.** Using 2.4 GHz Wi-Fi with Bluetooth audio or calls can make both stutter. Prefer 5 GHz when the unit supports it.
9. **Wireless CarPlay/Android Auto at the same time.** Most units have one Wi-Fi radio. Don't connect the unit to the hotspot while using wireless CarPlay/AA (section 4).

## 6. Spotify, YouTube, Netflix and other apps

After step 3 they should open much faster. Additionally:
- **Update the apps** from the Play Store, but if a new version becomes too heavy, uninstalling updates on a 2 GB unit can help.
- **Spotify**: turn off Canvas (Settings → Playback → Canvas), lower streaming quality on mobile data, limit offline downloads.
- **YouTube / Netflix**: set video quality to 480p/720p — the screen is small and the decoder is weak; 1080p causes stutter. Netflix only runs in SD on most head units anyway.
- Don't keep more than 2–3 big apps open; return to the home screen instead of switching between many apps.
- Avoid "RAM booster" and "cleaner" apps; they restart apps in a loop and make things slower.
- On Google Maps / Waze, download the offline map for your area so it doesn't load tiles over Wi-Fi.

## 7. If your unit has only 2 GB RAM

2 GB is the real limit on these units: Android itself plus the car apps (launcher, CAN bus, Bluetooth, ZLink) already use about 1.2–1.4 GB, so only **one or two** big apps fit at a time. Spotify, YouTube and Netflix each want 200–400 MB, so they get killed and must cold-start every time — that is why they open so slowly. The goal is to free RAM for the apps you actually use.

**Run the 2 GB profile:**
```bash
./scripts/optimize.sh --lowram --dry-run    # see what would be disabled
./scripts/optimize.sh --lowram --debloat    # apply, then reboot
```
On top of the normal optimizations this:
- freezes apps sitting in the background so they stop using CPU (Android 11+) and puts rarely used apps in standby;
- stops heavy non-car apps from running in the background (`scripts/packages-restrict-bg.txt`: Netflix, social media, shopping/ride apps, Chrome, Play Store auto-updates). You can still open them normally;
- additionally disables the RAM-hungry Google app/Discover feed, YouTube Music, Google Maps, etc. (`scripts/packages-debloat-lowram.txt` — **delete the lines for apps you use** before running).
- Spotify, YouTube, navigation and CarPlay/Android Auto are never restricted, so music and navigation keep playing.

**Also do by hand:**
- Developer options → *Background process limit* → **At most 2 processes** (or 3 if switching apps reloads too much). This resets on reboot on some firmwares — set it again if it does.
- Developer options → *Don't keep activities* must stay **off** (it makes everything slower).
- Play Store → Settings → Network preferences → Auto-update apps → **Don't auto-update**. Update manually when parked at home on Wi-Fi.
- Keep **no more than ~15–20 extra apps** installed. Each installed app can start itself after ignition-on.
- Use a simple launcher and remove animated/live wallpapers and widgets (weather, clock, music widgets) from the home screen.
- If you mostly use CarPlay/Android Auto, use Spotify/YouTube/Maps **from the phone through CarPlay/AA** instead of the head-unit apps. The phone does all the work, and the head unit only shows the screen. This is the single biggest improvement on a 2 GB unit.
- Use lighter apps where they exist: *Google Go* instead of the Google app, the YouTube/Netflix quality at 480p.

**Check it worked:** run `./scripts/diagnose.sh` again after a reboot. "free now" should be noticeably higher (aim for 600 MB+ with no apps open).

### YouTube still takes ages to open

YouTube is one of the heaviest apps there is (~150 MB, and it gets bigger every update). On a 2 GB head unit with a slow processor and eMMC storage it can take 10–20 s to cold-start even when everything else is fine. In order:

1. **Measure and precompile it:**
   ```bash
   ./scripts/app-speed.sh youtube          # cold-start time, compile state, free RAM
   ./scripts/app-speed.sh youtube --fix    # precompile + trim, then measure again
   ```
   If "Compiled as" is anything other than `speed`, the app runs partly interpreted on every start. On these units, precompiling often roughly halves the start time. Re-run `--fix` after every YouTube update, since an update throws away the precompiled code.
2. **Update Android System WebView and Google Play services** in the Play Store. YouTube waits on both at start-up, and head units often ship with very old versions.
3. **Clear YouTube's data once** (Settings → Apps → YouTube → Storage → Clear data). Its database grows large over time. You'll need to sign in again.
4. **Lighter ways to watch YouTube**, if it's still too slow:
   - **YouTube in the browser:** open `m.youtube.com` in Chrome → menu → *Add to Home screen*. It starts much faster than the app.
   - **NewPipe** (free, open-source, from F-Droid): a lightweight YouTube player, without Google Play services overhead, and it can play audio in the background.
   - For music, use **YouTube Music / Spotify through CarPlay/Android Auto** from the phone instead.
5. If YouTube is still over ~10 s after `--fix` with 600 MB+ free RAM, that's the hardware's limit. The processor is simply too weak for the current YouTube app, and a lighter client from point 4 is the real fix.

### Lighter keyboard
Gboard uses ~240 MB of RAM all the time. On the head unit, install **Simple Keyboard** (by rkkr) from the Play Store, then run `./scripts/keyboard.sh`. It makes Simple Keyboard the default and only then disables Gboard. `restore.sh` switches back. You lose voice typing and word suggestions, which you rarely need in a car.

## 8. App updates take forever

An update is two jobs: **downloading** (limited by the phone hotspot) and **installing**. Installing is the slow part on head units. The weak processor has to recompile the whole app (YouTube ~150 MB), and the slow eMMC storage has to write it, which can take several minutes per app.

- **Update at home on fast Wi-Fi, while parked, with the engine running.** Don't update while driving or over a weak hotspot.
- **One app at a time.** Updating 10 apps at once on 2 GB RAM makes everything crawl. Play Store → Manage apps → Updates available → update one by one, biggest first.
- **Free storage first** (keep 25%+ free). With an almost-full eMMC, writing the update is what's slow. Run `./scripts/optimize.sh` before updating (it trims caches and runs `fstrim`).
- **Close everything else** before updating (`optimize.sh` does this at the end too).
- **Stuck on "Pending" or "Downloading…" forever:** Settings → Apps → *Google Play Store* → Storage → **Clear cache**. Do the same for *Google Play services* and *Download Manager* (show system apps). Then reboot.
- **Update Google Play Store and Google Play services first** (Play Store → Settings → About → *Update Play Store*). Old versions on head units make every other update slow.
- **Only update what you use.** Uninstall apps you never open, so there's nothing to update.
- **Turn off auto-updates** (section 7) so the unit doesn't update in the background while you drive.
- **After updating**, run `./scripts/app-speed.sh youtube --fix` (or `optimize.sh`) again. An update throws away the precompiled version, so the app is slow again until it's recompiled.

## 9. NFC

The NFC module (card/phone reader) is protected by the scripts and keeps working. If NFC stops responding after a firmware update, re-enable it in Settings → Connected devices → NFC and reboot.

## 10. Choosing a faster head unit

If you replace the unit, any brand can be fast; what matters is the hardware inside (the scripts work on any Android head unit):

| Part | Avoid (slow) | Get (fast) |
|---|---|---|
| RAM | 1–2 GB | **4 GB minimum, 6–8 GB ideal** (real, not faked) |
| Storage | eMMC | **UFS** storage, 64 GB+ |
| Processor | Allwinner T3/T5, old MTK, 4-core 1.3 GHz | **8-core**: Qualcomm Snapdragon (e.g. 662/665/680, QCM6125) or UNISOC UIS7862 / UIS7870 |
| Android | Android 9/10 | **Android 12 or newer** |
| CarPlay/AA | through a ZLink app only | **Built-in** wireless CarPlay/Android Auto, 5 GHz Wi-Fi |
| Firmware | no updates from seller | brand that publishes firmware updates (e.g. Teyes, Joying, Dasaita are known for this) |

**Verify before you pay:** fake specs are very common. In the shop, open Settings → About, and install *CPU-Z* (or run `./scripts/diagnose.sh`) to check the real RAM, processor and Android version. Open YouTube from a cold start; it should open in under 3 seconds.

**Cheaper option:** if you mainly use CarPlay/Android Auto, the phone does the work and even a slow unit is fine as a screen. Use wired CarPlay/AA and play Spotify/YouTube Music/Maps from the phone.

## 11. Deeper: modifying the Android system (root)

Without root, `optimize.sh` is as far as Android allows. With root (Magisk), the system itself can be tuned. On a 2 GB unit the biggest gain is **zram**: compressed swap in RAM that makes 2 GB behave like about 3 GB, so YouTube/Spotify stay in memory instead of cold-starting every time.

**Module in `magisk/`** (removable; nothing is written to the system partition):
- zram at 75% of RAM with lz4 compression, swappiness 100 (if the vendor already uses zram, it's resized; if the kernel has no zram, it's skipped);
- eMMC: low-latency I/O scheduler (mq-deadline/deadline/bfq), 512 KB read-ahead for faster app loading, `fstrim` at every boot;
- faster app installs/updates (`pm.dexopt.install=verify`: apps are compiled later in the background instead of during the install; run `app-speed.sh --fix` for apps you want compiled right away);
- deliberately **not** included: forcing the CPU to max speed (overheats behind the dashboard and then throttles), Android Go "low RAM" mode (breaks some apps).

```bash
./scripts/diagnose.sh    # check "Root" and "zram support" in the System section
./magisk/install.sh      # rooted units: builds, installs, then reboot
adb shell cat /data/local/tmp/headunit_optim.log   # after reboot: what was applied
./scripts/diagnose.sh    # zram/swap should now show ~1400 MB
```
Or build with `./magisk/build.sh` and install `dist/headunit-optim-magisk.zip` in the Magisk app → Modules → Install from storage.

**Undo:** Magisk app → Modules → remove → reboot. Without the app: `adb shell su -c 'touch /data/adb/modules/orca_headunit_optim/remove'` then `adb reboot`. The module only runs after Android has fully booted, so it can't stop the unit from starting.

**Getting root: depends on the chip (send the output of `diagnose.sh` first).**
1. **Back up first.** Take photos of all factory/car settings (CAN-bus profile, steering-wheel keys, screen/amp settings). Get the **official firmware file for your exact model** from Orca/the seller, so you can always reflash.
2. Some units have a **root switch** in Developer options or factory settings. Check there first.
3. Otherwise the usual method: take `boot.img` (or `init_boot.img`) from the official firmware, patch it in the Magisk app, and flash it back with the chip's flashing tool or update method. The steps differ per platform (UNISOC, Allwinner, MediaTek, Rockchip).
4. **Risks:** a wrong image can brick the unit until it's reflashed. Car features (CAN-bus, NFC, ZLink) may need their settings re-entered. Warranty is void. Never flash firmware made for a different model or MCU.

## 12. Last resort

If the unit is still slow after all this: back up your settings (factory settings → export, take photos of the CAN-bus/steering-wheel settings), then **factory reset** and install only the apps you need. Re-check the CAN-bus profile and steering wheel keys after reset.

---
**Safety:** only change settings when parked. If anything misbehaves after optimizing, run `./scripts/restore.sh` and reboot.

## Play services start loop (unit suddenly slow, Play Store stuck on its logo)
`./scripts/diagnose.sh` warns "processes stuck starting (app: com.google.android.gms)": Google
Play services keeps starting and never finishes, 1,000+ copies, RAM gone. It doesn't happen at
every boot, so reboot first. If it keeps coming back: download the current Google Play services
from apkmirror.com (variant **armeabi-v7a**, **nodpi**, **APK**, Android 10 or lower) and install
it with `adb install -r <file>.apk`, then reboot. Don't just "Uninstall updates": the factory
version is too old to sign in to Google.

## Going back from Orca Home
- `./scripts/launcher.sh off` (or `./scripts/restore.sh`): factory launcher back as the home
  screen, the unit's own launcher setting back (NWD firmware), Orca Home no longer opens at
  start-up. Works even without the records in `backup/` (another computer, a fresh clone).
- By hand, if the scripts aren't at hand (any adb, e.g. Termux on the phone):
  ```
  adb exec-out setprop persist.nwd.launcher.default com.android.launchertheme
  adb exec-out cmd package set-home-activity com.android.launchertheme/com.android.launcher2.Launcher
  adb exec-out appops set com.orcahome SYSTEM_ALERT_WINDOW default
  adb exec-out appops set com.google.android.apps.maps PICTURE_IN_PICTURE default
  adb exec-out appops write-settings
  adb exec-out settings put global enable_freeform_support 0   # only if you used maps-window
  ```
  then press Home. (`com.android.launchertheme` is "UI Style", the factory launcher on the
  owner's unit; the other built-in one is `com.android.launcher`.)

## Building Orca Home
GitHub Actions builds `launcher/orca-home.apk` on every change under `launcher/` and commits it,
so `git pull` + `./scripts/launcher.sh` is all a user needs. The signing key is not in the
repository; the workflow reads it from two repository secrets (Settings → Secrets and variables
→ Actions):
- `ORCAHOME_KEYSTORE_B64`: the keystore file, base64-encoded (`base64 -w0 orcahome.jks`)
- `ORCAHOME_KEY_PASSWORD`: its password

Builds must always use the same key, or Android refuses them as updates (then uninstall Orca
Home once and install again). To build yourself, set `ORCAHOME_KEYSTORE` (path to the keystore)
and `ORCAHOME_KEY_PASSWORD`, then run `gradle -p launcher assembleRelease` with JDK 17.
