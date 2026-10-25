#!/usr/bin/env bash
# CI-only disposable API 35 emulator. Unlike android-emulator-runner this never
# sends `input keyevent 82` after boot: on the full AOSP image Launcher3 is
# force-stopped and restarted right after boot_completed, and a key dispatched
# to it then raises an ANR that the strict preflight must reject.
set -euo pipefail
[[ "${CI:-}" == "true" ]] || { echo "Use a developer-managed emulator outside CI" >&2; exit 1; }
image="system-images;android-35;default;x86_64"
avd=test
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
avdmanager="$(dirname "$sdkmanager")/avdmanager"
echo y | "$sdkmanager" --install emulator platform-tools "$image" >/dev/null
echo no | "$avdmanager" create avd --force -n "$avd" --package "$image" --device pixel_6
# Same explicit budget as before; Gradle has already exited.
printf 'hw.cpu.ncore=4\nhw.ramSize=4096\nhw.heapSize=512\n' >>"$ANDROID_AVD_HOME/$avd.avd/config.ini"
nohup "$ANDROID_HOME/emulator/emulator" -port 5554 -avd "$avd" -no-snapshot -no-window \
  -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none \
  >"$RUNNER_TEMP/emulator.log" 2>&1 &
echo "$!" >"$RUNNER_TEMP/emulator.pid"
timeout 600 adb -s emulator-5554 wait-for-device
deadline=$((SECONDS + 600))
until [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
  if (( SECONDS >= deadline )) || ! kill -0 "$(cat "$RUNNER_TEMP/emulator.pid")" 2>/dev/null; then
    tail -n 50 "$RUNNER_TEMP/emulator.log" >&2
    echo "Emulator did not finish booting" >&2
    exit 1
  fi
  sleep 2
done
mkdir -p build/device-diagnostics
"${RUNNER_TEMP:?}/ssh-fixture-venv/bin/python" tools/device-regression-support.py settle \
  | tee build/device-diagnostics/settle.jsonl
