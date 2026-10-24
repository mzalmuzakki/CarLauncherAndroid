#!/usr/bin/env bash
# Removes apps the manufacturer keeps re-enabling, after saving their APKs to
# backup/apks/ so restore.sh can reinstall them. Only for normal apps
# installed in /data/app; system apps are refused.
#
#   ./scripts/remove-app.sh com.txznet.txz com.txznet.smartadapter
. "$(dirname "$0")/common.sh"
[ $# -gt 0 ] || { sed -n '2,6p' "$0"; exit 1; }
require_device
mkdir -p "$STATE_DIR/apks"

for p in "$@"; do
  info "$p"
  paths=$(dev pm path "$p" | sed -n 's/^package://p')
  [ -n "$paths" ] || { warn "not installed — skipped"; continue; }
  if echo "$paths" | grep -qv '^/data/app/'; then
    warn "system app (not in /data/app) — not removing it"; continue
  fi

  # Save every APK file of the app (base + splits) before touching anything.
  # Into a new folder first: a copy saved by an earlier run stays until this one is complete.
  dir="$STATE_DIR/apks/$p"; new="$dir.new"; rm -rf "$new"; mkdir -p "$new"
  saved=0
  for a in $paths; do
    f="$new/$(basename "$a")"
    adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} pull "$a" "$(host_path "$f")" >/dev/null 2>&1
    [ -s "$f" ] && saved=$((saved + 1))   # trust the file, not adb's exit code
  done
  if [ "$saved" -ne "$(echo "$paths" | wc -l)" ]; then
    warn "couldn't save a copy of the app — not removing it"; rm -rf "$new"; continue
  fi
  rm -rf "$dir" && mv "$new" "$dir"
  ok "saved $saved APK file(s) to backup/apks/$p"

  if dev pm uninstall "$p" | grep -q Success; then
    echo "$p" >> "$STATE_DIR/removed.txt"
    ok "removed"
  else
    warn "the unit refused to remove it (the saved copy is kept)"
  fi
done
echo
echo "Reboot, then check the car functions (steering-wheel keys, reverse camera,"
echo "radio, Bluetooth calls, ZLink, NFC). Undo: ./scripts/restore.sh"
