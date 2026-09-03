# fdroiddata submission bundle

This directory stages the files that must be copied to the official
`fdroid/fdroiddata` repository after MangoSSH v0.4.1 is tagged and published.
It is not an alternative app repository.

Before submitting:

1. Replace `RELEASE_COMMIT_SHA` in the metadata with the full commit referenced
   by the signed `v0.4.1` tag.
2. Confirm `version.txt` is `0.4.1` and `version-code.txt` is `4001` at that
   commit.
3. Confirm the GitHub release contains `MangoSSH-v0.4.1-fdroid.apk`, signed by
   certificate SHA-256
   `6ad86ccb0026c60606b016a643fb5f90c090035591c2c734d0fd66004b9373be`.
4. Copy `metadata/website.sung.mangossh.yml` and the seven files under `srclibs/`
   to the matching fdroiddata directories.
5. Run `fdroid rewritemeta`, `fdroid lint website.sung.mangossh`,
   `fdroid checkupdates website.sung.mangossh`, and
   `fdroid build --server website.sung.mangossh:4001`.

The app recipe reuses fdroiddata's existing `go.yml` and `zlib.yml` srclibs.
All requested revisions are full commit hashes matching
`tools/fdroid-sources.lock`.
