# MangoSSH

MangoSSH is a Jetpack Compose Android SSH and Mosh client with encrypted local
profiles, reusable keys, encrypted WebDAV backups, port forwarding, SCP,
keyboard shortcuts, app lock, and Tailnet-aware SSH routing.

## License

This Mosh-enabled distribution is licensed under GPL-3.0-or-later. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the native Mosh source,
build provenance, and distribution obligations. Initialize submodules before
building:

```text
git submodule update --init --recursive
```

## Native Mosh build

On the system user's Ubuntu WSL environment, run:

```text
bash /mnt/d/code/android/mangossh/tools/fetch-android-ndk-wsl.sh
bash /mnt/d/code/android/mangossh/tools/build-pty-bridge-wsl.sh
bash /mnt/d/code/android/mangossh/tools/build-mosh-android-wsl.sh
bash /mnt/d/code/android/mangossh/tools/install-mosh-assets.sh
```

The final command validates and copies the four ABI archives into the Android
app. The scripts keep downloaded compilers and intermediate files in `.tools`,
which is not committed.

## 16 KiB page-size verification

After building the debug APK, validate both the ELF load segments and the APK
alignment. This is required for Android devices that use 16 KiB memory pages:

```text
bash /mnt/d/code/android/mangossh/tools/check-16kb-elf-wsl.sh \
  /mnt/d/code/android/mangossh/app/build/outputs/apk/debug/app-debug.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
```

The JNI PTY bridge explicitly uses the NDK r27 16 KiB linker options. The
native build and Mosh asset installation scripts reject a binary whose
`PT_LOAD` segments do not meet that requirement.
