# Policy and metadata core, first implementation

This is a platform-independent Java core, not an Android recorder. It has no
Android imports, third-party dependencies, network clients, credential handling,
filesystem operations, threads, or recording-service implementation. Java was
chosen for this first dependency-free contract slice; Kotlin Android components
can call these classes later.

## Minimal API

Package: `io.github.headmaster218.recorder.core`, under the conventional
`app/src/main/java` and `app/src/test/java` source directories.

- `PcmFormat`: configurable positive sample rate and 1–32 channels, PCM16
  little-endian; default 16,000 Hz mono. Checked frame/byte arithmetic. This
  expresses configuration, not device support or permission to adapt silently.
- `CaptureTimeline`: a single-writer epoch plus segment frame counter.
  `framesUntilBoundary()` tells the writer how to split the next input buffer.
  `appendFrames()` accepts only complete frames within that limit. `seal()`
  creates immutable segment metadata using a caller-computed payload SHA-256.
  Routine rotation preserves the run, epoch and adjacent frame positions.
  Stop, interruption or a recovered tail requires a new epoch before further
  frames. `beginEpoch()` rejects an unsealed tail, stale epoch, different capture,
  or a recreated stream without a new run identity and explicit restart reason.
- `CachePolicy.beforeWrite()`: checks the configured spool maximum and remaining
  free-space reserve without overflow. A failed check returns `STOP_AND_ALERT`.
  It never deletes or designates any pending recording for eviction.
- `TransferPolicy.evaluate()`: threshold, oldest age, session stop and Upload now
  are OR triggers. Charging, connectivity, Wi-Fi, cellular, metered and roaming
  constraints are AND gates. Defaults require charging and unmetered Wi-Fi.
  Upload now does not bypass these gates. Unknown, mixed and ambiguous routes
  are blocked. Local-only Wi-Fi does not require public Internet validation.
- `QuotaLedger`: reserve an upper traffic bound, persist the charged snapshot,
  send a bounded unit, account actual traffic, and release only unused capacity.
  Every retry consumes a fresh reservation. A snapshot includes outstanding
  reservations, so crash recovery retains their full possible charge. Daily and
  monthly windows cannot move backward or reset during an active reservation.
- `DeletionGate`: READY → UPLOADING → REMOTE_VERIFIED → REMOTE_COMMITTED →
  LOCAL_DELETE_ELIGIBLE → LOCAL_DELETED. Size plus payload SHA-256 must match via
  trusted server hashing or authenticated full readback. Receipt identity binds
  the fragment, destination, all three object paths, size and payload/metadata/
  marker hashes. Publication is followed by local receipt persistence and a
  fresh check of final objects. Retention and strict durability requirements
  remain additional gates. A failed later recheck revokes eligibility.

All mutable objects are single-owner, single-writer contracts. They are not
thread-safe, persistent stores, proof-producing verifiers, or I/O executors.
Adapters must not invoke evidence acknowledgement methods before the named
operation has actually succeeded. Constructors and transitions reject invalid
arguments, overflow, wrong objects and unsafe ordering.

## Capture integration contract

The writer must keep the same capture stream running across routine file
rotation, split interleaved buffers only at complete frames, and hash the exact
bytes it durably finalized. Rejected append/seal operations do not consume
frames. A segment may close short for stop/interruption/recovery, but an ordinary
rotation must have exactly the configured target frame count.

`targetFrames` is an exact frame count in the actual epoch format, not a wall-clock
duration. The integration must convert a selected duration to actual-format
frames and recreate the planner with its persisted next sequence if the target
changes. A fresh planner never infers continuity with a prior process. Persist
sequence allocation before publishing files so a crash cannot reuse an identity.

Epoch boundaries distinguish restart, uncertain route change, format change,
policy silence, dropped frames and unknown interruptions. Unknown missing time
remains unknown; no silence or exact lost-frame duration is invented. Requested
and actual routes are separate. Input errors and observed silencing must be
reported by the future Android adapter; zero amplitude alone is not evidence.

The version-1 in-memory `SegmentManifest` is a first contract slice. A canonical
on-disk encoding, schema migration, timestamp/boot anchors, detailed gap-interval
journal, checkpoint recovery, previous-fragment digest chain, requested/device
formats and resampling are still unimplemented. Do not treat this as a complete
serialized manifest specification or claim hardware continuity from these tests.
Dates, upload order and wall-clock corrections do not drive its frame counters.

## Storage and transfer integration contract

`occupiedBytes`, available space and each prospective write must include all
payloads, staging, metadata, journals, finalization and relevant overhead.
The reserve must be maintained before starting capture and rechecked before
writes. The future recorder must visibly stop and preserve unverified files
when space or its bounded in-memory queue is exhausted. These contracts do not
reserve OS disk blocks or protect against a concurrent external disk consumer.

Evaluate policy and acquire quota together under one coordinator before each
bounded transfer. Reevaluate after network/power changes and bind the socket to
the approved network where supported. Wi-Fi and metered are distinct. Current
network modeling deliberately supports Wi-Fi or cellular only; unknown wired/VPN
routes are blocked until the adapter can establish an unambiguous supported
route. No destination reachability or transport security is inferred.

Resolve quota windows using the configured timezone outside this Java core.
Pass strictly ordered day/month keys, and retain stored high-water marks across
wall-clock rollback. Reconcile/stop transfers at a window boundary before reset.
Charging snapshots must be durably stored before I/O; do not persist only the
actual byte count while a reservation remains outstanding. Restore a new ledger
with `Snapshot.dailyCharged` and `monthlyCharged`; unknown in-flight bytes stay
charged, even though this can conservatively exhaust the budget early.

Count uploaded payload, metadata, commit traffic, retries, authentication/
protocol overhead that can be measured, and verification downloads. On an
unexpected overrun `recordBytes()` still charges observed bytes and returns
false; stop and reconcile all active transfers immediately. No further packet
is authorized by that return value. Reservations must bound traffic before it
is emitted, not retroactively. This is an application budget, not a guarantee
about carrier billing or unobservable protocol overhead.

The deletion contract performs no file deletion. The adapter must independently
verify remote content, publish without overwriting conflicts, verify metadata,
publish/verify the commit marker last, persist the exact receipt, then recheck
all final objects. Persist and reconcile every real transition. On restart,
reconstruct evidence from durable records and recheck final objects; a fresh
gate is READY and provides no delete permission. Repeated physical deletion
and crash recovery need an idempotent store implementation, which is not here.
Strict durability requires an actual destination capability and acknowledgement;
a boolean argument cannot establish NAS power-loss safety. Retain data when the
configured durability requirement cannot be met.

## Verification and limits

Run `sh scripts/test-core.sh`. It compiles all production/test sources with Java
8 source/classfile targets and executes a dependency-free `main` test harness.
No Gradle, SDK, dependencies, downloads, keys or Android build are needed.

Verified on OpenJDK 21.0.12.1 only. The existing runtime supplies the compiler module used here. This script does
not perform Java-8 `--release`/boot-classpath API validation.
`-source 8 -target 8` checks syntax and produces Java-8 classfiles; it does not
prove API availability on an actual Java 8 runtime or Android. The code confines
itself to basic Java APIs, but real Java-8/Android compilation remains a gate.

The deterministic harness covers sizing/overflow, variable-read frame splitting,
short tails, recovery/restart/route boundaries, preservation on invalid calls,
cache capacity/free-space edges, triggers and constraint changes, local Wi-Fi,
quota retry/accounting/crash snapshots/clock rollback/month rollover/overruns,
and deletion failure injection including same-size wrong hashes, destination
mismatch, missing receipts, durability and retention blockers, and a revoked
final-object check. It uses no audio, credentials, network or remote files.

Still untested: Android packaging, permissions, device audio capture, route
callbacks, real filesystem crash durability, quota persistence, NAS/protocol
adapters, signing, foreground service behavior and long-running phone operation.
