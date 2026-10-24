#!/usr/bin/env bash
# Switches the head unit to a light keyboard and disables Gboard (~240 MB of
# RAM). Undo with ./scripts/restore.sh.
#
#   1. On the head unit: Play Store -> search "Simple Keyboard" (by rkkr) -> Install
#   2. ./scripts/keyboard.sh
. "$(dirname "$0")/common.sh"
require_device
GBOARD=com.google.android.inputmethod.latin

ID=$(dev ime list -a -s | grep -i simplekeyboard | head -1)
if [ -z "$ID" ]; then
  echo "Simple Keyboard isn't installed yet."
  echo "On the head unit: Play Store -> search \"Simple Keyboard\" (by rkkr) -> Install. Then run this again."
  exit 1
fi

info "Switching keyboard to $ID"
if ! backup_setting secure enabled_input_methods || ! backup_setting secure default_input_method; then
  warn "no answer from the unit; nothing changed — run this again"
  exit 1
fi
dev ime enable "$ID" >/dev/null
dev ime set "$ID" >/dev/null
if [ "$(dev settings get secure default_input_method)" != "$ID" ]; then
  warn "the unit didn't accept the new keyboard; nothing else changed"
  exit 1
fi
ok "default keyboard is now Simple Keyboard"

# Only disable Gboard once the new keyboard is safely the default.
if dev pm list packages -d | grep -qx "package:$GBOARD"; then
  ok "Gboard was already disabled"
elif pkg_installed "$GBOARD"; then
  if dev pm disable-user --user 0 "$GBOARD" | grep -q disabled; then
    echo "$GBOARD" >> "$STATE_DIR/disabled.txt"
    ok "Gboard disabled"
  else
    warn "couldn't disable Gboard (the new keyboard is still active)"
  fi
fi
echo
echo "Tap any text field on the head unit to check the new keyboard."
echo "Undo: ./scripts/restore.sh"
