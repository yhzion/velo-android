#!/bin/bash
# scenes.sh PREFIX [SCENE...]
#
# Records each SCENE on the device from a cold start of the app, into
# $VELO_PERF_OUT/traces/PREFIX-SCENE.pftrace. Without a SCENE, all of them in this order:
#
#   ko2350        8 s of random common Hangul, 30 lines a second (glyph atlas misses)
#   prose         8 s of Korean text, 30 lines a second ($VELO_PERF_TEXT, or `load.py prose`)
#   ascii         8 s of random ASCII, 30 lines a second (the atlas has it after a few lines)
#   textsize      Text Size Larger, then Reset, from the pane menu (every glyph drawn again)
#   title         30 title changes a second for 8 s and nothing else (state and redraw path)
#   overview      the tab overview: four swipes each way, then back to a tab
#   overview-out  the tab overview open while every tab prints at once (an atlas per preview)
#   tabs          every tab holds its own Korean text; tabs 2, 3, 4, 5 and 1 shown in turn
#
# The perf session needs five tabs (`setup.sh`), shown by the device's Velo. The taps are for
# the LG Velvet in landscape (2460 x 1080 px, 95 columns); set the PERF_* variables below for
# another screen. The rail's Overview button moves with the buttons above it: unless
# PERF_TAP_OVERVIEW is set, the scenes that open the overview find it on the screen first.
set -euo pipefail
source "$(dirname "$0")/perf.env"
: "${PERF_COLS:=95}"
: "${PERF_TAP_PANE_MENU:=2361 471}"
: "${PERF_SWIPE_MENU:=2160 1000 2160 200 400}"
: "${PERF_TAP_LARGER:=2140 278}"
: "${PERF_TAP_RESET:=2140 542}"
PERF_TAP_OVERVIEW_SET=${PERF_TAP_OVERVIEW:-}
: "${PERF_TAP_CARD:=1181 475}"
: "${PERF_SWIPE_NEXT:=1900 450 1100 450 250}"
: "${PERF_SWIPE_BACK:=600 450 1400 450 250}"
export PERF_COLS PERF_TAP_PANE_MENU PERF_SWIPE_MENU PERF_TAP_LARGER PERF_TAP_RESET \
  PERF_TAP_OVERVIEW PERF_TAP_CARD PERF_SWIPE_NEXT PERF_SWIPE_BACK PERF_DIR ADB
export -f perf_send

[ $# -ge 1 ] || { sed -n '2,19p' "$0"; exit 2; }
PREFIX=$1
shift
SCENES=("$@")
[ ${#SCENES[@]} -gt 0 ] || SCENES=(ko2350 prose ascii textsize title overview overview-out tabs)

LOAD="python3 $PERF_DIR/load.py"
# What the prose scenes print: SECONDS RATE.
prose() {
  if [ -n "${VELO_PERF_TEXT:-}" ]; then echo "$LOAD file $VELO_PERF_TEXT $1 $2"; else echo "$LOAD prose $1 $2 $PERF_COLS"; fi
}

send1() { perf_send 1 "$1"; }
export -f send1

# The centre of the control a screen reader calls $1, from a dump of the screen; nothing when
# the screen has none.
desc_center() {
  "$PERF_DIR/ui.sh" >/dev/null
  python3 - "$VELO_PERF_OUT/ui.xml" "$1" <<'PY'
import re, sys
x = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r'content-desc="%s"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(sys.argv[2]), x)
if m:
    a, b, c, d = map(int, m.groups())
    print((a + c) // 2, (b + d) // 2)
PY
}

# Before a scene that opens the overview: where its button is now.
find_overview() {
  [ -n "$PERF_TAP_OVERVIEW_SET" ] && return
  PERF_TAP_OVERVIEW=$(desc_center Overview)
  [ -n "$PERF_TAP_OVERVIEW" ] || { echo "no Overview button on the screen" >&2; exit 1; }
  export PERF_TAP_OVERVIEW
}

menu() {
  "$ADB" shell "input tap $PERF_TAP_PANE_MENU; sleep 0.9; input swipe $PERF_SWIPE_MENU; sleep 0.9; input tap $1"
}
textsize_steps() {
  menu "$PERF_TAP_LARGER"
  sleep 4
  menu "$PERF_TAP_RESET"
}
overview_steps() {
  "$ADB" shell "input tap $PERF_TAP_OVERVIEW; sleep 1.5
    for i in 1 2 3 4; do input swipe $PERF_SWIPE_NEXT; sleep 1; done
    for i in 1 2 3 4; do input swipe $PERF_SWIPE_BACK; sleep 1; done
    input tap $PERF_TAP_CARD"
}
overview_out_steps() {
  "$ADB" shell "input tap $PERF_TAP_OVERVIEW"
  sleep 2
  local i ranges=("1,40" "20,60" "60,100" "100,140" "140,180")
  for i in 1 2 3 4 5; do
    if [ -n "${VELO_PERF_TEXT:-}" ]; then
      perf_send "$i" "clear; sed -n '${ranges[i - 1]}p' $VELO_PERF_TEXT" &
    else
      perf_send "$i" "clear; python3 $PERF_DIR/load.py lines $i 40 $PERF_COLS" &
    fi
  done
  wait
  sleep 4
  "$ADB" shell "input tap $PERF_TAP_CARD"
}
# Every tab prints its own text, before the trace: what a tab shows when it is shown.
fill_tabs() {
  local i ranges=("1,40" "20,60" "60,100" "100,140" "140,180")
  for i in 1 2 3 4 5; do
    if [ -n "${VELO_PERF_TEXT:-}" ]; then
      perf_send "$i" "clear; sed -n '${ranges[i - 1]}p' $VELO_PERF_TEXT"
    else
      perf_send "$i" "clear; python3 $PERF_DIR/load.py lines $i 40 $PERF_COLS"
    fi
  done
}
tabs_steps() {
  local i
  for i in 2 3 4 5 1; do
    rex window focus -s "$VELO_PERF_SESSION" "$i"
    sleep 1.5
  done
}
export -f menu textsize_steps overview_steps overview_out_steps tabs_steps

for scene in "${SCENES[@]}"; do
  "$PERF_DIR/cold.sh"
  case $scene in
    ko2350) "$PERF_DIR/trace.sh" "$PREFIX-$scene" 11 send1 "$LOAD ko 8 30 $PERF_COLS" ;;
    prose) "$PERF_DIR/trace.sh" "$PREFIX-$scene" 11 send1 "$(prose 8 30)" ;;
    ascii) "$PERF_DIR/trace.sh" "$PREFIX-$scene" 11 send1 "$LOAD ascii 8 30 $PERF_COLS" ;;
    textsize)
      send1 "$(prose 1 60)"
      sleep 4
      "$PERF_DIR/trace.sh" "$PREFIX-$scene" 14 textsize_steps
      ;;
    title) "$PERF_DIR/trace.sh" "$PREFIX-$scene" 11 send1 "$LOAD title 8 30" ;;
    overview) find_overview; "$PERF_DIR/trace.sh" "$PREFIX-$scene" 16 overview_steps ;;
    overview-out) find_overview; "$PERF_DIR/trace.sh" "$PREFIX-$scene" 12 overview_out_steps ;;
    tabs)
      fill_tabs
      sleep 3
      "$PERF_DIR/trace.sh" "$PREFIX-$scene" 11 tabs_steps
      ;;
    *) echo "unknown scene: $scene" >&2; exit 2 ;;
  esac
done
