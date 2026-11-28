#!/usr/bin/env bash
# No Gradle, credentials or network required; needs jq.
# The script's output for these inputs must be the fixture UpdateManifestTest parses (#265).
# After changing the manifest on purpose, regenerate the fixture with: test-update-manifest.sh --write
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
script=$here/update-manifest.sh
fixture=$here/../../app/src/test/resources/velo-update.json
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work"

tag=v2.16.1 apk=velo-v2.16.1-arm64-v8a.apk
sha=$(printf 'abcdef0123456789%.0s' 1 2 3 4)
notes=$'<!-- android-version-code: 3000053 -->\n<!-- velo-changes -->\n### Fixed\n- Updates are found.\n<!-- /velo-changes -->\n\n## What\'s Changed\n- "Quoted" \\ text'
printf '%s  %s\n' "$sha" "$apk" > "$apk.sha256"
out=$(bash "$script" "$tag" 3000053 "$apk" "$notes")
if [[ ${1:-} == --write ]]; then
  printf '%s\n' "$out" > "$fixture"
  echo "Wrote $fixture"
  exit 0
fi
if ! diff -u "$fixture" <(printf '%s\n' "$out"); then
  echo "update-manifest.sh no longer writes the fixture UpdateManifestTest reads." >&2
  echo "If the change is meant, run it with --write and update ReleaseFeed to match." >&2
  exit 1
fi
fail() { echo "update-manifest.sh: $*" >&2; exit 1; }
# The keys and their types are what ReleaseFeed reads.
[[ $(jq -c '[to_entries[] | [.key, (.value | type)]]' <<<"$out") == \
  '[["tag","string"],["versionCode","number"],["apk","string"],["sha256","string"],["notes","string"]]' ]] ||
  fail "keys or types differ from what ReleaseFeed reads"
[[ $(jq -r .notes <<<"$out") == "$notes" ]] || fail "the notes did not come through unchanged"
# The notes release.yml passes come from changelog.sh body; they carry the marker this script checks.
changelog=$work/CHANGELOG.md
printf '# Changelog\n\n## Unreleased\n\n## 2.16.1 - 2026-11-28\n\n### Fixed\n- Updates are found.\n' > "$changelog"
body=$(CHANGELOG="$changelog" bash "$here/changelog.sh" body 2.16.1 3000053)
bash "$script" "$tag" 3000053 "$apk" "$body" >/dev/null || fail "rejected the notes changelog.sh writes"

reject() {
  if bash "$script" "$@" >/dev/null 2>&1; then
    echo "Unexpectedly accepted: $*" >&2; exit 1
  fi
}
reject "$tag" 3000053 "$apk"
reject "$tag" 3000054 "$apk" "$notes" # the marker says 3000053
reject "$tag" 3000053 "$apk" "Notes without a marker"
reject "$tag" 3000053 "$apk" "$notes"$'\n<!-- android-version-code: 3000053 -->' # two markers
reject "$tag" 03000053 "$apk" "$notes"
reject "$tag" 3000053x "$apk" "$notes"
reject "$tag" '' "$apk" "$notes"
reject "$tag" 3000053 velo-v2.16.0-arm64-v8a.apk "$notes" # not this tag's APK
reject v2.16.2 3000053 "$apk" "$notes"
printf '%s  %s\n' "$sha" velo-v2.16.0-arm64-v8a.apk > "$apk.sha256"
reject "$tag" 3000053 "$apk" "$notes" # the checksum names another file
printf '%s  %s\n' "$(tr a-f A-F <<<"$sha")" "$apk" > "$apk.sha256"
reject "$tag" 3000053 "$apk" "$notes" # sha256sum writes lowercase
printf '%s  %s\n' "${sha:1}" "$apk" > "$apk.sha256"
reject "$tag" 3000053 "$apk" "$notes"
rm "$apk.sha256"
reject "$tag" 3000053 "$apk" "$notes"
echo "update-manifest.sh: ok"
