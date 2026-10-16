# Continuous historical enrollment (dev13)

Dev13 allows an opted-in historical finalized chunk to be confirmed and queued
while a different chunk is being recorded. It reuses the dev11 physical owner
and single maintenance lease, the dev12 destination-specific exact history, and
the existing scheduler. It does not start recording, change credentials or expand
local deletion authority.

## Bounded progression

Discovery selects the lexically smallest 64 unknown finalized names, retaining
only those names plus a has-more result. It examines at most 4,096 root entries;
that hard namespace bound still stops discovery safely. More than 500 unknown
finalized names no longer disables consent: multiple bounded pages can progress
as detailed queue capacity becomes available. Exact membership for the pinned
destination, including compacted history, is the durable progress record. No
maximum ordinal or success at a different destination can skip a source.

A page with more results persists a five-minute continuation before any of its
IDs are processed. A busy in-memory page is retried without being repeatedly
rescanned. The next due continuation performs one new bounded scan only after
that page drains and capacity exists. Restart reconstructs a page from exact
membership; duplicate discovery is harmless. Finalization callbacks never scan
history. The 500 detailed-row, cache and compacted-history caps remain real limits.

One object is processed per existing worker turn. Discovery checks cancellation
before each entry lookup. Hashing has the existing ten-second cooperative limit.
Invalid identity, malformed content or a confirmation deadline still stops
enrollment for review and retains files. Busy/closing ownership, Pause, Disable
and transfer-job cancellation defer or revoke admission without treating the
source as delivered.

## Capture ownership

The transfer worker obtains the existing SpoolAdmission token and borrows the
same physical DirectorySpool owner used by capture, or opens one idle owner.
It takes an enrollment lease for one immutable finalized ID, performs canonical
ready confirmation, checks catalog identity, and retains the lease through the
queue admission/commit. Neither staging files nor an unconfirmed directory name
is upload authority. A source that vanishes before admission is a safe refusal,
not evidence that any destination received it.

The writer marks publication-in-progress before rename. Publication identity,
confirmed/eligible membership and active-stage clearing use the same short
monitor. A visible ready name still being published is transiently busy. There
is no filesystem or database I/O inside that monitor. Capture append does no new
namespace scan, confirmation hash or queue work.

Stop seals lease admission and cooperatively cancels confirmation hashing, then
retains the physical writer lock until the issued lease closes. Platform fsync
is not interruptibly bounded, so Stop can still wait indefinitely for filesystem
I/O. Shared storage contention remains, and no real-time or 24-hour guarantee is
made. SAF admission remains reciprocal with maintenance.

## Pause, Disable and destination changes

Pause preserves destination consent and pending progress, cancels cooperative
work and stops scheduled transfers. Resume uses the existing queue action.
Disable is available during a busy worker and immediately revokes new enrollment
admission; its durable update clears both the destination pin and continuation.

Each operation captures a single-use ticket tied to the exact source, destination
and consent epoch. Disable before the final short admission boundary prevents
queueing. One operation already admitted at that boundary may finish its SQLite
commit; no I/O holds the consent monitor. Already queued transfers retain their
existing authority, and Pause is their stop control. Re-enabling, including the
same destination, cannot revive a stale ticket. Changing the displayed profile
does not retarget existing consent. A stale wake cannot restore a disabled pin.

## Evidence and limits

Tests use freshly generated synthetic fixtures. Direct production-core checks
cover more-than-500 paging, known-name progress, restart/full-capacity models,
4,096-entry rejection, cancellation, append during shared-owner confirmation,
publication-visible busy admission, stale leases and Stop/physical-lock lifetime.
A separate fixture executes the actual compiled production consent class for
Disable before/after admission, ticket reuse/foreign tickets and revision changes.
Database/UI wiring checks remain explicitly host models or source contracts.

No phone, Android lifecycle, real FTPS server, credential, microphone, power-loss
or all-day runtime acceptance is claimed. OS background scheduling, charging and
network restrictions, small-tail transfer thresholds, cache pressure, source
errors and uninterruptible filesystem calls still constrain progress.

Recorded finite checks: 1,318 new core enrollment assertions, 26 actual compiled
production consent assertions, and 22 narrow integration source contracts passed.
Existing checks also passed: 42 concurrent cleanup, 22 original discovery,
5,096 history retirement, 40 reconciliation cases / 276 assertions, 19 spool
admission, 81 history SQLite, 12 automatic-queue SQLite, 30 cleanup SQLite,
389 storage and 656 recovery integration assertions. The existing automatic
wiring (13) and history wiring (20) contracts were updated for shared ownership.

The complete app compiled against the installed official API 37 SDK using Java
21 and produced a 143,788-byte unsigned APK; sampled build process-tree peak was
148.64 MiB. A further 533 production admission assertions passed against those
compiled classes using explicit host API fixtures, along with the targeted
Java-8 buffer linkage check. The APK was not signed, installed or delivered.
