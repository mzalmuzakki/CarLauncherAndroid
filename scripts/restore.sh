#!/usr/bin/env bash
# Reverts everything optimize.sh, keyboard.sh, remove-app.sh and launcher.sh
# changed, using the records in backup/. Each change is read back from the unit:
# only the ones that worked are crossed off, so running it again finishes the rest.
. "$(dirname "$0")/common.sh"
require_device
FAILED=0

# Keep only the record lines that still need undoing; remove the file when none do.
keep_failed() { # file failed-lines-file
  if [ -s "$2" ]; then mv "$2" "$1"; FAILED=1
  else rm -f "$1" "$2"; fi
}

# The link must work before anything is crossed off.
link_ok() { [ "$ON_DEVICE" = 1 ] || [ "$(dev echo ok)" = ok ]; }
lost() { warn "lost the connection to the unit — run ./scripts/restore.sh again"; exit 1; }

# No record (backups from another computer, or deleted) but Orca Home is still
# Android's home app: go back to the factory launcher anyway.
if [ ! -f "$STATE_DIR/home-before.txt" ] && current_home | grep -q '^com.orcahome/'; then
  original_home > "$STATE_DIR/home-before.txt"
fi

if [ -f "$STATE_DIR/home-before.txt" ]; then
  prev=$(cat "$STATE_DIR/home-before.txt")
  case "$prev" in
    ""|android/*|*ResolverActivity*)
      warn "the saved home screen isn't a real launcher; choose it on the unit: Settings opens now"
      dev am start -a android.settings.HOME_SETTINGS >/dev/null
      rm -f "$STATE_DIR/home-before.txt" ;;
    *)
      if dev cmd package set-home-activity "$prev" | grep -qi success; then
        ok "home screen back to $prev"; rm -f "$STATE_DIR/home-before.txt"
      else
        link_ok || lost
        warn "couldn't switch the home screen back to $prev (kept for the next run)"; FAILED=1
      fi ;;
  esac
fi

# Orca Home must not open itself at boot once it isn't the home screen.
pkg_installed com.orcahome && orca_boot off
maps_pip back || FAILED=1
maps_window off

# NWD firmwares that pick the launcher themselves (see launcher.sh); also when
# the record is missing but the setting still names Orca Home.
if ! nwd_home_back; then
  link_ok || lost
  FAILED=1
fi

# The manufacturer's auto-installer (NwdManagerService) reinstalls every .apk in
# this folder at boot; apps we keep away from it were renamed to .apk.off.
PRESET=/mnt/media_rw/sdcard/preset_install
dev ls $PRESET | grep '\.apk\.off$' | while IFS= read -r f; do
  dev mv "$(q "$PRESET/$f")" "$(q "$PRESET/${f%.off}")"
  if dev ls $PRESET | grep -qxF "${f%.off}"; then ok "auto-install re-enabled for ${f%.off}"
  else warn "couldn't rename $f back (run again)"; fi
done

if [ -f "$STATE_DIR/removed.txt" ]; then
  info "Reinstalling removed apps"
  : > "$STATE_DIR/removed.left"
  sort -u "$STATE_DIR/removed.txt" | while read -r p; do
    apks=()
    for f in "$STATE_DIR/apks/$p"/*.apk; do [ -f "$f" ] && apks+=("$(host_path "$f")"); done
    [ ${#apks[@]} -gt 0 ] || { warn "$p: no saved copy found"; continue; }
    if adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} install-multiple -r "${apks[@]}" </dev/null 2>&1 | grep -q Success; then
      ok "$p"
    else
      warn "$p could not be reinstalled; the APKs are in backup/apks/$p"
      echo "$p" >> "$STATE_DIR/removed.left"
    fi
  done
  keep_failed "$STATE_DIR/removed.txt" "$STATE_DIR/removed.left"
fi

if [ -f "$STATE_DIR/disabled.txt" ]; then
  info "Re-enabling apps"
  sort -u "$STATE_DIR/disabled.txt" | while read -r p; do dev pm enable "$p" >/dev/null; done
  link_ok || lost
  still=$(dev pm list packages -d | sed 's/^package://')
  : > "$STATE_DIR/disabled.left"
  sort -u "$STATE_DIR/disabled.txt" | while read -r p; do
    if echo "$still" | grep -qx "$p"; then warn "$p is still disabled"; echo "$p" >> "$STATE_DIR/disabled.left"
    else ok "$p"; fi
  done
  keep_failed "$STATE_DIR/disabled.txt" "$STATE_DIR/disabled.left"
fi

if [ -f "$STATE_DIR/settings.txt" ]; then
  info "Restoring settings"
  : > "$STATE_DIR/settings.left"
  while read -r ns key val; do
    # Never make a keyboard the default while it is still disabled: that leaves no keyboard.
    case "$key" in *input_method*)
      if [ -f "$STATE_DIR/disabled.txt" ] && grep -qxF "${val%%/*}" "$STATE_DIR/disabled.txt"; then
        warn "$ns/$key waits until ${val%%/*} is enabled again (kept for the next run)"
        echo "$ns $key $val" >> "$STATE_DIR/settings.left"; continue
      fi ;;
    esac
    if [ "$val" = "null" ]; then
      dev settings delete "$ns" "$key" >/dev/null
    elif [ -n "$val" ]; then
      dev settings put "$ns" "$key" "$(q "$val")" >/dev/null
    else
      # An empty record means the original was never read (lost reply): leave it.
      warn "$ns/$key: original value unknown — left as it is"; continue
    fi
    now=$(dev settings get "$ns" "$key")
    if [ "$now" = "$val" ]; then ok "$ns/$key = $val"
    else
      [ -n "$now" ] || link_ok || lost
      warn "$ns/$key is '$now', not '$val' (kept for the next run)"
      echo "$ns $key $val" >> "$STATE_DIR/settings.left"
    fi
  done < "$STATE_DIR/settings.txt"
  keep_failed "$STATE_DIR/settings.txt" "$STATE_DIR/settings.left"
fi

if [ -f "$STATE_DIR/props.txt" ]; then
  info "Restoring properties"
  : > "$STATE_DIR/props.left"
  while read -r name val; do
    dev setprop "$name" "$(q "$val")" >/dev/null
    if [ "$(dev getprop "$name")" = "$val" ]; then ok "$name = ${val:-(empty)}"
    else warn "$name didn't change (kept for the next run)"; echo "$name $val" >> "$STATE_DIR/props.left"; fi
  done < "$STATE_DIR/props.txt"
  link_ok || lost
  keep_failed "$STATE_DIR/props.txt" "$STATE_DIR/props.left"
fi

# Battery-optimisation exemptions: remove the ones optimize.sh added, give back
# the ones --manual-projection took away.
if [ -f "$STATE_DIR/whitelisted.txt" ] || [ -f "$STATE_DIR/unwhitelisted.txt" ]; then
  info "Battery optimisation back as it was"
  [ -f "$STATE_DIR/whitelisted.txt" ] && sort -u "$STATE_DIR/whitelisted.txt" | while read -r p; do
    dev cmd deviceidle whitelist -"$p" >/dev/null; done
  [ -f "$STATE_DIR/unwhitelisted.txt" ] && sort -u "$STATE_DIR/unwhitelisted.txt" | while read -r p; do
    dev cmd deviceidle whitelist +"$p" >/dev/null; done
  wl=$(user_whitelist) || lost
  : > "$STATE_DIR/whitelisted.left"; : > "$STATE_DIR/unwhitelisted.left"
  [ -f "$STATE_DIR/whitelisted.txt" ] && sort -u "$STATE_DIR/whitelisted.txt" | while read -r p; do
    if echo "$wl" | grep -qx "$p"; then warn "$p is still exempt"; echo "$p" >> "$STATE_DIR/whitelisted.left"
    else ok "$p optimised again"; fi
  done
  [ -f "$STATE_DIR/unwhitelisted.txt" ] && sort -u "$STATE_DIR/unwhitelisted.txt" | while read -r p; do
    if echo "$wl" | grep -qx "$p"; then ok "$p exempt again (as before)"
    else warn "$p isn't exempt yet"; echo "$p" >> "$STATE_DIR/unwhitelisted.left"; fi
  done
  keep_failed "$STATE_DIR/whitelisted.txt" "$STATE_DIR/whitelisted.left"
  keep_failed "$STATE_DIR/unwhitelisted.txt" "$STATE_DIR/unwhitelisted.left"
fi

# Background modes: back to the value saved before the first change (appops.txt).
# Apps only in restricted.txt (recorded by older versions) go back to "default".
if [ -f "$STATE_DIR/appops.txt" ] || [ -f "$STATE_DIR/restricted.txt" ]; then
  info "Background activity back as it was"
  todo=$(cat "$STATE_DIR/appops.txt" 2>/dev/null)
  if [ -f "$STATE_DIR/restricted.txt" ]; then
    for p in $(sort -u "$STATE_DIR/restricted.txt"); do
      echo "$todo" | grep -q "^$p " && continue
      todo="$todo
$p RUN_IN_BACKGROUND default
$p RUN_ANY_IN_BACKGROUND default"
    done
  fi
  : > "$STATE_DIR/appops.left"
  echo "$todo" | grep . | while read -r p op mode; do
    dev cmd appops set "$p" "$op" "$mode" >/dev/null
    now=$(appop_mode "$p" "$op")
    if [ "$now" = "$mode" ]; then ok "$p $op = $mode"
    else warn "$p $op is '${now:-?}', not '$mode' (kept for the next run)"; echo "$p $op $mode" >> "$STATE_DIR/appops.left"; fi
  done
  link_ok || lost
  keep_failed "$STATE_DIR/appops.txt" "$STATE_DIR/appops.left"
  # Everything restricted.txt stood for is now in appops.txt (or done).
  rm -f "$STATE_DIR/restricted.txt"
fi

if [ "$FAILED" = 1 ]; then
  warn "some changes couldn't be undone yet; run ./scripts/restore.sh again (the records are kept)"
else
  info "Restored. (Precompiled apps stay compiled — that is harmless.) Reboot the unit."
fi
