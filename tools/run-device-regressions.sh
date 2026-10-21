#!/usr/bin/env bash
# CI-only disposable emulator and loopback fixtures; never use connected installers on a developer device.
set -euo pipefail
[[ "${CI:-}" == "true" ]] || { echo "Use audited explicit instrumentation on developer devices" >&2; exit 1; }
python="${RUNNER_TEMP:?}/ssh-fixture-venv/bin/python"
pids=()
ports=(22349 22350 22351 22352 22353 22354 22355 22356 22357 22358 22359)
diagnostics="$PWD/build/device-diagnostics"
mkdir -p "$diagnostics"
phase=setup
collect() {
  "$python" tools/device-regression-support.py collect --output "$diagnostics/$phase" --fixture-directory "$RUNNER_TEMP" || true
  adb pull /sdcard/mangossh-ci-diagnostics "$diagnostics/$phase/ui" >/dev/null 2>&1 || true
}
cleanup() {
  collect
  if [[ "$(adb shell getprop ro.kernel.qemu 2>/dev/null)" == 1 ]]; then
    adb shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
    adb shell dumpsys battery reset >/dev/null 2>&1 || true
    adb shell svc wifi enable >/dev/null 2>&1 || true
    adb shell svc data enable >/dev/null 2>&1 || true
  fi
  for port in "${ports[@]}"; do adb reverse --remove "tcp:$port" >/dev/null 2>&1 || true; done
  for pid in "${pids[@]}"; do sudo kill "$pid" >/dev/null 2>&1 || true; done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT
"$python" tools/test-device-regression-support.py
# Finish compilation before installing reverse listeners and probing the device.
./gradlew --no-daemon :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest \
  :app:assembleFdroidDebug :app:assembleFdroidDebugAndroidTest \
  :third_party:termlib:assembleDebug :third_party:termlib:assembleDebugAndroidTest
"$python" tools/device-regression-support.py prepare | tee "$diagnostics/prepare.jsonl"
wait_port() {
  "$python" -c 'import socket,time,sys
for attempt in range(300):
 try:
  socket.create_connection(("127.0.0.1", int(sys.argv[1])), .2).close(); break
 except OSError: time.sleep(.1)
else: raise SystemExit("Fixture failed to start")' "$1"
}
for mode in normal fallback metadata cancel legacy stalled-write windows-stat; do
  options=()
  case "$mode" in
    normal) port=22349; options=(--native-tools --echo-port 22357) ;;
    fallback) port=22350; options=(--no-posix-rename) ;;
    metadata) port=22351; options=(--reject-metadata) ;;
    cancel) port=22352; options=(--stall-forward-cancel) ;;
    legacy) port=22353; options=(--legacy-algorithms) ;;
    stalled-write) port=22355; options=(--stall-sftp-writes) ;;
    windows-stat) port=22356; options=(--windows-stat-projection) ;;
  esac
  # Root is confined to the disposable runner fixture's TemporaryDirectory. It enables real chown assertions.
  sudo "$python" tools/ssh-test-fixture.py --port "$port" "${options[@]}" >"$RUNNER_TEMP/fixture-$mode.log" 2>&1 &
  pids+=("$!")
  wait_port "$port"
  adb reverse "tcp:$port" "tcp:$port"
done
for mode in linux stall; do
  if [[ "$mode" == linux ]]; then port=22358; else port=22359; fi
  "$python" tools/server-resource-fixture.py --port "$port" --mode "$mode" >"$RUNNER_TEMP/fixture-resource-$mode.log" 2>&1 &
  pids+=("$!")
  wait_port "$port"
done
arguments=(
  -Pandroid.testInstrumentationRunnerArguments.fixturePort=22349
  -Pandroid.testInstrumentationRunnerArguments.fixtureFallbackPort=22350
  -Pandroid.testInstrumentationRunnerArguments.fixtureMetadataPort=22351
  -Pandroid.testInstrumentationRunnerArguments.fixtureCancelPort=22352
  -Pandroid.testInstrumentationRunnerArguments.fixtureLegacyPort=22353
  -Pandroid.testInstrumentationRunnerArguments.fixtureStalledWritePort=22355
  -Pandroid.testInstrumentationRunnerArguments.fixtureWindowsPort=22356
  -Pandroid.testInstrumentationRunnerArguments.ciDiagnostics=true
  -Pandroid.testInstrumentationRunnerArguments.requireFixtures=true
  -Pandroid.testInstrumentationRunnerArguments.fixtureOwnership=true
  -Pandroid.testInstrumentationRunnerArguments.fixtureTmux=true
  -Pandroid.testInstrumentationRunnerArguments.fixtureMosh=true
  -Pandroid.testInstrumentationRunnerArguments.fixtureEchoPort=22357
  -Pandroid.testInstrumentationRunnerArguments.fixtureResourcesPort=22358
  -Pandroid.testInstrumentationRunnerArguments.fixtureResourceStallPort=22359
  -Pandroid.testInstrumentationRunnerArguments.fixtureResourcePlatform=LINUX
  -Pandroid.testInstrumentationRunnerArguments.deviceNetworkFixture=true
  -Pandroid.testInstrumentationRunnerArguments.deviceIdleFixture=true
)
gradle_status=0
# Separate invocations ensure preflight runs immediately before each variant.
for phase in github fdroid termlib; do
  case "$phase" in
    github) task=:app:connectedGithubDebugAndroidTest ;;
    fdroid) task=:app:connectedFdroidDebugAndroidTest ;;
    termlib) task=:third_party:termlib:connectedDebugAndroidTest ;;
  esac
  "$python" tools/device-regression-support.py prepare | tee "$diagnostics/$phase-prepare.jsonl"
  "$python" tools/device-regression-support.py probe | tee "$diagnostics/$phase-preflight.jsonl"
  adb shell rm -rf /sdcard/mangossh-ci-diagnostics
  adb logcat -c
  if ./gradlew --no-daemon "$task" "${arguments[@]}"; then
    collect
  else
    gradle_status=1
    collect
  fi
done
report_status=0
"$python" tools/check-regression-results.py \
  app/build/outputs/androidTest-results/connected \
  third_party/termlib/build/outputs/androidTest-results/connected || report_status=$?
if (( gradle_status != 0 )); then
  exit "$gradle_status"
fi
exit "$report_status"
