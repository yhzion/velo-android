#!/bin/bash
# setup.sh: make the perf session on the local Rex server with five tabs of bash, or give it
# the tabs it lacks. Then show it once in the device's Velo: Velo opens the session it showed
# last, so the scenes find it there after each cold start. Use a session of its own: the
# device takes the size of the terminals it shows.
set -euo pipefail
source "$(dirname "$0")/perf.env"
rex session inspect "$VELO_PERF_SESSION" >/dev/null 2>&1 ||
  rex new "$VELO_PERF_SESSION" --window t1 -- bash >/dev/null
have=$(rex window ls -s "$VELO_PERF_SESSION" | tail -n +2 | grep -c . || true)
for i in $(seq $((have + 1)) 5); do
  rex window new -s "$VELO_PERF_SESSION" --focus=false "t$i" -- bash >/dev/null
done
rex window ls -s "$VELO_PERF_SESSION"
