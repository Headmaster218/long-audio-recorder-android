# Explicit opt-in local cleanup after readback (dev10)

This is a separate, weaker **readback-at-time** policy. It does not use or change
DeletionGate, invent remote version tokens, or assert remote immutability,
server fsync, backup reliability or future persistence. The default is OFF.
The native confirmation names the exact saved destination revision and warns
that deleted audio may be unrecoverable if the remote copy later changes or
vanishes. Existing historical verified rows are not retroactively enrolled.

No real user recording was deleted, no external FTPS connection was opened, no
credentials were created or tested, and no APK was signed, installed or sent.
All destructive tests used newly generated tiny synthetic fixtures.

## Scope and limits

New successfully verified transfers may authorize cleanup for the exact opted-in
destination. Consent does not follow edits to another saved profile. Disable is
available while busy and sets an immediate in-process revocation flag; its durable
write follows on the single queue owner. Until that write completes it is a disable
request, not a power-loss durability guarantee. New cleanup checks revocation
before quarantine and irreversible purge entry.

Cleanup does **not** mutate files while recording is active. Full audio hashing,
quarantine, directory sync and purge run on the background transfer owner with
an exclusively owned idle spool. The existing capture/service/recovery-session
code is unchanged. A new recording start can wait for an already running idle
filesystem operation; OS rename/fsync/unlink calls have no hard time bound.

Transfers verified during recording wait. Once idle, a policy-gated, authenticated
full readback of the exact existing remote attempt refreshes the evidence before
cleanup. This path never uploads, creates another remote attempt or overwrites
anything. Charging/Wi-Fi/permission/TLS gates still apply; unsafe routes persist a
five-minute delay. Missing credentials wait for the exact profile to be supplied;
a failed/cancelled recheck retains the original and blocks cleanup rather than
blindly retrying. While waiting for capture or manual export to end, a persisted
five-minute scheduler wake prevents lifecycle ordering from stranding cleanup.
Android/OEM scheduling can delay it further.

Manual SAF selection/picker/copy admission and cleanup use the same process lock.
An active export therefore retains the source. Another queue record for the same
source that has not verified also blocks cleanup, including a blocked or
credential-waiting destination. A fresh recheck may proceed when all those records
are verified. Neither guard expands which destination was authorized.

This patch does not reclaim space during continuous recording. Cache-full still
stops capture and alerts; a default 512 MiB spool is still roughly 4.66 hours of
16 kHz mono PCM16 before overhead/reserves. The 500-row queue/history limit remains.
Detailed receipts are not retired or compressed here. These are explicit remaining
barriers to unattended all-day rolling recording, not accepted capabilities.

## Evidence and local identity

A transfer must finish the existing complete authenticated WAV, transport metadata
and marker readback, with matching byte counts and SHA-256 values. The exact
receipt is persisted before cleanup intent. The local source is fully hashed again
on the transfer worker. Strong identity stamps are captured **before** that hash
and checked afterward, closing the hash-to-binding race.

The bounded binding records the canonical source ID and exact directory/member
identities. File checks include file key, creation time, size, mtime and a strong
change token. Android uses public Os.lstat plus device/inode and nanosecond
st_ctim; this avoids relying on the optional Java NIO unix attribute view.
[StructStat.st_ctim is public since API 27](https://developer.android.com/reference/android/system/StructStat#st_ctim),
below this app's minimum API 29. Host fixtures use the host filesystem ctime.
Missing/unsupported identities fail closed and retain audio. Directory birthtime
is not assumed stable during partial purge: Android can derive it from mutable
mtime. Directory inode identity and every remaining file binding still must match.
These checks assume the app-private cooperating-writer namespace; they are not
protection against a privileged attacker or a malicious filesystem.

Only the exact committed ready directory can move. Active/partial files, unexpected
members, symlinks, changed sources and destination conflicts are rejected. Checks
also reject same-size content writes with restored mtime through the change token.
No full hash or network work runs in the audio producer/storage loop.

## Journal, quarantine and crash replay

SQLite schema 3 adds cleanup consent and per-row cleanup state/binding without
replacing existing profile, credential, queue, receipt, pause or retry fields.
Migration accepts old schema 1 or 2; cleanup remains OFF after migration.

1. Persist INTENT tied to the verified queue receipt and exact source binding.
2. Under idle spool ownership, validate and atomically rename ready to quarantine;
   sync the spool directory, then persist QUARANTINED.
3. Recheck consent/freshness and persist PURGING before irreversible deletion.
4. Revalidate all remaining bound members; unlink at most three files, syncing
   after each. Rename the original now-empty directory to .retired and sync root.
5. Persist REMOVED. The full transfer receipt remains available.

A crash before purge authorization restores the complete source and requires a new
fresh readback before any later cleanup. Revoking consent converts INTENT and
QUARANTINED to RESTORE_ONLY; re-enabling the same profile cannot revive them.
Deferred/rechecking/credential-waiting/source-waiting work becomes RETAINED on
revocation. Already committed partial PURGING may complete on restart, as disclosed
in consent, but only for the exact unchanged remaining objects. If purge has not
entered, cancellation/revocation rolls its intent back to quarantine for restoration.
Conflicts or changed members become BLOCKED and are preserved for inspection.

The original directory inode becomes an empty .retired ordinal reservation.
nextSequence recognizes ready, partial, quarantine and retired names. Removing
local WAV bytes can therefore never silently reuse a previously published source
ID on resume. The existing 500-row bound also bounds these markers in this slice.

## Verification

- 54 production-core assertions with new synthetic files: identity round-trip,
  malformed bindings, complete quarantine/restore/purge, repeated replay,
  sequence preservation, unexpected members, replacement inodes, symlinks,
  same-size/mtime-restored corruption, cancellation, hash-to-binding mutation,
  Android directory-birthtime fallback, and crashes after every purge sync boundary.
- 30 host SQLite assertions execute production migration/revocation SQL and model
  journal writes/reopens, default-off consent, retained receipt fields, re-enable
  behavior and multi-destination source retention. Android ContentValues and
  actual Android SQLite crash behavior are not executed.
- 16 narrow source-order contracts cover idle admission, live revocation, state
  boundaries, exact read-only recheck, Android adapter and SAF admission. These
  are textual checks, not runtime concurrency tests.
- Existing storage 389 + 11 adversarial cases, export 301, FTPS 868, policy 7,068
  + 14 adversarial cases, app helpers 58, capture evidence 129, capture anchors 91,
  interruption policy 1,310, recovery integration 656 and recovery session 325 pass.
  Existing automatic discovery 22, automatic queue SQL 12, FTPS queue SQL 27 and
  automatic source contracts 13 pass. Counts include repeated checks.

The independent review found and prompted fixes for the hash/binding race,
Android ctime portability and directory birthtime fallback, live revocation and
re-enable behavior, capture-idle scheduling races, active export protection and
other-destination retention. The abandoned writer-safe-point approach was removed
rather than claiming unbounded filesystem calls could not stall capture.

Official API 37 / Java 21 direct unsigned build (minSdk 29, version 0.1.0-dev10,
code 10) passed: 125,609-byte APK, SHA-256
`2df4946f1b261f9732511f42499bfad6cbafc1e079ff915734f6912e534b0dad`.
Sampled build RSS was 144.33 MiB. Production admission tests passed 533 assertions
with API fixtures 29–37, and the targeted Java-8 buffer linkage check passed.
The APK is an unsigned build check, not an installable release or device qualification.
No Android lifecycle, device filesystem durability, actual SQLite/process/power
loss, real FTPS/TLS interoperability, battery/thermal, screen-off or 24-hour tests
were performed. Legacy git-dependent wiring checks cannot fully run in this
extracted candidate; this is not a claim that every historical aggregate passed.
