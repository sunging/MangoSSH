# Encrypted backups and recovery

File imports, WebDAV downloads and historical restores all prepare an import
preview. Nothing in the current vault changes until that preview is confirmed.
The default is a merge; replacement is an explicit choice that shows the number
of local-only records it will remove.

## Merge rules

- Profiles, SSH keys, snippets and forwarding rules match by ID. Different IDs
  remain different records even if their labels or endpoints are identical.
- Identical records are skipped. Conflicts keep the local record unless the
  user selects the incoming record.
- Trusted hosts match by hostname, port and algorithm. Replacing an existing
  trust decision always requires its own checkbox, including in replacement
  mode. Bulk conflict selection excludes trust decisions.
- Merges retain existing profile order and local usage counters. New profiles
  append in backup order. Replacement restores backup order and counters.
- Duplicate IDs, unknown enum values, invalid ports and dangling non-null
  references reject the import. Validation runs again on the merged result.
- A preview is bound to a local vault revision. A concurrent edit or connection
  counter update requires a new preview and confirmation.

WebDAV settings and login credentials are excluded from ordinary exports and
all WebDAV uploads. Manual exports can explicitly include them. Restoring them
is a separate, default-off choice, even for legacy archives or full replacement.
Active sessions keep their existing configuration; changes affect new sessions.

## Portable format

New archives use container version 3. The 40-byte header retains the `MSSHX`
magic, version byte, 16-byte salt, 12-byte nonce and ciphertext length. AES-256-GCM
authenticates this complete header. Password derivation remains PBKDF2-HMAC-SHA256
with 310,000 iterations.

The v3 authenticated plaintext is a JSON object with `id` (UUID), `createdAt`
(epoch milliseconds), `appVersion` and `payload` (the versioned vault object).
The vault schema remains 5. Container versions 1 and 2 remain readable; v1 uses
the legacy zero-length AAD convention. Vault schemas 1 through 5 migrate to the
current schema. Missing legacy creation metadata is reported as unknown.

Older app versions cannot read v3 exports. Unknown container or payload versions
are reported separately from authentication failure. Password errors and failed
GCM authentication deliberately share one message.

Portable files are bounded to 16 MiB before parsing. The final local payload must
fit the existing 5 MiB vault limit. File operations report success only after
the output stream has successfully closed.

## Local secrets and recovery

Before applying an import, the repository saves the previous complete snapshot
as a recovery point, then performs the normal AtomicFile vault write. Failure to
save recovery aborts the import. Successful imports prune recovery to 10 points.
Recovery itself follows the same import flow and creates another recovery point.

Recovery points, saved passphrases and remote receipts occupy separate directories
and separate Keystore key domains under `noBackupFilesDir/backup-state`. Saved
passphrases are never serialized into a vault, portable archive or recovery point.
Manual-file passwords and WebDAV target passwords are separate. WebDAV targets
are scoped by endpoint, username and filename; changing a target cannot reuse
another target's receipt or password.

Remembering a passphrase is optional and initially off. A new export passphrase
must be confirmed twice. Changing it affects future archives only; old remote
history still needs its original passphrase. Restoring history never silently
replaces the saved current password. Local recovery needs the device key, not an
old backup passphrase. Forgetting a saved password leaves backups untouched.

Password inputs are not saveable UI state. Mutable password buffers are cleared
after use. Leaving the backup page or locking the application invalidates pending
confirmations and releases pending plaintext. An in-flight blocking operation
finishes releasing its buffers before another operation may start.

## WebDAV publication

The configured filename remains the latest backup. Before overwriting it, its
existing ciphertext is archived unchanged in `.<filename>.history/`, using an
immutable `mssh-<time>-<uuid>.mssh` filename. The newest 10 historical files are
kept in addition to the current file. Only recognized files in that exact
directory are eligible for cleanup.

An unknown or changed remote ciphertext requires the user to download and merge,
or explicitly choose archive-and-overwrite. A target-scoped receipt records the
SHA-256 digest of a successfully imported or published head. Historical restores
do not acknowledge the current head.

Publication checks the remote again, probes conditional-write support using a
disposable object, archives the previous head, then writes with `If-Match` using
the strong ETag from GET. Creation uses `If-None-Match: *`. A 412 response requires
another check; there is no unconditional fallback. A lost response is verified
with a GET before deciding the result. A successful write followed by failed
history cleanup or receipt persistence is reported as partial success.

The server must support HTTPS, MKCOL, PROPFIND with Depth 1, PUT, DELETE and
conditional writes. Redirects and automatic request retries are disabled. XML
directory responses have byte and entry limits, reject DTD/entity declarations,
and cannot resolve entries outside the history directory. Failed cleanup is
retried after a later successful publication.

This is manual backup publication and restoration, not background or bidirectional
sync. Deletions are not propagated during a merge.
