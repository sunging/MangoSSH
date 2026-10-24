# Versioning and releases

## Version numbers

`version.txt` is the only application-version source. It contains stable SemVer
without a leading `v`, while Git tags and GitHub Releases use `v<version>`.
Gradle derives both Android values from that file:

```text
versionName = MAJOR.MINOR.PATCH
versionCode = MAJOR * 1,000,000 + MINOR * 1,000 + PATCH
```

For example, `0.0.1` maps to version code `1`, `0.1.0` to `1000`, and
`1.2.3` to `1002003`. Minor and patch components are limited to `0..999`, and
the resulting code must fit Android's `2,100,000,000` limit. This deterministic
mapping ensures that GitHub and network-isolated F-Droid builds of the same tag
carry identical version metadata.

## Build identity

About displays a separate build identity, for example
`339054b293f5-github` or `339054b293f5-fdroid`. Its first component is the
first twelve lowercase characters of the source repository's HEAD; its second
component is the Gradle `distribution` flavor. Debug and release builds use the
same format. Local uncommitted changes do not change this identity.

Gradle reads HEAD through a configuration-cache-aware provider, including in
shallow clones and Git worktrees. Each Android variant has its own generated
source directory, so building both distributions together cannot mix channels.
This display identity does not change `versionName`, `versionCode`, or update
eligibility.

For a source archive without Git metadata, pass the original full commit SHA:

```text
gradlew.bat :app:assembleFdroidRelease -PmangosshBuildRevision=FULL_COMMIT_SHA
```

Replace `FULL_COMMIT_SHA` with the actual source commit. The property takes
precedence over Git and must contain a full 40-character SHA-1 or 64-character
SHA-256 commit ID. Use the same SHA when reproducing a build from an archive.
Without the property and usable Git metadata, About displays `Unknown-github`
or `Unknown-fdroid`, localized in Simplified Chinese as appropriate. Build
identities contain no timestamps, CI run counters, or machine-specific paths.

## Release flow

Release Please owns version changes, `CHANGELOG.md`, `v*` tags, and GitHub
Releases. Commits merged into `main` must use Conventional Commit subjects;
`fix:` requests a patch release, `feat:` a minor release, and `feat!:` or a
`BREAKING CHANGE` footer a major release. Build, documentation, and CI-only
commits do not request an application release by themselves.

The release flow is:

1. Release Please creates or updates a release PR against `main`.
2. Review the proposed version and generated changelog. Add non-empty localized
   store notes named after the derived version code, for example:
   `fastlane/metadata/android/en-US/changelogs/1000.txt` and
   `fastlane/metadata/android/zh-CN/changelogs/1000.txt` for version `0.1.0`.
3. Approve the automated PR's Actions run when GitHub requests it, and merge
   only after Android CI passes.
4. The merge creates the version tag and a draft GitHub Release. The same
   workflow rebuilds native assets, tests, signs, verifies 16 KiB alignment,
   attaches the versioned APK/AAB and checksums, and then publishes the draft.

The repository must allow GitHub Actions to create pull requests and grant the
release workflow its declared `contents`, `issues`, and `pull-requests` write
permissions. The workflow uses only the built-in `GITHUB_TOKEN`; no PAT or
GitHub App credential is required. A manual run validates signing and uploads
an Actions artifact, but never creates a tag or publishes a GitHub Release.

Validate the current version, localized notes, and an optional tag locally:

```text
gradlew.bat :app:verifyReleaseVersion -PreleaseTag=v0.0.1
```

## Continuous integration

Android CI runs on `main` and `develop`, for both pushes and pull requests.
Validation builds both unsigned release packages without signing secrets. On
`develop` pushes and protected `main` pushes, a separate `ci-signing` environment
job signs those same-run artifacts using SDK tools, without checkout or Gradle
execution.
Configure that environment to admit only the named `main`/`develop` branches and
hold the four Android signing secrets. PRs and manual runs never enter the
signing job. The GitHub and F-Droid signed release APKs remain
`MangoSSH-ci-<commit>.apk` and `MangoSSH-ci-<commit>-fdroid.apk` in the
`MangoSSH-release-test-<full commit SHA>` Actions artifact, retained for 14 days;
missing signing secrets fail the job. See [CI isolation](ci-signing.md).

## In-app update contract

The `github` distribution's updater depends on the asset names the release
workflow produces: `MangoSSH-<tag>.apk` and `SHA256SUMS`. A release without
`SHA256SUMS` cannot be installed in-app; MangoSSH links to the release page
instead. Renaming either asset breaks in-app updates for existing installs.
