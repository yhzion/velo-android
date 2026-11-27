#!/bin/bash
# lowend.sh on|off: make the device a slower one for the next runs, or undo it. Needs root.
#
# on: the big cores off (PERF_BIG_CPUS) and the GPU clock capped (PERF_GPU_LOW Hz).
# off: the cores back on and the GPU cap back at PERF_GPU_MAX Hz.
# The defaults are for the LG Velvet's Snapdragon 765G: cpu6 and cpu7, 275 and 540 MHz.
set -euo pipefail
source "$(dirname "$0")/perf.env"
: "${PERF_BIG_CPUS:=6 7}"
: "${PERF_GPU_LOW:=275000000}"
: "${PERF_GPU_MAX:=540000000}"
KGSL=/sys/class/kgsl/kgsl-3d0
case ${1:-} in
  on) online=0 gpu=$PERF_GPU_LOW ;;
  off) online=1 gpu=$PERF_GPU_MAX ;;
  *) echo "usage: lowend.sh on|off" >&2; exit 2 ;;
esac
SCRIPT=$(mktemp)
trap 'rm -f "$SCRIPT"' EXIT
{
  echo '#!/system/bin/sh'
  for cpu in $PERF_BIG_CPUS; do echo "echo $online > /sys/devices/system/cpu/cpu$cpu/online"; done
  # Lowering: the devfreq ceiling first, as the clock may not exceed it. Raising: the other way.
  if [ "$1" = on ]; then
    echo "echo $gpu > $KGSL/devfreq/max_freq; echo $gpu > $KGSL/max_gpuclk"
  else
    echo "echo $gpu > $KGSL/max_gpuclk; echo $gpu > $KGSL/devfreq/max_freq"
  fi
  echo "echo \"online=\$(cat /sys/devices/system/cpu/online) gpu_max=\$(cat $KGSL/max_gpuclk)\""
} > "$SCRIPT"
# A script file, not a command line: `su -c 'a; b'` runs only `a` as root on some devices.
"$ADB" push -q "$SCRIPT" /data/local/tmp/velo-lowend.sh >/dev/null
"$ADB" shell "su -c 'sh /data/local/tmp/velo-lowend.sh'; rm -f /data/local/tmp/velo-lowend.sh"
