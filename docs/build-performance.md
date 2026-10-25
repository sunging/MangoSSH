# Build performance

## What is being measured

Keep these different workloads separate:

| Workload | State before the build | Expected work |
| --- | --- | --- |
| Cold native build | Prepared SDK/JDK/sources, empty native component state | Mosh dependencies and all requested Go/JNI ABIs compile |
| Full application rebuild | Application/module outputs cleaned, dependency and native caches retained | Compile/package everything, or restore eligible Gradle task outputs |
| No-change incremental build | Previous outputs, same task list and inputs | Configuration-cache reuse and mostly `UP-TO-DATE` tasks |
| Source edit | Previous outputs, one Kotlin/resource change | Only affected compilation and packaging |
| Revision-only build | Same code, different Git SHA | Build identity resources and packaging; Kotlin can remain `UP-TO-DATE` |

Dependency/toolchain downloads, CI runner queue time, emulator boot and device
test execution are not compilation. Report them separately. A clean rebuild
with a populated output cache is not a cold build. `--rerun-tasks` bypasses
up-to-date checks and cannot measure normal incremental behavior.

## Profiling locally

Use the repository's JDK 17 and prepared native inputs. Run the same commands,
ABI set, worker count and daemon policy before and after a change. Avoid other
builds, emulators and concurrent source edits during timing. For example:

```sh
./gradlew --no-daemon --max-workers=4 --profile \
  -Pkotlin.build.report.output=file \
  :app:assembleGithubDebug :app:assembleFdroidDebug
# Repeat unchanged to measure no-change incrementality.
```

Gradle writes task timings to `build/reports/profile/`; Kotlin's compilation
rounds and rebuild reasons are in `build/reports/kotlin-build/`. The optional
compiler-arguments metadata field is disabled; Kotlin's own compilation log
can still contain classpaths and source filenames. Use `--info` only
when investigating a specific unexpected cache miss. The HTML task durations
overlap when tasks run concurrently: their sum is not wall-clock build time.

To compare rebuilding application outputs with and without task-output reuse,
run these sequences separately, measuring the assembly command and excluding
the cleanup command:

```sh
# Baseline without Gradle task-output reuse.
./gradlew --no-daemon :app:clean :third_party:termlib:clean \
  :third_party:cbssh:clean :third_party:cbssh:protocol:clean
./gradlew --no-daemon --no-build-cache --max-workers=4 --profile \
  :app:assembleGithubDebug :app:assembleFdroidDebug

# Repeat cleanup, then assemble with --build-cache to populate the task cache.
# Repeat that pair once more to measure reconstruction from the populated cache.
```

This keeps Linux `build/native` intact. Root `clean` also removes that default
native state; a custom external `mangosshNativeStateDir`, including the Windows
WSL default, survives it. For an actual cold native measurement, choose a new,
empty state directory under `build/` or outside the checkout, and disable
Gradle task-output caching. Do not delete a shared cache to simulate a cold run.

Measure a revision-only change with a full synthetic SHA:

```sh
./gradlew --no-daemon --max-workers=4 --profile \
  -PmangosshBuildRevision=0123456789012345678901234567890123456789 \
  :app:assembleGithubDebug :app:assembleFdroidDebug
```

After the new resource generator has been built once, both app Kotlin compile
tasks should be `UP-TO-DATE` for this probe. Run again without the override to
restore the real identity in local APKs. For source-edit measurements, change
one method body, build, restore that file, and build again; a timestamp-only
touch is not a source edit. Verify the Kotlin report actually records incremental
compilation and that Mosh/tsnet stay `UP-TO-DATE`.

Local device iteration may use `-PmangosshAbis=arm64-v8a` or `x86_64` and one
distribution. Warm that ABI selection first: changing the ABI set deliberately
changes the native identity, including the tsnet AAR. Release builds still
require all four ABIs. Multi-worktree developers can explicitly select the same
content-addressed `mangosshNativeStateDir` to reuse verified native components;
each checkout must retain its own published outputs.

## CI cache and measurement policy

`gradle/actions/setup-gradle` saves a new Gradle User Home snapshot per commit
and restores compatible earlier snapshots. This includes dependency transforms
and the local task-output cache. It replaces `setup-java`'s build-file-keyed
Gradle cache, whose unchanged immutable key cannot collect new task outputs
after source-only commits. Do not enable both cache mechanisms in one job.

Only `main` and `develop` push verification jobs write the Gradle cache. PR,
device, release and CodeQL jobs restore it read-only. Configuration-cache files
are not exported from the project directory. Native component archives retain
their separate source/toolchain/hash validation and pruning policy.

Release publication uses `--no-build-cache` and `component-cache: off` to build
shipped binaries from source. CodeQL retains `--no-build-cache`,
`--no-configuration-cache` and in-process Kotlin compilation. Both distributions,
four release ABIs, JVM tests, lint, AndroidTest compilation, APK/Ed25519 checks
and device regression suites remain in the workflow.
The local F-Droid offline verification wrapper also disables Gradle task-output
caching; the external fdroiddata recipes are unchanged.

CI and release jobs upload Gradle/Kotlin performance reports for 14 days, even
after a failure. Compare the same job, task list and cache state across runs;
read the configuration, compile, lint, shrinker and packaging timings before
changing heap size or worker counts.

## Observed CI baseline (2026-10-25)

The successful [current-commit push run](https://github.com/sunging/MangoSSH/actions/runs/40149002547)
for `57fd158f0fbf9a4b15ca64ab6bfd0f3fa1dc421f` reported:

| Step | Duration |
| --- | ---: |
| Prepare pinned native inputs | 1m 45s |
| Build-logic tests plus full verification/packaging | 16m 56s |
| Main Gradle invocation within that step | 16m 27s |
| Entire verification job | 19m 24s |

All Mosh and tsnet components were reused in that run. A previous successful
[push run](https://github.com/sunging/MangoSSH/actions/runs/40115349141) spent
42m 45s in the corresponding build step; different inputs/cache conditions mean
this is variability, not evidence of a speedup.

The successful [PR run](https://github.com/sunging/MangoSSH/actions/runs/40158971609)
spent 6m 26s compiling its device-test APKs, followed by Gradle invocations of
16m 25s (GitHub suite), 15m 39s (F-Droid suite), and 50s (termlib suite).
The device job took 43m 37s. Much of that is actual device regression work;
compiler caching cannot eliminate it. Runner scheduling also delayed the
verification job, so the workflow's elapsed time is not its build duration.

These are pre-change observations. The optimized workflows had not run on
GitHub when these measurements were recorded; no CI speedup percentage is claimed.

## Local measurements (2026-10-25)

Windows with JDK 17, Gradle 9.8.0, four Gradle workers, `--no-daemon`, offline
Maven resolution, both Debug distributions and all four ABIs. The toolchain,
dependency and verified WSL native component caches were prepared first.
An isolated Gradle task cache started empty; the same cache was reused after
cleaning all four application/library module output directories. Cleanup time
is excluded. These are single-run observations except for the three no-change
runs, not guarantees for other machines or CI runners.

| Scenario | Wall time | Evidence |
| --- | ---: | --- |
| Full rebuild, initially empty task cache | 253.31s | App and library Kotlin compiled; 10 task outputs reused within the build |
| Same full rebuild, populated task cache | 112.03s | 59 tasks `FROM-CACHE`, including both app Kotlin compilations |
| No changes, three repeats | 11.29 / 11.53 / 11.70s | Configuration cache reused; Kotlin and Mosh/tsnet `UP-TO-DATE` |
| Revision-only change | 49.83s | Both Kotlin compile tasks `UP-TO-DATE`; both APKs contain the new 12-character SHA |
| One equivalent ViewModel method-body edit | 98.95s | One source file compiled per distribution; both reports confirm incremental compilation |
| Restore that source file | 33.83s | Both app Kotlin outputs restored `FROM-CACHE` |

The populated-cache reconstruction was about 56% faster in this comparison.
The empty-cache run can still reuse equivalent outputs between the two variants
within the same invocation; it does not imply every task must execute twice.

The first full rebuild spent 106.39s in the tsnet adapter and 69.97s in Mosh
source/cache validation and publication. Its app Kotlin tasks took 94.65s and
84.27s. These durations overlap. The native adapters still hash sources on
Windows drives when module cleanup removes their published outputs. Avoiding
unnecessary `clean` and unrelated-input invalidations is therefore valuable.
The ViewModel edit analyzed 1,843 lines per distribution, demonstrating that
incremental compilation still has a real cost for a large source file.

The SHA probe also inspected packaged resources with SDK `aapt2`, then rebuilt
without the override. The temporary source edit was restored byte-for-byte.
Four Mosh binaries, terminfo and the tsnet AAR were byte-identical to the main
checkout's existing native artifacts after the clean native rebuild. The native
source/cache, Mosh/Go/tsnet checks, 19 build-logic tests and workflow actionlint
checks passed.

The complete local verification graph then passed in 663.90s with two workers,
including both Release APKs, Debug APKs, AndroidTest compilation, JVM tests and
lint. Its workload and cache state differ from the assembly benchmarks above.
The 1,149 JVM tests (including build-logic) had zero failures, errors or skips.
Both app lint reports had zero errors and 12 warnings; termlib had zero errors
and six warnings. Device suites were compiled, not executed on a device.

All four final APKs passed ZIP and ELF 16 KiB alignment, four-ABI coverage and
native provenance checks (20 native files per APK). Both F-Droid APKs passed the
updater-exclusion checks, both Release APKs retained the Ed25519 JCA classes and
constructors, and all four packages contained the real revision and correct
distribution. The SSH migration scan and F-Droid wrapper shell syntax check
also passed.

Heavy tasks in that verification run included GitHub Release R8 (163.75s),
GitHub Release Kotlin (76.03s), GitHub Debug lint analysis (74.95s), F-Droid
Release Kotlin (61.23s), and F-Droid Release R8 (52.70s). They overlap and depend
on execution order, so these figures are not additive or a fair performance
comparison between distributions. Changed inputs still require these checks;
task caching helps when their inputs match an earlier build.

Local raw timings and logs are retained under `.tools/build-performance/`;
Gradle HTML and Kotlin reports remain in `build/reports/`. The `.tools` data is
ignored and is not part of the source change.

## References

- [Gradle task-output caching](https://docs.gradle.org/current/userguide/build_cache.html)
- [Gradle setup action cache behavior](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md)
- [Kotlin BTA and in-process incremental compilation](https://kotlinlang.org/docs/build-tools-api.html)
- [Android build profiling](https://developer.android.com/build/optimize-your-build)
