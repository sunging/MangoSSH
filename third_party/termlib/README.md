# ConnectBot termlib 0.3.14 (MangoSSH patch)

This directory contains the Kotlin sources from ConnectBot termlib 0.3.14,
upstream commit `56934dd1369dc5d3e1f842b384df989c3c642a12`. The upstream project is
<https://github.com/connectbot/termlib> and is licensed under Apache-2.0.
Upstream's own unit, screenshot and benchmark tests are not vendored; the
tests here cover MangoSSH's modifications.

MangoSSH carries the following source modifications, kept additive to the
upstream defaults so a future re-sync is a straightforward re-apply. Each
modified file carries a `Modified by MangoSSH` header line.

- `TerminalEmulator.kt`: `TerminalEmulatorFactory.create` and
  `TerminalEmulatorImpl` accept an optional `maxScrollbackLines` parameter
  (default `1000`, matching the upstream hardcoded limit) so MangoSSH's settings
  UI can offer a larger or smaller off-screen line budget per session.
- `Terminal.kt`: the `Terminal` and `TerminalWithAccessibility` composables
  accept optional `minZoomScale`/`maxZoomScale` parameters (defaults `0.5f`/
  `3f`, matching the upstream pinch-to-zoom bounds). A finished pinch folds its
  scale into the font size (rounded to whole sp, clamped to min/max font size)
  instead of snapping back, reports it through `onFontSizeCommit`, and
  `fontSizeOverride` restores a committed size when the screen is shown again.
- `TerminalSnapshot.kt` / `TerminalEmulator.kt` / `Terminal.kt` plus new
  `MouseReport.kt`: while the remote program tracks the mouse
  (`VTERM_PROP_MOUSE`), a vertical swipe is forwarded as X10 wheel reports at
  the touch position (tmux `mouse on`, vim `mouse=a`). On the alternate screen
  without mouse tracking a swipe does nothing, and entering the alternate
  screen returns to the bottom of local history. `TerminalSnapshot` gains
  `mouseTrackingActive`; `TerminalEmulatorImpl.sendMouseWheel` queues the bytes
  in the same FIFO as keyboard input. Inertia applies only to local history.
- `TerminalEmulator.kt` plus new `ConptyDetector.kt`: once the output carries
  Windows ConPTY's `ESC[?9001h` announcement, `popScrollbackLine` declines to
  refill grown rows from scrollback. ConPTY repaints its whole viewport after
  every window change with the viewport top fixed, so refilled lines would be
  overwritten and lost; other hosts keep the upstream refill behaviour.
- `TerminalEmulator.kt`: `setDisplayActive(false)` coalesces snapshot rebuilds
  to a two-second timer while no UI shows the emulator; `true` repaints from
  libvterm and returns to frame cadence. MangoSSH keeps emulators for
  background sessions, whose output keeps arriving.
- `TerminalEmulator.kt`: upstream's `close()` (`93a4e04`) is used, but input,
  key, paste, color, resize and image calls made after it are ignored and
  content queries return nothing, instead of throwing. A transport or UI that
  races the close of an ended session therefore cannot crash the app.
- `KeyboardHandler.kt` / `TerminalDispatcher.kt` / `Terminal.kt`:
  `ctrlEnterSendsLineFeed` makes a lone Ctrl+Enter (hardware, IME or sticky
  modifier) send a raw line feed whatever keyboard protocol the program
  enabled; Claude Code and Codex insert a newline for it.
- `KeyboardHandler.kt`: the key-up of every consumed key-down is consumed too,
  so Android's fallback keys never turn a terminal Esc into Back (or the
  number pad into navigation). The key interceptor sees key-downs only.
- `Terminal.kt`: with a hardware keyboard and the soft keyboard hidden, the
  terminal claims focus itself, with `onPreviewKeyEvent` placed above its
  focus target, so key events reach the terminal. The IME view is rebuilt when
  the emulator changes and its focus requester precedes `focusable()`.
- `Terminal.kt`: `selectionMenuExtras` appends host items below a divider in
  the selection overflow menu, with a `dismiss` callback; the
  `modifierManager` argument is applied to the keyboard handler on every
  composition.
- `Terminal.kt` / `TerminalScreenState.kt`: the cursor blink loop runs only
  while the lifecycle is RESUMED and snapshot collection only while STARTED.
- `TerminalScreenState.kt`: the auto-detected URL grid is reused while every
  visible row is the same line instance, so cursor-only updates do not rerun
  URL detection over the whole screen.
- `Terminal.kt` / `AccessibilityOverlay.kt`: copying uses the suspending
  `Clipboard` API instead of the deprecated `ClipboardManager`.
- Resources: only the default English strings and MangoSSH's Simplified
  Chinese translation are kept.

Upstream now logs through SLF4J. MangoSSH ships the no-output SLF4J provider,
so none of these messages, which can include terminal state, are emitted.

JNI and libvterm sources are imported from that same commit under `src/main/cpp`
and built by AGP/CMake with NDK r27d. The Maven AAR is no longer a native input.
`native-import.json` records the mapping and ordered patches:
`native-patches/android-build.patch` adds the r27 16 KiB linker options and
path normalization; `native-patches/keyboard-protocols.patch` adds the legacy,
xterm modifyOtherKeys and kitty "disambiguate" key encodings and their libvterm
tests. `native-patches/screen-resize-size-arithmetic.patch` keeps screen resize
allocation, row offsets and move lengths in `size_t` arithmetic. Verify with
`python3 tools/native/termlib.py /path/to/pinned-upstream`; `--update` is an explicit
source maintenance operation that replaces the imported native tree.

libvterm carries its own MIT license under `src/main/cpp/libvterm/LICENSE`, also
packaged at `assets/licenses/MIT-libvterm.txt`.
