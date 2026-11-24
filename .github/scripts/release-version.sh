#!/usr/bin/env bash
# Emit only validated single-line assignments for the build job's GITHUB_ENV.
set -euo pipefail
export LC_ALL=C
die() { echo "$1" >&2; exit 1; }
tag=$1 base=$2 run=$3
# The public repository starts a new run sequence after archive code 3000035.
offset=3000000
if [[ ${4:-} == yhzion/velo-android ]]; then offset=3000035; fi
major='(0|[1-9][0-9]{0,3})'
part='(0|[1-9][0-9]{0,2})'
[[ $base =~ ^$major\.$part\.$part$ ]] || die 'VERSION must be canonical X.Y.Z (minor and patch <= 999)'
legacy=$((BASH_REMATCH[1] * 1000000 + BASH_REMATCH[2] * 1000 + BASH_REMATCH[3]))
(( legacy > 2005003 && legacy <= 2100000000 )) || die 'Release base must be above 2.5.3 and within Android version bounds'
[[ ${tag%%-*} == "v$base" ]] || die 'Tag base differs from VERSION'
suffix=${tag#"v$base"}
[[ -z $suffix || $suffix =~ ^-(preview|edge)\.[1-9][0-9]{0,8}$ ]] || die 'Expected vX.Y.Z, vX.Y.Z-preview.N or vX.Y.Z-edge.N (N = 1..999999999)'
[[ $run =~ ^[1-9][0-9]{0,9}$ ]] && (( run <= 2100000000 - offset )) || die 'Run number cannot map to an Android versionCode'
printf 'RELEASE_VERSION_NAME=%s\nRELEASE_VERSION_CODE=%d\n' "${tag#v}" "$((offset + run))"
