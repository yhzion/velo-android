#!/bin/bash
# ui.sh: the device's screen as uiautomator sees it, one line per element with text or a
# content description: text, description, bounds.
set -euo pipefail
source "$(dirname "$0")/perf.env"
mkdir -p "$VELO_PERF_OUT"
XML="$VELO_PERF_OUT/ui.xml"
"$ADB" shell 'uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml' > "$XML"
python3 - "$XML" <<'PY'
import re, sys
x = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(r'text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="([^"]*)"', x):
    t, d, b = m.groups()
    if t or d:
        print(repr(t)[:60], repr(d)[:48], b)
PY
