#!/usr/bin/env bash
# Runs a Gradle command, and again when it failed only because a repository could not be
# reached (#212). release.yml keeps no dependency cache, so each release run downloads every
# plugin and library; the pull request checks keep one since #257 but still download on a miss
# or a new version. Between 2026-11-27 and 2026-11-28 the Compose plugin did not resolve and
# Maven Central answered 429 more than six times, and a manual rerun passed each time. A compile error, a failing test, a missing library or any other failure
# ends the run at once; the one exception is a plugin version that does not exist (see below).
#
# Robolectric downloads its android-all jar itself, inside the test JVM and not through Gradle;
# a 429 there ends as a failing test and is not retried.
#
# Maven Central's 429 can last longer than a few minutes (#221: on PR #216 three runs over
# 3.5 minutes all got it), so the waits add up to 12 minutes: 60, 120, 240 and 300 seconds.
#
# Usage: gradle-retry.sh <command> [args...]
# GRADLE_RETRY_ATTEMPTS (default 5) bounds the runs. GRADLE_RETRY_DELAY (default 60) is the
# wait in seconds before the second run; it doubles before each later one, up to
# GRADLE_RETRY_MAX_DELAY (default 300). A Retry-After of N seconds in the failure report makes
# that wait at least N. GRADLE_RETRY_WINDOW (default 900) bounds the waits together: when the
# next wait would pass it, the run ends instead. pr-check-android.yml's timeout-minutes leaves
# room for five runs and the whole window.
set -uo pipefail

if (($# == 0)); then
  echo "usage: $0 <command> [args...]" >&2
  exit 2
fi

attempts=${GRADLE_RETRY_ATTEMPTS:-5}
delay=${GRADLE_RETRY_DELAY:-60}
max_delay=${GRADLE_RETRY_MAX_DELAY:-300}
window=${GRADLE_RETRY_WINDOW:-900}
waited=0
delay=$((delay < max_delay ? delay : max_delay))

# Gradle prints each failure on stderr as a block from "* What went wrong:" to "* Try:".
# A block is a network failure when it names one of these. Plugin resolution names no status
# code: Gradle reports an unreachable portal the same way as a plugin version that does not
# exist, so a wrong version also costs the bounded retries before it fails.
network='Received status code (408|425|429|5[0-9][0-9]) from server'
network+='|could not resolve plugin artifact'
# A host that closes the connection without an answer, or resets it, shows as "failed to respond".
network+='|(Connect|Read) timed out|SocketTimeoutException|failed to respond'
network+='|Connection (reset|refused)|Remote host terminated the handshake'
network+='|UnknownHostException|Temporary failure in name resolution|No route to host'
network+='|Premature end of Content-Length|Truncated chunk'

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

# Prints the largest Retry-After in seconds that stderr shows, or nothing. Gradle 9.6.0 does not
# print response headers today; a server's or a later Gradle's message may carry one.
retry_after() {
  grep -oiE 'retry-after["'"'"']?[:= ]+[0-9]+' "$1" | grep -oE '[0-9]+$' | sort -n | tail -n 1
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
  pause=$delay
  asked=$(retry_after "$err")
  if [[ -n $asked ]] && ((10#$asked > pause)); then
    pause=$((10#$asked))
  fi
  if ((waited + pause > window)); then
    echo "::error::A repository could not be reached in $attempt attempts; waiting ${pause}s more would pass the ${window}s window"
    exit "$status"
  fi
  echo "::warning::A repository could not be reached (attempt $attempt of $attempts); retrying in ${pause}s"
  sleep "$pause"
  waited=$((waited + pause))
  delay=$((delay * 2 < max_delay ? delay * 2 : max_delay))
done
