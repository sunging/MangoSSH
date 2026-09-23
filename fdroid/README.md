# F-Droid distribution

The F-Droid build recipe and srclib definitions are maintained in
[fdroiddata](https://gitlab.com/fdroid/fdroiddata), not duplicated in this
application repository. Follow the
[MangoSSH inclusion merge request](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/47819)
for the reviewed recipe, CI results, and inclusion status. After it is merged,
use `metadata/website.sung.mangossh.yml` in fdroiddata as the authoritative
recipe. The old submission bundle was removed to avoid stale copies.

This repository still owns the inputs used by that recipe:

- `app/src/fdroid/`: the distribution-specific source and manifest.
- `tools/*fdroid*`: source fetching, verification, native build, and APK audit
  scripts, including the pinned `tools/fdroid-sources.lock`.
- `fastlane/metadata/android/`: localized descriptions and release notes.

The ignored `.fdroid/` directory (with a leading dot) is a local workspace for
source downloads, fdroiddata checkouts, validation environments, and release
artifacts. It is separate from this documentation directory and is not a
second authoritative recipe. Check for uncommitted or unpushed work in any
nested checkout before cleaning it; removing cached sources also means they
must be fetched again for a local source build.
