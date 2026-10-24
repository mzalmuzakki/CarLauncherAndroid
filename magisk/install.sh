#!/usr/bin/env bash
# Installs the module on a rooted head unit over ADB.
. "$(dirname "$0")/../scripts/common.sh"
require_device
dev 'su -c id' | grep -q 'uid=0' || { echo "No root (su) access. See README section 11 first, and allow ADB/Shell in the Magisk app."; exit 1; }
"$(dirname "$0")/build.sh"
adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} push "$(host_path "$(dirname "$0")/../dist/headunit-optim-magisk.zip")" /data/local/tmp/ >/dev/null
dev 'su -c "magisk --install-module /data/local/tmp/headunit-optim-magisk.zip"'
echo
info "Installed. Reboot the unit, wait ~1 minute, then check: adb shell cat /data/local/tmp/headunit_optim.log"
echo "    Undo: Magisk app → Modules → remove, or: adb shell su -c 'touch /data/adb/modules/orca_headunit_optim/remove' && adb reboot"
