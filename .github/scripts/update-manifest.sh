#!/usr/bin/env bash
# Usage: update-manifest.sh <tag> <version-code> <apk> <notes>
# Prints velo-update.json, the update manifest release.yml publishes with each release and the
# app's ReleaseFeed reads from releases/latest/download/. The digest comes from <apk>.sha256 in the
# current directory, the checksum asset published beside the APK, which the app checks it against.
# app/src/test/resources/velo-update.json is this script's output for test-update-manifest.sh's
# inputs, and UpdateManifestTest parses it: a key renamed here fails both tests (#265).
set -euo pipefail
(( $# == 4 )) || { echo "Usage: $0 <tag> <version-code> <apk> <notes>" >&2; exit 2; }
tag=$1 code=$2 apk=$3 notes=$4
[[ $code =~ ^[1-9][0-9]{0,9}$ ]] || { echo "Invalid version code: $code" >&2; exit 1; }
[[ $apk == "velo-$tag-arm64-v8a.apk" ]] || { echo "Unexpected APK name: $apk" >&2; exit 1; }
# ReleaseFeed takes a manifest only when its notes carry one version-code marker, for this code.
markers=$(grep -F -- '<!-- android-version-code' <<<"$notes" || true)
[[ $markers == "<!-- android-version-code: $code -->" ]] ||
  { echo "The notes need one version-code marker for $code" >&2; exit 1; }
checksum=$(<"$apk.sha256")
[[ $checksum =~ ^([0-9a-f]{64})\ \ (.+)$ && ${BASH_REMATCH[2]} == "$apk" ]] ||
  { echo "Unexpected checksum line in $apk.sha256" >&2; exit 1; }
jq -n --arg tag "$tag" --argjson code "$code" --arg apk "$apk" --arg sha256 "${BASH_REMATCH[1]}" \
  --arg notes "$notes" '{tag: $tag, versionCode: $code, apk: $apk, sha256: $sha256, notes: $notes}'
