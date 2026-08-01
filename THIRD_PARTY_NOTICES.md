# Third-party notices

## Mosh for Android

MangoSSH packages the native `mosh-client` executable from the
[ConnectBot mosh4android](https://github.com/connectbot/mosh4android) project.
The source is present as the `third_party/mosh4android` Git submodule and is
pinned to commit `2de58be90449bfee4041c5d798f921f84d10dc0b` on its `android`
branch.

- License: GNU General Public License, version 3 or later (GPL-3.0-or-later).
- License text: `third_party/mosh4android/COPYING` and the copy included in
  every APK at `assets/licenses/GPL-3.0-or-later.txt`.
- Packaged artifacts: `app/src/main/jniLibs/*/libmosh_client.so` and
  `app/src/main/assets/mosh/terminfo.zip`.
- Build entry point: `tools/build-mosh-android-wsl.sh` using Android NDK
  `27.3.13750724`; `tools/fetch-android-ndk-wsl.sh` obtains that NDK into the
  ignored project-local `.tools` directory.

The upstream Android build script uses its declared zlib, protobuf, ncurses,
GMP, and nettle tags. F-Droid supplies those exact source trees through its
source-library mechanism, and MangoSSH applies
`tools/patches/mosh4android-offline-sources.patch` so its network-isolated build
never downloads compiler binaries or dependency source. Do not replace the
packaged binary with an unverifiable build or remove the source submodule,
license text, build patch, or this notice.

## Tailscale tsnet

MangoSSH builds an outbound-only gomobile bridge against
[`tailscale.com` v1.98.8](https://github.com/tailscale/tailscale/tree/v1.98.8).
Tailscale is distributed under the BSD 3-Clause license. The packages linked
into the bridge are committed as vendored Go source so F-Droid can rebuild the
AAR without network access.

- Exact module checksums are recorded in `native/tsnetbridge/go.sum`, and the
  matching source is under `native/tsnetbridge/vendor`.
- `tools/patches/tailscale-v1.98.8-tsnet-no-logtail.patch` disables creation
  and upload of tsnet's raw logtail buffer; the build fails if the patch no
  longer applies exactly.
- `tools/generate-tsnet-notices.py` derives notices from the Go packages
  actually linked into the bridge.
- Every generated AAR and APK contains the Tailscale BSD license at
  `assets/licenses/tailscale-BSD-3-Clause.txt` and consolidated dependency
  notices at `assets/licenses/tsnet-third-party-notices.txt`.
- The generated AAR is build output and is deliberately not committed.

## Terminal appearance assets

MangoSSH packages the following static terminal fonts and color palettes. The
appearance preference is device-local UI configuration; it is not part of the
encrypted vault or portable backup format.

### Fonts

- **Cascadia Mono PL Regular**, from
  [Cascadia Code v2407.24](https://github.com/microsoft/cascadia-code/releases/tag/v2407.24),
  is licensed under the SIL Open Font License 1.1 (OFL-1.1). It is packaged at
  `res/font/cascadia_mono_pl_regular.ttf` and has SHA-256
  `41FEBF792BE11A8E05408DA9C6E9509994C4E56837E932CFB200847296950262`.
  Its license text is included at `assets/licenses/OFL-1.1-Cascadia-Code.txt`.
- **JetBrains Mono NL Regular**, from
  [JetBrains Mono v2.304](https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304),
  is licensed under OFL-1.1. It is packaged at
  `res/font/jetbrains_mono_nl_regular.ttf` and has SHA-256
  `FB3B2575D7B0657359707993288F12A7360344D39387BB26050E276D61F6BD2A`.
  Its license text is included at
  `assets/licenses/OFL-1.1-JetBrains-Mono.txt`.
- **Fira Code Regular**, from
  [Fira Code v6.2](https://github.com/tonsky/FiraCode/releases/tag/6.2), is
  licensed under OFL-1.1. It is packaged at
  `res/font/fira_code_regular.ttf` and has SHA-256
  `5992AB9640E2DF491B2F609467B1DE60E8BC39B2C28DB184342A0592D98F6117`.
  Its license text is included at `assets/licenses/OFL-1.1-Fira-Code.txt`.

### Color palettes

- The Dracula palette derives from
  [dracula/windows-terminal](https://github.com/dracula/windows-terminal),
  Copyright (c) 2019 thismat, MIT license. The APK includes its license at
  `assets/licenses/MIT-Dracula-Windows-Terminal.txt`.
- The Nord palette derives from
  [nordtheme/terminal-app](https://github.com/nordtheme/terminal-app),
  Copyright (c) 2016-present Sven Greb, MIT license. The APK includes its
  license at `assets/licenses/MIT-Nord-Terminal-App.txt`.
- The Solarized palettes derive from
  [altercation/solarized](https://github.com/altercation/solarized), Copyright
  (c) 2011 Ethan Schoonover, MIT license. The APK includes its license at
  `assets/licenses/MIT-Solarized.txt`.

## Distribution commitment

The Mosh-enabled MangoSSH distribution is offered under GPL-3.0-or-later.
When distributing an APK, also make the exact corresponding source available,
including this repository, the initialized submodule, the build scripts, and
any local modifications to the native bridge or Mosh build inputs.
