# ConnectBot termlib 0.1.0 (MangoSSH patch)

This directory contains the Kotlin sources from ConnectBot termlib 0.1.0,
upstream commit `e3f4bdc3b3b5563fee54b0eca4b50d0e611bfd07`. The upstream project is
<https://github.com/connectbot/termlib> and is licensed under Apache-2.0.

MangoSSH carries the following source modifications, kept additive to the
upstream defaults so a future re-sync is a straightforward re-apply:

- `TerminalEmulator.kt`: after a terminal resize it queues a full-screen
  damage region on termlib's normal serialized update path. This rebuilds the
  Compose snapshot from libvterm and prevents stale or duplicated visible rows
  after keyboard, rotation, or window size changes. The change follows the
  corrected scheduling approach described in upstream pull request #234
  without taking that branch's unrelated changes.
- `TerminalEmulator.kt`: `TerminalEmulatorFactory.create` and
  `TerminalEmulatorImpl` accept an optional `maxScrollbackLines` parameter
  (default `1000`, matching the prior hardcoded limit) so MangoSSH's settings
  UI can offer a larger or smaller off-screen line budget per session.
- `Terminal.kt`: the `Terminal` and `TerminalWithAccessibility` composables
  accept optional `minZoomScale`/`maxZoomScale` parameters (defaults `0.5f`/
  `3f`, matching the prior hardcoded pinch-to-zoom bounds) so MangoSSH's
  settings UI can offer a wider or narrower pinch-to-zoom magnification range.
- `TerminalSnapshot.kt` / `TerminalEmulator.kt` / `Terminal.kt` plus new
  `MouseReport.kt`: while the alternate screen or a mouse tracking mode
  (`VTERM_PROP_MOUSE`) is active there is no primary scrollback to pan, so a
  vertical swipe is forwarded to the remote program — X10 wheel reports when it
  tracks the mouse (tmux `mouse on`, vim `mouse=a`), otherwise arrow keys.
  `TerminalSnapshot` gains `isAltScreen` / `mouseTrackingActive` (both default
  `false`) and `TerminalEmulatorImpl` gains an internal `sendMouseWheel`.
  Inertia is suppressed for these forwarded swipes.

JNI and libvterm sources are imported from that same commit under `src/main/cpp`
and built by AGP/CMake with NDK r27d. The Maven AAR is no longer a native input.
`native-import.json` records the mapping and `native-patches/android-build.patch`
adds the r27 16 KiB linker options and path normalization. Verify the import with
`python3 tools/native/termlib.py /path/to/pinned-upstream`; `--update` is an explicit
source maintenance operation that replaces the imported native tree.

libvterm carries its own MIT license under `src/main/cpp/libvterm/LICENSE`, also
packaged at `assets/licenses/MIT-libvterm.txt`.
