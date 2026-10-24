# Remote file management

The browser uses SFTP protocol operations without opening a remote shell. Files and
folders share an action menu; long-press starts a selection. Copy and cut remain
within the current connection. The task panel shows item outcomes after a partial
failure, and started tasks continue when the browser closes.

The single-row toolbar keeps Back and More visible. When space runs out, Tasks,
Upload, then Home directory move into More; they return as the
window widens. More also contains creation, selection and platform actions. The
current path appears in the breadcrumb navigation. To refresh, scroll to the top
and pull down; this also works for short listings, empty folders and failed
listings. A failed listing additionally offers Retry.

Folder uploads and downloads first preview the whole folder, including the
destination, file count, subfolder count and total size. The preview also lets
you enable SHA-256 verification for the task. Existing-file replacements still
require their own conflict decision. Cancelling the preview creates no destination
folders or files.

Folder downloads initially suggest Internal storage and request that the system
picker show device storage, including on older DocumentsUI versions that hide it
by default. Choose any writable folder the system allows, including folders outside
Download. The initial location and storage visibility are picker hints; Android's
directory access restrictions still apply.

Opening a completed folder download opens the system document browser at the actual
downloaded subfolder, including when the provider adjusts its name. Folder tasks
skip the app chooser because some OEM file managers reject directory document URIs
or open a storage root instead. The app checks the directory and asks the provider
for a concrete document reference off the UI thread, falling back to the original
directory document when canonicalization is unsupported. It uses
`ACTION_OPEN_DOCUMENT` with `DocumentsContract.EXTRA_INITIAL_URI`; locating that
directory still depends on the system picker and provider supporting the hint.
You can browse subfolders and select a file to open with an app, or press Back to
return to the task. Browsing does not change the download destination or persist a
new storage grant. File viewers receive temporary read access only. Missing
directories and revoked access show a local-access error; an unavailable system
browser shows a folder-specific error. Single-file tasks keep their app chooser.

Single-file Download and Save as dialogs initially suggest Internal storage > Download through
the system storage document provider. Some OEM Downloads providers create a document
and advertise write support but reject every write mode. If opening the target fails,
the task explains how to retry with Save as and choose a writable location. Access
always uses the picker-granted URI; the app does not infer filesystem paths or request
broad storage permission. Failed writes and readback verification remain commit failures.

## Platform and access policy

The browser's platform choice lasts for the live connection. Auto recognizes
Windows drive paths; a POSIX-looking path alone does not establish the server OS.
Choose POSIX before editing permissions. Windows mode attributes are not an NTFS
ACL editor. Windows names are validated separately, and case-only renames require
an intermediate name chosen by the user.

POSIX copies to new names preserve ordinary mode bits and modification times.
Ownership and special bits are not copied. Windows copies inherit destination
directory ACLs. Existing-file copy replacement retains the target access policy;
direct overwrite requires a separate confirmation and may leave partial content
after interruption. A POSIX atomic move replacement retains the source policy.

Permissions support separate file and folder modes. Recursive changes exclude
links and special files, apply to files first, and finish with directories from
deepest to shallowest. Special bits are preserved per item unless explicitly
changed. No ownership changes are offered.

## Failure and traversal behavior

Recursive copy, delete and permission changes scan at most 2,000 items and 32
levels. An incomplete scan does not authorize writes. Confirmations belong to a
single task and metadata snapshot. The engine revalidates before executing and
stops on the first failure. It does not automatically retry destructive actions
after disconnect. Cancellation is disabled during a final commit.

Links are copied as links, including their original relative targets, and are
never traversed. Unsupported link creation stops the task. Directories are not
merged on conflict. Moves use rename only: a cross-filesystem failure does not
trigger copy-and-delete. The protocol and drive roots cannot be mutation targets.

SFTP v3 does not offer a transaction for a directory tree or a universal
compare-and-swap primitive. Revalidation detects observable changes but cannot
exclude every remote race. Completed effects remain visible after cancellation
or failure; a lost reply can leave the final remote outcome uncertain. Refresh
and inspect both locations before retrying.

## Validation isolation

`RemoteOperationManagerTest` uses an in-memory filesystem. Compose tests use
synthetic state and callbacks. `RemoteOperationsInstrumentedTest` requires the
disposable loopback fixture and creates random test directories. Linux POSIX
fixtures and Windows-stat projection fixtures exercise different semantics;
projection coverage is not a claim of testing a real Windows SSH server.

Existing device installations must retain their data: verify installed and
candidate certificates and versions, replace with `adb install -r -t`, invoke
instrumentation explicitly, and compare persistent-data summaries afterward.

### Verified on 2026-10-13

- JDK 17: both distributions passed unit tests, lint, debug APK builds and
  AndroidTest compilation, along with the required terminal/SSH module checks.
  The JUnit audit covered 199 suites and 966 tests, with no failures, errors or
  skips. Each app lint report had zero errors and eight unrelated warnings.
- No physical ADB device was connected. The existing API 37, x86_64, 16 KiB
  emulator passed 16 targeted tests per distribution. Nine UI tests also passed
  at each of 320 dp phone width and 1280 dp landscape tablet width; those tests
  include double-font permission and deletion dialogs.
- Both certificate-checked replacements preserved all 72 non-ProfileInstaller
  persistent files. The only changed file was Android's ProfileInstaller marker.
  The installed final F-Droid APK matched the built APK byte-for-byte.
- SFTP integration used disposable Linux Paramiko and Windows-stat projection
  fixtures. A separate fixture check passed parent-escape rejection, link
  preservation, rename and empty-directory removal. Physical-device and real
  Windows/OpenSSH coverage were subsequently added in the follow-ups below.
- Emulator display overrides were restored; task-owned fixtures and ADB reverse
  mappings were removed.

### Physical-device follow-up

- The connected Meizu 16th (API 27 / Android 8.1) passed all 16 targeted tests
  on each distribution, for 32 physical-device tests without failures or skips.
  These exercise management menus, multiselection, the editing entry point,
  large-font dialogs, and live SFTP rename/delete/copy/permission/link operations.
- Installed, candidate and local Studio debug certificates matched, and no
  version downgrade was required. Both installs used `adb install -r -t`.
  All 12 original persistent files were identical before and after both runs,
  including the full original-to-final comparison.
- Wireless ADB reverse failed on this connection. A temporary LAN relay admitted
  only this phone's IP and connected it to the same disposable Linux and
  Windows-stat projection fixtures. Real Windows server coverage was subsequently
  added below.
- The phone retains the verified F-Droid debug APK, whose installed bytes match
  the build output. The temporary relay and fixture processes were stopped;
  no app data was cleared.

### Real Windows/OpenSSH follow-up (2026-10-14)

- The physical Meizu phone passed five real-Windows tests on each distribution,
  for ten targeted tests without failures or skips. The test APKs compiled and
  both distribution lint tasks passed. Two earlier upload/replacement probes
  also passed before the expanded test runs.
- The server's native Windows OpenSSH SFTP executable operated on NTFS inside
  one freshly created UUID test directory under the authorized temporary path.
  Local OpenSSH handled existing authentication and strict host-key verification;
  production credentials and keys were never copied into test fixtures or APKs.
  The device-only bridge forwarded real SFTP responses and attributes unchanged,
  with only the initial home lookup redirected to the owned test root.
- Coverage included exclusive creation, rename, deletion, resumed upload and
  SHA-256 verification, safe direct replacement, preservation of existing target
  permission attributes, UTF-8 BOM/CRLF editing, concurrent edit rejection, and
  refusal to apply POSIX permissions in Windows mode.
- All 12 phone persistent files were identical across both runs. The phone
  retains the verified F-Droid debug APK. Test-only bridge processes were stopped,
  the remote UUID directory was removed after exact path/reparse-point checks,
  and the temporary parent directory's entry baseline was restored.
- No production implementation change was required. The new editing tests remain
  in the AndroidTest source set.
