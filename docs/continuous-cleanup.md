# Concurrent finalized-object cleanup (dev11)

This source slice allows the already opted-in, destination-pinned dev10 cleanup
policy to reclaim finalized recordings while a different chunk is being recorded.
It does not change the weaker readback-at-time proof, consent, receipt database,
remote verification, quarantine/replay protocol or strict `DeletionGate`.

Cleanup remains off by default. The remote copy may later change or disappear;
permanent local deletion can make that future loss unrecoverable. See the
[policy and crash protocol introduced in dev10](verified-local-cleanup.md).

## Ownership and scheduling

One physical `.writer.lock` owner remains for the entire capture session. The
transfer worker borrows that exact owner through one bounded maintenance lease,
for one immutable finalized ID. Active `.part` objects cannot receive a lease.
Newly published chunks become eligible only after normal finalization, root sync
and validation finish. Objects present at startup undergo canonical
`confirmReady` before quarantine, with a ten-second cooperative hash deadline
(platform fsync itself is not interruptibly bounded). Journal-authorized partial purge recovery uses
the existing exact identity checks and never infers authority from absence.

Capture never performs cleanup hashing, rename, unlink or fsync. All such work
runs on the existing transfer worker. Small monitors protect admission and byte
accounting only; no filesystem operation runs while those monitors are held.
A global SAF listing/picker/copy reservation blocks maintenance admission, and
SAF admission reciprocally refuses while a maintenance token is issued. Existing
multi-destination queue rows that still need a source also block cleanup.

Stop first waits for microphone release, then seals maintenance admission and
preserves the active stage. It waits for already issued leases before releasing
the physical lock. The UI remains visibly stopping, and `spoolClosed` evidence
is recorded only after actual close. A blocked filesystem call is never forcibly
abandoned or unlocked. Token identity rejects stale or duplicate returns. A new
capture cannot replace an owner that is opening, closing or still borrowed.
Start during an idle maintenance reservation fails safely with a retry-Start
message instead of waiting. The user must retry after maintenance finishes.

## Conservative cache accounting

The prior per-append namespace walk is replaced by one bounded initial census
and a current-owner ledger. The census includes regular root files, partials,
metadata, orphan files, quarantine and retired markers. Unreadable entries,
symlinks, nested directories, duplicate canonical reservations, invalid segment
names, overflow or more than 4,096 root objects fail admission while preserving
bytes. At most 4,096 direct members of any object are inspected.

The ledger measures logical file bytes, matching the existing cache policy. It
is not an allocated-block/filesystem-overhead guarantee. The existing available
space reserve is still checked before each write.

Preflight checks do not charge bytes. A write reserves its growth once before
I/O; uncertain/failed writes retain the charge until restart. Finalization
headroom is checked once per preflight, not accumulated on each PCM append.
Only a fully durable successful purge credits that current owner's object
charge. Partial purge failure retains its full charge. Restart rebuilds charges
from surviving files; it never subtracts original persisted binding sizes.
Replay of an already retired object credits zero. This prevents crash-mid-purge
recovery from undercounting unrelated audio.

Sequence selection is monotonic in memory during ownership. Existing partial,
ready, quarantine and empty retired reservations establish the restart maximum.
No new sequence journal or watermark is introduced, and retired markers remain.

A known confirmation failure before the quarantine operation is entered becomes
BLOCKED for inspection, preventing repeated remote downloads for an invalid or
consistently slow source. Busy/closing admission alone remains deferred. Genuine
crash-uncertain intents still use the existing restore/recheck protocol.

## Bounds and remaining limits

- One capture writer, one maintenance lease and the existing bounded transfer job.
- No cleanup I/O or namespace enumeration on the PCM append path.
- Cache-full still stops and preserves recordings when uploads or cleanup lag.
- 500 queue/history rows and permanent ordinal markers remain. History retirement
  and bounded tombstone compaction are separate unfinished work.
- Existing small-tail threshold, charging/unmetered Wi-Fi policy and credential
  availability still constrain progress.
- Startup catch-up still waits for idle ownership; new finalized callbacks enroll
  during recording. A deferred backlog retains a five-minute scheduled retry;
  admission is checked again without a new history scan. Android scheduling and
  the existing transfer-job constraints can delay that wake.
- Shared flash contention remains. Existing capture append fsync and finalization
  hashing can also stall. This is no hard real-time or uninterrupted 24-hour claim.
- Stop can wait indefinitely for a blocked filesystem call; ownership is retained
  rather than admitting unsafe overlapping capture.

## Evidence

Host tests use freshly generated synthetic files only. New tests exercise the
production ledger, partial-crash census credit, quota overflow/bounds, exact
reservation accounting, failure reservation retention, blocked cleanup fsync
concurrent with PCM append, physical-lock retention during close, stale lease
rejection, ordinal restart and unsafe census entries. Production Android
admission state is tested with a small export-busy model; it is not Android
lifecycle or SAF execution. Existing cleanup protocol/fault tests remain.

Current host results: 42 new core ledger/concurrency/fault assertions, 19
production admission assertions, 54 cleanup assertions, 389 storage + 11
adversarial cases, 7,068 policy + 14 adversarial cases, 301 export, 868 FTPS,
656 recovery integration, 325 recovery session, 129 capture evidence, 91 capture
anchors, 1,310 interruption policy, 22 automatic discovery and 58 app-helper
assertions passed. Cleanup/automatic/FTPS host SQL checks passed 30/12/27
assertions, and narrow cleanup/automatic source contracts passed 17/13.
These counts describe finite tests, not complete state-space coverage.

Official offline API 37 / Java 21 direct unsigned build passed (minSdk 29,
version 0.1.0-dev11/code 11): 129,960 bytes, SHA-256
`42c822a6974e7f5fdcbf31a9dfe6307861183bded39cbbfb0eba573fc5f6c136`.
Sampled build RSS: 143.95 MiB. Production FTPS admission/route tests passed
533 assertions with API fixtures 29–37 against the final compiled classes.
The unsigned APK is a build check, not a release or device qualification. No real user file was deleted, no server
or credential was used, and no Android device, process/power-loss, screen-off,
battery/thermal or 24-hour acceptance test was performed.
