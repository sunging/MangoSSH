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
