#!/usr/bin/env bash
# Runs a Gradle command, and again when it failed only because a repository could not be
# reached (#212). The pull request checks keep no dependency cache, as release.yml keeps none,
# so each run downloads every plugin and library; between 2026-11-27 and 2026-11-28 the Compose
# plugin did not resolve and Maven Central answered 429 more than six times, and a manual rerun
# passed each time. A compile error, a failing test or any other failure ends the run at once.
#
# Usage: gradle-retry.sh <command> [args...]
# GRADLE_RETRY_ATTEMPTS (default 3) bounds the runs; GRADLE_RETRY_DELAY (default 60) is the
# wait in seconds before the second run, and doubles before each later one.
set -uo pipefail

if (($# == 0)); then
  echo "usage: $0 <command> [args...]" >&2
  exit 2
fi

attempts=${GRADLE_RETRY_ATTEMPTS:-3}
delay=${GRADLE_RETRY_DELAY:-60}

# Gradle prints each failure on stderr as a block from "* What went wrong:" to "* Try:".
# A block is a network failure when it names one of these. Plugin resolution names no status
# code: Gradle reports an unreachable portal the same way as a plugin version that does not
# exist, so a wrong version also costs the bounded retries before it fails.
network='Received status code (408|425|429|5[0-9][0-9]) from server'
network+='|could not resolve plugin artifact'
network+='|(Connect|Read) timed out|SocketTimeoutException'
network+='|Connection (reset|refused)|Remote host terminated the handshake'
network+='|UnknownHostException|Temporary failure in name resolution|No route to host'
network+='|Premature end of Content-Length'

# Succeeds when stderr holds at least one failure block and every block is a network failure.
network_only() {
  awk -v pattern="$network" '
    /^\* What went wrong:/ { inside = 1; blocks++; hit = 0; next }
    inside && /^\* Try:/ { inside = 0; if (hit) network++; next }
    inside && tolower($0) ~ tolower(pattern) { hit = 1 }
    END {
      if (inside && hit) network++
      exit !(blocks > 0 && network == blocks)
    }
  ' "$1"
}

err=$(mktemp)
trap 'rm -f "$err"' EXIT

for ((attempt = 1; ; attempt++)); do
  # stderr goes to the log as before, and to $err for the check; stdout is left alone.
  { "$@" 2>&1 1>&3 3>&- | tee "$err" >&2 3>&-; status=${PIPESTATUS[0]}; } 3>&1
  if ((status == 0)); then
    exit 0
  fi
  if ! network_only "$err"; then
    exit "$status"
  fi
  if ((attempt >= attempts)); then
    echo "::error::A repository could not be reached in $attempts attempts"
    exit "$status"
  fi
  echo "::warning::A repository could not be reached (attempt $attempt of $attempts); retrying in ${delay}s"
  sleep "$delay"
  delay=$((delay * 2))
done
