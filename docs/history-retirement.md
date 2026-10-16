# Bounded exact history retirement (dev12)

Dev12 compacts detailed transfer rows only after the source's local cleanup has
completed and its exact original empty `.retired` directory is proven. This
module never removes audio. Cleanup still requires the separate destination-bound
opt-in and fresh readback-at-time proof described in [dev10](verified-local-cleanup.md).
The remote copy may later change or disappear. Compacted history is deduplication
membership, not a replacement receipt or proof of current remote availability.

## Eligibility and bounds

All rows for a source retire together. Every destination must have a verified
receipt with matching source hashes and size. At least one original `REMOVED`
cleanup binding must remain. Pending transfers, uncertain cleanup and mismatched
identities block retirement. Destination revisions remain separate: success at A
never proves delivery at B. No gap is filled by taking a maximum ordinal.

The SQLite transaction merges only exact adjacent sequence intervals. Bounds are
2,048 intervals, 256 capture floors, 512 destination/capture groups and eight
outstanding source retirements globally, with at most eight sources per pass.
The existing 500 detailed-row and 32-profile limits remain. Limits are evaluated
on the final transaction result; saturation rolls back and retains source/history.
Contiguous eligible history can compact repeatedly, but arbitrary fragmentation,
new captures, unverified rows or a blocked cleanup can still exhaust a finite cap.

The UI reports compacted destination receipts, both total and for the displayed
profile. A source delivered to two destinations counts twice. Detailed per-upload
receipts are no longer available after compaction.

## Crash ordering and database identity

1. Commit exact revision-specific ranges, capture floor, global generation and
   pending generation on all retained source rows in one SQLite transaction.
2. Durably replace the bounded, checksummed `.retirement-state` sentinel, which
   binds a random store UUID and generation to this spool.
3. Under the existing owner/maintenance lease, remove only the exact original
   empty marker, then sync the spool directory.
4. Revalidate pending source rows and delete those detailed rows in a second
   SQLite transaction.

The detailed rows and original binding remain the bounded intent until step 4.
Marker absence authorizes only completion of this already committed retirement,
never deletion of audio. Replaced inodes, nonempty markers, conflicting source
names or malformed bindings fail closed. The existing audio quarantine/purge
protocol is unchanged.

Startup reconciles the sentinel and database while holding the physical writer
lock, before capture can append. A database ahead of the sentinel must account
for each missing generation using bounded retained pending rows and exact empty
markers; it advances and syncs the sentinel before pruning. A database behind
the sentinel, UUID mismatch, or missing sentinel after initialization blocks
admission. Missing/recreated database with an existing sentinel cannot silently
start at zero. Initial legacy enrollment is allowed only with empty retirement
state and writes the sentinel before marking initialization complete. An exact
unfinished temporary sentinel can be replayed; ambiguous metadata is retained.

Floors are validated and installed before use. Removing a marker releases only
the current owner's zero-charge ledger entry and confirmed/eligible entries,
without lowering the owner's sequence high-water mark. Sequence updates share
one small monotonic monitor. New capture IDs are fresh UUIDs; restoring/resetting
part of the database/spool independently is unsupported and blocks when detected.
Simultaneous loss of all identity evidence cannot be reconstructed.

## Capacity consent and unresolved all-day gap

Queue capacity pressure retains the exact destination pin and a durable next
capacity-check time. One scheduled five-minute wake, subject to the existing
charging/network gates, attempts compaction and one bounded discovery scan when
space exists. Publication callbacks never rescan the directory. The waiting flag
clears only after that scan captures skipped names. Explicit Disable atomically
clears both consent and waiting state; stale wakes cannot restore either. Prior
versions' already-cleared consent is not restored.

Discovery still stops safely at more than 4,096 entries or 500 unknown finalized
candidates. A scan or validation failure can still turn enrollment off for review.
Historical catch-up confirmation still waits until capture stops: chunks skipped
during capacity pressure can therefore remain unenrolled throughout a continuous
capture session. This is an unresolved all-day gap, even though ordinary
new-chunk enrollment resumes when capacity exists. Shared-owner historical
confirmation is a separate future change.

## I/O and finite acceptance

Sentinel and SQLite work run on startup or the transfer worker, never capture
append. Startup uses bounded census passes before and after reconciliation, and
cache admission reserves 4 KiB of metadata staging headroom. Logical file-byte
accounting is not a filesystem-allocation guarantee; the existing free-space
reserve remains relevant. Active capture continues under the dev11 shared owner;
Stop can wait for outstanding filesystem I/O, and Start during idle maintenance
can require retry. See [dev11 ownership limits](continuous-cleanup.md).

Tests use fresh synthetic files only. `test-history-retirement.sh` checks exact
ranges, marker guards, sequence-floor concurrency, 32 real owner cleanup/retire
cycles, and 5,000 ledger-only retirements. The latter is not 5,000 recordings.
`test-retirement-sentinel.sh` checks atomic metadata and failure boundaries.
`test-retirement-store.sh` compiles the production reconciliation class and real
core with explicitly modeled Android/database ports. `test-history-queue-sql.py`
executes production DDL and queries on host SQLite while modeling Java transaction
steps. These establish neither Android SQLite/runtime behavior nor hardware
power-loss durability. No real recording, credentials, external FTPS transfer,
phone runtime or uninterrupted all-day acceptance is claimed.

## Recorded finite checks

The candidate passed 5,096 core retirement assertions, 149 sentinel assertions,
40 production reconciliation cases / 276 assertions, and 20 integration source
contracts. The 81 host SQLite assertions additionally cover populated schema versions 1, 2
and 3 upgrading to 4, exact multi-destination membership, all finite caps and
capacity-wait pin preservation across restart/Disable. Counts include repeated
checks, not independent scenarios.

Existing regressions passed: 19 admission, 22 discovery, 12 auto-queue SQL,
30 cleanup SQL, 27 FTPS SQL, 7,068 policy-core, 389 storage, 11 independent
storage cases, 656 recovery integration, 129 capture evidence, 91 capture
anchors, 301 export and 868 scripted FTPS assertions. Concurrent cleanup (42)
and verified local cleanup (54) also passed. All remain host/synthetic checks.


Official installed Android API 37 stubs and Java 21 compiled the complete app,
and the direct unsigned build completed with a 146.61 MiB sampled process-tree
peak. The unsigned APK is 141,060 bytes. A further 533 admission assertions ran
against those compiled production classes with explicit API 29–37 host fixtures;
the targeted Java-8 buffer linkage check passed. The APK was not signed,
installed, delivered or tested on a device.
