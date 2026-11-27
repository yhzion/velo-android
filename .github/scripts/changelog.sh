#!/usr/bin/env bash
# Read CHANGELOG.md for the release workflow and the pull request check. No network.
#
#   changelog.sh section VERSION   the ### and - lines of "## VERSION" (X.Y.Z or Unreleased)
#   changelog.sh body VERSION CODE the head of the release body: the version-code marker and the
#                                  <!-- velo-changes --> block the app shows before updating
#   changelog.sh pr-check BASE HEAD fail when an item under "## Unreleased" is over 200 characters,
#                                  or when app/ or native/ changed from the merge base of BASE and
#                                  HEAD to HEAD without changing the items under "## Unreleased"
#
# CHANGELOG overrides the file for section and body; pr-check reads CHANGELOG.md from git.
set -euo pipefail
export LC_ALL=C
die() { echo "changelog.sh: $1" >&2; exit 1; }
root=$(cd "$(dirname "$0")/../.." && pwd)
changelog=${CHANGELOG:-$root/CHANGELOG.md}
max_item_length=200

check_version() {
  local n='(0|[1-9][0-9]{0,8})'
  [[ $1 == Unreleased || $1 =~ ^$n\.$n\.$n$ ]] || die "expected X.Y.Z or Unreleased, not '$1'"
}

# The lines under "## VERSION" or "## VERSION - date", up to the next "## " heading, from stdin.
# Exits 3 unless there is exactly one such section.
lines() {
  awk -v v="$1" '
    /^## / { n = $0; sub(/ - .*/, "", n); inside = (n == "## " v); count += inside; next }
    inside { print }
    END { if (count != 1) exit 3 }'
}

# Only the lines the app reads: "### Section" and "- item".
kept() { grep -E '^(### |- )' || true; }

section() {
  local version=$1 raw line out='' heading='' count=0
  check_version "$version"
  raw=$(lines "$version" < "$changelog") || die "expected exactly one '## $version' section in $changelog"
  while IFS= read -r line; do
    case $line in
      '### '*)
        heading=${line#'### '}
        [[ $heading =~ ^(Added|Changed|Fixed|Removed|Security)$ ]] ||
          die "'$line' under '## $version': use Added, Changed, Fixed, Removed or Security"
        ;;
      '- '*)
        [[ -n $heading ]] || die "'$line' under '## $version' comes before any ### heading"
        [[ ${line#- } == *[![:space:]]* ]] || die "empty item under '## $version'"
        count=$((count + 1))
        ;;
      *) continue ;;
    esac
    # The release body delimits the block with HTML comments.
    [[ $line != *'<!--'* && $line != *'-->'* ]] || die "'$line' under '## $version' contains an HTML comment"
    out+=$line$'\n'
  done <<< "$raw"
  (( count > 0 )) || die "no '- ' item under '## $version'"
  printf '%s' "$out"
}

body() {
  local version=$1 code=$2 items
  [[ $version != Unreleased ]] || die 'a release body needs a released version'
  [[ $code =~ ^[1-9][0-9]{0,9}$ ]] && (( code <= 2100000000 )) || die "'$code' is not an Android versionCode"
  items=$(section "$version")
  printf '<!-- android-version-code: %d -->\n<!-- velo-changes -->\n%s\n<!-- /velo-changes -->\n' "$code" "$items"
}

# Fail if any "- " item under "## Unreleased" is over max_item_length characters (after "- ").
# Released sections are not checked.
check_unreleased_lengths() (
  local lineno=0 inside=0 line heading item len bad=0
  # Count characters, not UTF-8 bytes, to match ChangeNotes.kt's MAX_CHANGE_LENGTH.
  { export LC_ALL=C.UTF-8; } 2>/dev/null || true
  while IFS= read -r line || [[ -n $line ]]; do
    lineno=$((lineno + 1))
    if [[ $line == '## '* ]]; then
      heading=${line%% - *}
      if [[ $heading == '## Unreleased' ]]; then
        inside=1
      else
        inside=0
      fi
      continue
    fi
    (( inside )) || continue
    [[ $line == '- '* ]] || continue
    item=${line#'- '}
    len=${#item}
    if (( len > max_item_length )); then
      if (( bad == 0 )); then
        echo "Items under \"## Unreleased\" in CHANGELOG.md must be at most $max_item_length characters (after \"- \"):" >&2
      fi
      printf '  CHANGELOG.md:%d (%d characters): %s\n' "$lineno" "$len" "$line" >&2
      bad=1
    fi
  done
  if (( bad )); then
    echo "Shorten each item so Velo does not cut it off in What's new. See docs/releasing.md." >&2
    exit 1
  fi
)

pr_check() {
  local base=$1 head=$2 merge_base changed before after
  git rev-parse --verify --quiet "$head^{commit}" >/dev/null || die "'$head' is not a commit"
  merge_base=$(git merge-base "$base" "$head") || die "no merge base of '$base' and '$head'"
  { git show "$head:CHANGELOG.md" 2>/dev/null || true; } | check_unreleased_lengths
  changed=$(git diff --name-only "$merge_base" "$head" -- app native)
  if [[ -z $changed ]]; then
    echo 'No changes under app/ or native/: no changelog entry needed.'
    return
  fi
  unreleased() { { git show "$1:CHANGELOG.md" 2>/dev/null || true; } | { lines Unreleased || true; } | kept; }
  before=$(unreleased "$merge_base")
  after=$(unreleased "$head")
  if [[ $before == "$after" ]]; then
    {
      echo 'This change touches app/ or native/ but adds nothing under "## Unreleased" in CHANGELOG.md:'
      sed 's/^/  /' <<< "$changed" | head -n 20
      echo 'Add a short, user-facing "- " item under ### Added, Changed, Fixed, Removed or Security.'
      echo 'If users see no difference, label the pull request skip-changelog. See docs/releasing.md.'
    } >&2
    exit 1
  fi
  echo 'CHANGELOG.md has a new "## Unreleased" entry.'
}

case ${1:-} in
  section) (( $# == 2 )) || die 'usage: changelog.sh section VERSION'; section "$2" ;;
  body) (( $# == 3 )) || die 'usage: changelog.sh body VERSION CODE'; body "$2" "$3" ;;
  pr-check) (( $# == 3 )) || die 'usage: changelog.sh pr-check BASE HEAD'; pr_check "$2" "$3" ;;
  *) die 'usage: changelog.sh section VERSION | body VERSION CODE | pr-check BASE HEAD' ;;
esac
