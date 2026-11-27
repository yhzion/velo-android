#!/bin/bash
# cold.sh: restart Velo on the device, so that the next trace starts with empty glyph atlases;
# wait until it is connected again, then clear the first tab of the perf session. The first
# tab is the one shown: Velo shows the window the server has focused.
set -euo pipefail
source "$(dirname "$0")/perf.env"
rex window focus -s "$VELO_PERF_SESSION" 1
# Not `monkey`: it unlocks the screen rotation as it ends, and the scenes need landscape.
"$ADB" shell "am force-stop $VELO_PKG; am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $VELO_PKG/.MainActivity >/dev/null"
for _ in $(seq 1 30); do
  sleep 1
  if "$PERF_DIR/ui.sh" 2>/dev/null | grep -q "Connection: Connected"; then break; fi
done
sleep 4
perf_send 1 clear
sleep 2
