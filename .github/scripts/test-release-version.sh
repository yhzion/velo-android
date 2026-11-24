#!/usr/bin/env bash
# No Gradle, credentials or network required.
set -euo pipefail
script=$(dirname "$0")/release-version.sh
check() {
  local tag=$1 run=$2 code=$3
  [[ $(bash "$script" "$tag" 2.5.4 "$run") == "RELEASE_VERSION_NAME=${tag#v}"$'\n'"RELEASE_VERSION_CODE=$code" ]]
}
reject() {
  if bash "$script" "$@" >/dev/null 2>&1; then
    echo "Unexpectedly accepted: $*" >&2; exit 1
  fi
}
check v2.5.4 1 3000001
check v2.5.4-preview.1 42 3000042
check v2.5.4-edge.999999999 2097000000 2100000000
GITHUB_RUN_ATTEMPT=7 check v2.5.4-preview.1 42 3000042
public_check() {
  local run=$1 code=$2
  [[ $(bash "$script" v2.9.18 2.9.18 "$run" yhzion/velo-android) == 'RELEASE_VERSION_NAME=2.9.18'$'\n'"RELEASE_VERSION_CODE=$code" ]]
}
public_check 1 3000036
public_check 2 3000037
public_check 2096999965 2100000000
reject v2.9.18 2.9.18 2096999966 yhzion/velo-android
for suffix in -preview -edge. -rc.1 -preview.0 -edge.01 -edge.1000000000 -preview.1.0 +build; do
  reject "v2.5.4$suffix" 2.5.4 42
done
for base in 2.5.3 2.4.999 2.05.4 2.5.1000 2100.0.1; do
  reject "v$base" "$base" 42
done
reject v2.5.5-preview.1 2.5.4 42
for run in 0 -1 01 1+1 2097000001 12345678901234567890; do
  reject v2.5.4 2.5.4 "$run"
done
