#!/bin/bash
# trace.sh NAME SECONDS [COMMAND...]
#
# Records a Perfetto trace of SECONDS on the device, runs COMMAND here 1.5 s into it, waits
# for the trace to end and pulls it to $VELO_PERF_OUT/traces/NAME.pftrace.
set -euo pipefail
source "$(dirname "$0")/perf.env"
NAME=$1
SECS=$2
shift 2
mkdir -p "$VELO_PERF_OUT/traces"
CFG="$VELO_PERF_OUT/run.cfg"
sed -e "s/DURATION/$((SECS * 1000))/" -e "s/PACKAGE/$VELO_PKG/" "$PERF_DIR/velo.cfg.tmpl" > "$CFG"
DEV=/data/misc/perfetto-traces/$NAME.pftrace
"$ADB" push -q "$CFG" /data/misc/perfetto-configs/run.cfg >/dev/null
"$ADB" shell "rm -f $DEV; perfetto --background --txt -c /data/misc/perfetto-configs/run.cfg -o $DEV" >/dev/null
sleep 1.5
if [ $# -gt 0 ]; then "$@"; fi
for _ in $(seq 1 $((SECS + 20))); do
  "$ADB" shell 'pidof perfetto' >/dev/null 2>&1 || break
  sleep 1
done
"$ADB" pull -q "$DEV" "$VELO_PERF_OUT/traces/$NAME.pftrace" >/dev/null
echo "$VELO_PERF_OUT/traces/$NAME.pftrace"
