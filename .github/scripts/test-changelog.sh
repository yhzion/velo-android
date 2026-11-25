#!/usr/bin/env bash
# No Gradle, credentials or network required.
set -euo pipefail
script=$(cd "$(dirname "$0")" && pwd)/changelog.sh
repo=$(cd "$(dirname "$0")/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fail() { echo "$1" >&2; exit 1; }
reject() {
  if "$@" >/dev/null 2>&1; then
    fail "Unexpectedly accepted: $*"
  fi
}
expect() {
  local want=$1; shift
  local got
  got=$("$@") || fail "Failed: $*"
  [[ $got == "$want" ]] || fail "Unexpected output of $*:"$'\n'"$got"$'\n'"Expected:"$'\n'"$want"
}

export CHANGELOG=$tmp/CHANGELOG.md
cat > "$CHANGELOG" <<'EOF'
# Changelog

Intro text with a - dash.

## Unreleased

### Added
- New thing.

## 2.9.20 - 2026-12-01

Ignored prose.
### Added
- A dot on Settings tells you when an update is ready.
  continuation lines are ignored
### Fixed
- Hangul no longer looks flat in D2Coding.

## 2.9.19 - 2026-11-25

### Changed

## 2.9.1 - 2026-01-01

### Fixed
- Old fix.

## 2.9.18 - 2026-11-25

### Frobbed
- Unknown heading.

## 2.9.17 - 2026-11-01

- Item before any heading.

## 2.9.16 - 2026-10-01

### Fixed
- An <!-- html comment --> breaks the block.

## 2.9.15 - 2026-09-01
### Fixed
- First.

## 2.9.15 - 2026-09-02
### Fixed
- Duplicate.
EOF

# section: only the ### and - lines of exactly that version.
expect $'### Added\n- A dot on Settings tells you when an update is ready.\n### Fixed\n- Hangul no longer looks flat in D2Coding.' \
  bash "$script" section 2.9.20
expect $'### Added\n- New thing.' bash "$script" section Unreleased
expect $'### Fixed\n- Old fix.' bash "$script" section 2.9.1
reject bash "$script" section 2.9.2     # missing; 2.9.20 and 2.9.1 must not match by prefix
reject bash "$script" section 2.9.19    # no item
reject bash "$script" section 2.9.18    # heading outside the shared format
reject bash "$script" section 2.9.17    # item without a heading
reject bash "$script" section 2.9.16    # would break the release-body block
reject bash "$script" section 2.9.15    # duplicate section
reject bash "$script" section 2.9.20-preview.1
reject bash "$script" section 2x9x20
reject bash "$script" section
CHANGELOG=$tmp/missing.md reject bash "$script" section 2.9.20

# body: the version-code marker, then the change block, exactly as the shared format.
expect '<!-- android-version-code: 3000038 -->
<!-- velo-changes -->
### Added
- A dot on Settings tells you when an update is ready.
### Fixed
- Hangul no longer looks flat in D2Coding.
<!-- /velo-changes -->' bash "$script" body 2.9.20 3000038
reject bash "$script" body 2.9.19 3000038
for code in '' 0 01 -1 3e6 '3000038 ' 12345678901; do
  reject bash "$script" body 2.9.20 "$code"
done
reject bash "$script" body 2.9.20

# pr-check: app/ or native/ changes need a change under ## Unreleased.
git=(git -C "$tmp/repo" -c user.name=test -c user.email=test@example.invalid -c commit.gpgsign=false)
git init -q -b main "$tmp/repo"
mkdir -p "$tmp/repo/app" "$tmp/repo/native" "$tmp/repo/docs"
printf '# Changelog\n\n## Unreleased\n\n## 1.0.0 - 2026-01-01\n\n### Added\n- First.\n' > "$tmp/repo/CHANGELOG.md"
echo a > "$tmp/repo/app/a.kt"
"${git[@]}" add -A && "${git[@]}" commit -qm base
base=$("${git[@]}" rev-parse HEAD)
commit() { "${git[@]}" add -A && "${git[@]}" commit -qm "$1" && "${git[@]}" rev-parse HEAD; }
pr_check() { (cd "$tmp/repo" && unset CHANGELOG && bash "$script" pr-check "$@"); }

echo docs > "$tmp/repo/docs/x.md"
docs_only=$(commit docs)
pr_check "$base" "$docs_only"

echo b >> "$tmp/repo/app/a.kt"
app_only=$(commit app)
reject pr_check "$base" "$app_only"

sed -i 's/^## 1.0.0/### Fixed\n- A fix.\n\n## 1.0.0/' "$tmp/repo/CHANGELOG.md"
with_entry=$(commit entry)
pr_check "$base" "$with_entry"

"${git[@]}" checkout -q -b native "$base"
echo z > "$tmp/repo/native/z.zig"
native_only=$(commit native)
reject pr_check "$base" "$native_only"

# A change below Unreleased (an old release) does not count.
sed -i 's/^- First\./- First, reworded./' "$tmp/repo/CHANGELOG.md"
old_entry=$(commit old-entry)
reject pr_check "$base" "$old_entry"

# The diff is taken from the merge base: changes that landed on main meanwhile do not count.
"${git[@]}" checkout -q main
"${git[@]}" checkout -q -b later "$base"
sed -i 's/^## 1.0.0/### Fixed\n- Landed on main.\n\n## 1.0.0/' "$tmp/repo/CHANGELOG.md"
later_main=$(commit later-main)
reject pr_check "$later_main" "$old_entry"
pr_check "$later_main" "$with_entry"

reject pr_check "$base"
reject pr_check "$base" not-a-commit

# The repository's own changelog has the released sections the shared format needs.
CHANGELOG=$repo/CHANGELOG.md bash "$script" section 2.9.19 >/dev/null
CHANGELOG=$repo/CHANGELOG.md bash "$script" section 2.9.18 >/dev/null
grep -qx '## Unreleased' "$repo/CHANGELOG.md"
(unset CHANGELOG && bash "$script" section 2.9.19 >/dev/null)
echo 'changelog.sh: all checks passed'
