# CI signing isolation

Validation on main/develop pushes and PRs builds both distributions, runs app,
termlib, and SSH JVM tests, runs app/termlib lint, compiles instrumentation, and
checks native packaging. PR emulator tests run in a disposable CI device.
Validation never consumes the release signing secrets.

Only a protected main/develop push can enter `sign-test-package`. It downloads
the unsigned artifacts from that same workflow run, with digest mismatch treated
as failure. A fresh runner invokes SDK zipalign/apksigner directly. It neither
checks out the repository nor invokes its Gradle or scripts. Permissions are
contents:read. The temporary keystore is deleted by an EXIT trap.

Repository administration must configure the `ci-signing` environment with
branch deployment policies restricted to protected main/develop and the existing
ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, and
ANDROID_KEY_PASSWORD environment secrets. Missing secrets skip signed delivery;
unprotected branches cannot enter the job. This local change does not configure
GitHub environment policies. The main-only publishing workflow is unchanged.

Do not install the signed test artifact over an Android Studio debug application.
Developer emulator validation uses the same debug certificate and data-preserving
replacement installation, followed by explicit `am instrument` on audited tests.
