#!/bin/bash
# setup.sh: make the perf session on the local Rex server, five tabs of bash, unless it is
# there already. Then show it once in the device's Velo: Velo opens the session it showed
# last, so the scenes find it there after each cold start. Use a session of its own: the
# device takes the size of the terminals it shows.
set -euo pipefail
source "$(dirname "$0")/perf.env"
if rex session inspect "$VELO_PERF_SESSION" >/dev/null 2>&1; then
  echo "$VELO_PERF_SESSION is there already:"
else
  rex new "$VELO_PERF_SESSION" --window t1 -- bash >/dev/null
  for i in 2 3 4 5; do rex window new -s "$VELO_PERF_SESSION" --focus=false "t$i" -- bash >/dev/null; done
fi
rex window ls -s "$VELO_PERF_SESSION"
