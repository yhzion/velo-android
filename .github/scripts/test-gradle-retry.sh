#!/usr/bin/env bash
# Self-test for gradle-retry.sh: a fake Gradle prints the failure reports of real pull request
# runs (#212). No Gradle, credentials or network required.
set -euo pipefail
script=$(cd "$(dirname "$0")" && pwd)/gradle-retry.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export GRADLE_RETRY_DELAY=0

# Run N prints $work/out.N on stderr and exits with $work/status.N (0 when that file is absent).
cat >"$work/fake-gradle" <<'EOF'
#!/usr/bin/env bash
dir=$(dirname "$0")
n=$(($(cat "$dir/runs" 2>/dev/null || echo 0) + 1))
echo "$n" >"$dir/runs"
echo "> Task :app:testDebugUnitTest (run $n)"
if [[ -f $dir/out.$n ]]; then cat "$dir/out.$n" >&2; fi
exit "$(cat "$dir/status.$n" 2>/dev/null || echo 0)"
EOF
chmod +x "$work/fake-gradle"

# PR run 46001179531, attempt 1.
maven_429='FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task '"':app:testDebugUnitTest'"' (registered by plugin '"'com.android.internal.application'"').
> Could not resolve all files for configuration '"':app:debugUnitTestRuntimeClasspath'"'.
   > Could not download nativeruntime-dist-compat-1.0.20.jar (org.robolectric:nativeruntime-dist-compat:1.0.20)
      > Could not get resource '"'https://repo.maven.apache.org/maven2/org/robolectric/nativeruntime-dist-compat/1.0.20/nativeruntime-dist-compat-1.0.20.jar'"'.
         > Could not GET '"'https://repo.maven.apache.org/maven2/org/robolectric/nativeruntime-dist-compat/1.0.20/nativeruntime-dist-compat-1.0.20.jar'"'. Received status code 429 from server: Too Many Requests

* Try:
> Run with --stacktrace option to get the stack trace.

BUILD FAILED in 1m 59s'

# PR run 46041937206, attempt 1.
compose_plugin='FAILURE: Build failed with an exception.

* Where:
Build file '"'/home/runner/work/velo-android/velo-android/build.gradle.kts'"' line: 4

* What went wrong:
Plugin [id: '"'org.jetbrains.kotlin.plugin.compose'"', version: '"'2.4.21'"', apply: false] was not found in any of the following sources:

- Gradle Core Plugins (plugin is not in '"'org.gradle'"' namespace)
- Included Builds (No included builds contain this plugin)
- Plugin Repositories (could not resolve plugin artifact '"'org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.4.21'"')
  Searched in the following repositories:
    Gradle Central Plugin Repository

* Try:
> Run with --stacktrace option to get the stack trace.

BUILD FAILED in 12s'

read_timeout='FAILURE: Build failed with an exception.

* What went wrong:
Could not resolve all files for configuration '"':app:debugCompileClasspath'"'.
> Could not download kotlin-stdlib-2.4.21.jar (org.jetbrains.kotlin:kotlin-stdlib:2.4.21)
   > Could not get resource '"'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.4.21/kotlin-stdlib-2.4.21.jar'"'.
      > Could not GET '"'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.4.21/kotlin-stdlib-2.4.21.jar'"'.
         > Read timed out

* Try:
> Run with --stacktrace option to get the stack trace.

BUILD FAILED in 3m 1s'

failing_tests='FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task '"':app:testDebugUnitTest'"'.
> There were failing tests. See the report at: file:///home/runner/work/velo-android/velo-android/app/build/reports/tests/testDebugUnitTest/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 4m 2s'

compile_error='e: file:///home/runner/work/velo-android/velo-android/app/src/main/java/Foo.kt:3:5 Unresolved reference '"'bar'"'.

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task '"':app:compileDebugKotlin'"'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details

* Try:
> Run with --stacktrace option to get the stack trace.

BUILD FAILED in 2m 10s'

# A missing artifact is not a network failure, although its report names repository URLs.
not_found='FAILURE: Build failed with an exception.

* What went wrong:
Could not resolve all files for configuration '"':app:debugCompileClasspath'"'.
> Could not find org.example:nothing:1.0.
  Searched in the following locations:
    - https://repo.maven.apache.org/maven2/org/example/nothing/1.0/nothing-1.0.pom

* Try:
> Run with --stacktrace option to get the stack trace.

BUILD FAILED in 20s'

# Two failures (as with --continue): one is a network failure, the other a failing test.
mixed="FAILURE: Build completed with 2 failures.

1: Task failed with an exception.
-----------
${maven_429%%BUILD FAILED*}
==============================================================================

2: Task failed with an exception.
-----------
${failing_tests#FAILURE: Build failed with an exception.$'\n'}"

# expect <expected status> <expected runs> <out.1> [<out.2> ...]: each given report fails with
# status 1; the run after the last report passes.
expect() {
  local want_status=$1 want_runs=$2 n=1 got_status=0 got_runs
  shift 2
  rm -f "$work"/out.* "$work"/status.* "$work/runs"
  for out in "$@"; do
    printf '%s\n' "$out" >"$work/out.$n"
    echo 1 >"$work/status.$n"
    n=$((n + 1))
  done
  bash "$script" "$work/fake-gradle" >"$work/stdout" 2>"$work/stderr" || got_status=$?
  got_runs=$(cat "$work/runs")
  if [[ $got_status != "$want_status" || $got_runs != "$want_runs" ]]; then
    echo "Case at line ${BASH_LINENO[0]}: status $got_status after $got_runs runs, expected $want_status after $want_runs" >&2
    cat "$work/stdout" "$work/stderr" >&2
    exit 1
  fi
}

expect 0 1                                         # passes at once
expect 0 2 "$maven_429"                            # Maven Central 429, then passes
expect 0 2 "$compose_plugin"                       # plugin resolution, then passes
expect 0 2 "$read_timeout"                         # read timeout, then passes
expect 0 3 "$maven_429" "$compose_plugin"          # two network failures, then passes
expect 1 3 "$maven_429" "$maven_429" "$maven_429"  # bounded: three runs, then fails
expect 1 1 "$failing_tests"                        # failing tests: no retry
expect 1 1 "$compile_error"                        # compile error: no retry
expect 1 1 "$not_found"                            # missing artifact: no retry
expect 1 1 "$mixed"                                # a network failure beside a failing test
expect 1 1 ""                                      # no failure report (Gradle crashed)
expect 1 2 "$maven_429" "$failing_tests"           # retried, then a real failure ends it

# The exit status of Gradle comes back unchanged.
rm -f "$work"/out.* "$work/runs"
echo 7 >"$work/status.1"
status=0
bash "$script" "$work/fake-gradle" >/dev/null 2>&1 || status=$?
[[ $status == 7 ]] || { echo "Exit status $status, expected 7" >&2; exit 1; }

# stdout and stderr stay apart, and the report still reaches stderr.
expect 0 2 "$maven_429"
grep -q 'run 1' "$work/stdout"
! grep -q 'What went wrong' "$work/stdout"
grep -q 'Received status code 429' "$work/stderr"
grep -q '::warning::A repository could not be reached (attempt 1 of 3)' "$work/stdout"

GRADLE_RETRY_ATTEMPTS=1 expect 1 1 "$maven_429"   # one attempt means no retry

if bash "$script" >/dev/null 2>&1; then
  echo "Accepted no command" >&2; exit 1
fi
echo "gradle-retry.sh: all cases pass"
