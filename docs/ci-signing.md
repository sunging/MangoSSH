# CI signing isolation

Validation on main/develop pushes and PRs builds both distributions, runs app,
termlib, and SSH JVM tests, runs app/termlib lint, compiles instrumentation, and
checks native packaging. PR emulator tests run in a disposable CI device.
Validation never consumes the release signing secrets.

Only a develop push or a protected main push can enter `sign-test-package`.
Develop does not require branch protection. The job depends on successful
validation; PRs and manual runs cannot enter it. It downloads the unsigned
artifacts from that same workflow run, with digest mismatch treated
as failure. A fresh runner invokes SDK zipalign/apksigner directly. It neither
checks out the repository nor invokes its Gradle or scripts. Permissions are
contents:read. The temporary keystore is deleted by an EXIT trap.

Repository administration must configure the `ci-signing` environment with
branch deployment policies restricted to the named main/develop branches and
the existing
ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, and
ANDROID_KEY_PASSWORD environment secrets. Any missing signing secret fails the
signing job and reports only the missing configuration name. This local change
does not configure GitHub environment policies. The main-only publishing
workflow is unchanged.

Successful signing uploads `MangoSSH-release-test-<full commit SHA>` for 14 days.
It contains both release APKs signed with the developer key:
`MangoSSH-ci-<12-character commit SHA>.apk` and
`MangoSSH-ci-<12-character commit SHA>-fdroid.apk`. Both APKs must pass signature
and 16 KiB alignment checks before upload. Concurrent pushes retain the existing
cancel-in-progress behavior, prioritizing delivery of the latest commit.

Do not install the signed test artifact over an Android Studio debug application.
Developer emulator validation uses the same debug certificate and data-preserving
replacement installation, followed by explicit `am instrument` on audited tests.
