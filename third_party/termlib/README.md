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

The four ABI `libjni_cb_term.so` files are extracted during the build from the
pinned Maven Central AAR (`org.connectbot:termlib:0.1.0`). They are not copied
into this repository. Update the source and native artifact version together.
