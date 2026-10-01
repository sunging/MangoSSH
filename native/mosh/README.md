# Mosh recipes

These recipes are derived from ConnectBot mosh4android commit
`2de58be90449bfee4041c5d798f921f84d10dc0b`, with MangoSSH's offline-source and
no-GMP adaptations. The build rules are **the individual scripts in recipes/**;
their Git history records every change from upstream. The former patch files
for the upstream build script were removed because no build applied them.
The Android source compatibility patch is still taken from the pinned Mosh
source (`android/mosh-android.patch`) and applied strictly on its isolated copy.

`tools/native/build.py` verifies commits and recursive gitlinks, exports source,
provides toolchain/configuration identities, composes dependency install trees,
and invokes one recipe per component. Recipes never fetch sources. Each owns
its build/install directory, with dependencies available in a separate prefix.

Dependencies: host protoc <- protobuf; host tic <- ncurses; target protobuf <-
zlib; target ncurses/terminfo <- host tic; Mosh <- target zlib, protobuf,
ncurses, nettle, host protoc and terminfo. The existing `-w` and optimization
policy are retained pending a separate compiler-options review.

Mosh is an Android PIE executable, not a JNI shared object. Its unstripped
client stays in the component cache; publication copies and strips it to the
`.so` packaging name only after checking ELF identity, interpreter, dependencies
and 16 KiB load alignment. The static GPL license asset is maintained explicitly
and must match the source; compilation never edits root LICENSE or PTY output.
