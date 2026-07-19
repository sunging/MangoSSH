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

The upstream Android build script fetches its source dependencies at their
declared tags (zlib, protobuf, ncurses, GMP, and nettle). Their source and
license information remain available through the reproducible build directory
and upstream repositories. Do not replace the packaged binary with an
unverifiable build or remove the source submodule, license text, or this notice.

## Distribution commitment

The Mosh-enabled MangoSSH distribution is offered under GPL-3.0-or-later.
When distributing an APK, also make the exact corresponding source available,
including this repository, the initialized submodule, the build scripts, and
any local modifications to the native bridge or Mosh build inputs.
