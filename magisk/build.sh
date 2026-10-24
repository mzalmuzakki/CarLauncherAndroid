#!/usr/bin/env bash
# Builds the flashable module zip into dist/.
set -e
cd "$(dirname "$0")/module"
out=../../dist/headunit-optim-magisk.zip
mkdir -p ../../dist && rm -f "$out"
zip -qr9 "$out" . -x '.*'
echo "Built $(cd ../../dist && pwd)/headunit-optim-magisk.zip"
