#!/usr/bin/env bash
# CI-only disposable emulator and loopback fixtures; never use connected installers on a developer device.
set -euo pipefail
[[ "${CI:-}" == "true" ]] || { echo "Use audited explicit instrumentation on developer devices" >&2; exit 1; }
python="${RUNNER_TEMP:?}/ssh-fixture-venv/bin/python"
pids=()
cleanup() {
  for port in 22349 22350 22351 22352; do adb reverse --remove "tcp:$port" >/dev/null 2>&1 || true; done
  for pid in "${pids[@]}"; do sudo kill "$pid" >/dev/null 2>&1 || true; done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT
for mode in normal fallback metadata cancel; do
  options=()
  case "$mode" in
    normal) port=22349 ;;
    fallback) port=22350; options=(--no-posix-rename) ;;
    metadata) port=22351; options=(--reject-metadata) ;;
    cancel) port=22352; options=(--stall-forward-cancel) ;;
  esac
  # Root is confined to the disposable runner fixture's TemporaryDirectory. It enables real chown assertions.
  sudo "$python" tools/ssh-test-fixture.py --port "$port" "${options[@]}" >"$RUNNER_TEMP/fixture-$mode.log" 2>&1 &
  pids+=("$!")
  "$python" -c 'import socket,time,sys
for attempt in range(100):
 try:
  socket.create_connection(("127.0.0.1", int(sys.argv[1])), .2).close(); break
 except OSError: time.sleep(.1)
else: raise SystemExit("Fixture failed to start")' "$port"
  adb reverse "tcp:$port" "tcp:$port"
done
./gradlew --no-daemon :app:connectedGithubDebugAndroidTest :app:connectedFdroidDebugAndroidTest :third_party:termlib:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.fixturePort=22349 \
  -Pandroid.testInstrumentationRunnerArguments.fixtureFallbackPort=22350 \
  -Pandroid.testInstrumentationRunnerArguments.fixtureMetadataPort=22351 \
  -Pandroid.testInstrumentationRunnerArguments.fixtureCancelPort=22352 \
  -Pandroid.testInstrumentationRunnerArguments.requireFixtures=true \
  -Pandroid.testInstrumentationRunnerArguments.fixtureOwnership=true
