# PCM/WAV segment writer and local storage handoff

Development slice on top of the reviewed 0.1.0-dev2 policy core. Pure Java source
and tiny synthetic JVM tests only. No Android recorder, SDK, foreground service,
network adapter, credentials, real recordings or device validation are included.
This is not a long-running recording or power-loss durability claim.

## Public boundaries

- `PcmSegmentWriter`: single-owner streaming byte input, actual `Epoch` and exact
  target frame count, persisted next sequence, and a publication callback. Uses
  the existing `PcmFormat`, `CaptureTimeline` and `SegmentManifest`. Writes at
  most 8 KiB per iteration. No growing audio buffer or completed-segment list is
  retained by the writer. The caller's input array and listener queue are owned
  externally and must be bounded by the future recording integration.
- `WavHeader`: canonical 44-byte RIFF/WAVE PCM16 header. Default audio remains
  16 kHz mono; supported configuration is explicit. Checked unsigned RIFF and
  byte-rate limits reject oversized segments before recording. RF64 and other
  encodings are out of scope.
- `SegmentStore`: injectable persistence handoff. A staged object accepts bytes,
  finishes with an exact segment manifest, or closes while preserving its bytes.
  Only successful finalization returns a `Published` object.
- `DirectorySpool`: a reference local filesystem implementation with one
  exclusive file lock, a logical-byte cache ceiling/free-space reserve, stable
  capture-hash/sequence names, bounded verification reads and fault hooks.
  `DirectoryProtocol` supplies directory sync and atomic directory publication.
  Its Java NIO implementation exposes unsupported durability or I/O failure via
  `StorageException`; it never falls back to non-atomic moves or skips sync.
- `SegmentMetadata`: bounded version-1 local binary intent/manifest encoding,
  limited to 8 KiB and 512 UTF-16 units per identity/route string. This is a
  versioned local storage format with a SHA-256 checksum footer on intent and
  manifest records, not a complete transport manifest schema. Checksums detect
  accidental corruption; they are not authentication against an attacker.

Successful arbitrary read-buffer boundaries, including odd byte counts and
stereo interleaving, are combined without dropping or inventing bytes. Files
rotate on exact frame boundaries without any capture restart API. An incomplete
interleaved frame may exist temporarily in staging; it cannot be published.

`beginEpoch()` closes a whole-frame tail as interrupted before moving to the new
route/format/run epoch. The next file has an untrusted prior seam and frame offset
zero. An invalid transition is rejected before finalization. A partial-frame
transition, stop, cache block or I/O failure makes the writer terminal and leaves
staged data for inspection. A new lawful recording run/epoch must be explicit.
Target frames are in the actual format, not a wall-clock duration; an application
changing format must recompute duration-based configuration explicitly.

## What is persisted

Each object starts as `segment-<capture SHA-256>-<sequence>.part/` containing:

- `intent.bin`: capture, run, epoch, requested/actual route, boundary reason,
  actual PCM format, sequence, epoch-relative starting frame and target frames
- `audio.wav`: placeholder header followed by the received PCM bytes
- `manifest.bin`, created only during successful finalization: intent plus actual
  frame count, close reason, prior-seam assertion, raw PCM SHA-256 and final WAV
  SHA-256; byte counts are derived with checked arithmetic

The raw PCM hash/count correspond to `SegmentManifest`. The artifact uploaded
later is the complete WAV: use `wavBytes` and `wavSha256`, including its header,
for remote verification and quota sizing. They are not interchangeable.

The final object is the whole directory renamed to `.ready/`; both WAV and
metadata cross that namespace boundary together. `.ready` is a candidate name,
not upload permission. A fresh process must call `confirmReady()` to validate
and re-sync it. Never enqueue a directory merely because its name says ready.

`nextSequence(captureId)` includes incomplete and even empty failed staging
reservations. `open()` rejects a stale ordinal rather than reusing a name. The
caller must resume with at least this ordinal and a new run/epoch after a stream
restart. No staged or published identity is overwritten, reclaimed or deleted.

## Ordered write and publication contract

For a newly reserved object:

1. Check the cache ceiling and disk reserve before growth; exclusively create its
   staging directory. Write and force the intent, create the placeholder WAV,
   force it, then sync the staging and parent directories before accepting PCM.
2. Append bounded input chunks and force the WAV. The timeline advances only
   after the storage call succeeds. A partial system write followed by failure
   is retained; the writer terminates because the exact acceptance boundary is
   now uncertain. Do not replay the whole input call and claim continuity.
3. On a complete-frame close, patch the WAV header in staging and force/close
   the file. Re-read it in bounded blocks to verify the canonical header, exact
   length and PCM hash, and calculate the full-file hash.
4. Exclusively create and force the manifest. Sync the staging directory.
5. Atomically rename the complete staging directory to its final name without
   replacing an existing object, then sync the parent directory.
6. Re-read/validate the exact three files, intent/identity, lengths, header and
   both hashes. Only then return `Published` and invoke the listener.

Injected failures before any operation return no publication permission. A
failure after rename may leave a complete `.ready` directory; reconciliation
must verify and re-sync it before use. A callback failure also leaves a complete
local object, so downstream queue insertion must be idempotent by capture,
sequence and content identity. This implementation never uploads anything.

The no-replace operation assumes an exclusively owned private directory and
cooperating processes obeying the lock. Java's `ATOMIC_MOVE` alone is not a
universal no-replace primitive. Do not use this implementation on a shared or
hostile directory where another writer can substitute paths. Direct object
symlinks and unexpected finalized entries are rejected, but this is not a
security boundary against arbitrary mutation of ancestor directories or files.

## Recovery policy: preserve and report

`scan()` is read-only and streams one report at a time:

- `PRESERVED_PARTIAL`: valid intent, plausible bounded WAV size and canonical
  placeholder/final header. Reports observed whole frames and trailing bytes.
  The prefix is only a salvage candidate. It is not a durable-prefix guarantee,
  a checksum proof of every sample, or a continuity assertion.
- `CORRUPT_PRESERVED`: missing/truncated/malformed intent, corrupt WAV header,
  invalid finalized metadata/hash/length, unexpected files or other corruption.
  Preserve all files and expose the blocker.
- `FINALIZED_UNCONFIRMED`: a final directory validates, but must still pass
  `confirmReady()` before upload admission in this process.

No partial recovery truncates a tail, fixes a header in place, discards a byte,
creates synthetic silence, appends a new recording, or promotes an object for
upload. A future approved recovery tool can copy a proven whole-frame prefix
into a new object/uncertain recovery epoch while retaining the original and an
audit record. Automatic salvage and durable per-read frame checkpoints are
explicitly not implemented. No irreversible retention operation exists here.

## Cache and platform limits

Cache checks count the logical bytes of all files in the spool, including staged
WAVs and metadata, before each growth operation. An open file reserves 8 KiB for
final metadata plus 4 KiB of extra headroom; a configurable minimum free-space
reserve applies as well. The extra allowance is not a universal filesystem block
or journal-size calculation. The caller must set a conservative reserve for its
filesystem and concurrently active processes. On failure, stop-and-alert remains
the required UI behavior; this Java core has no UI and never evicts recordings.

Directory fsync, atomic rename and file forcing differ across filesystems and
Android versions. The protocol must report failure/unsupported capability and
retain local files when it cannot honor the ordering contract. A successful
Java call/test on this Linux filesystem is not proof against power loss, device
controller caches or an Android implementation's behavior. There is no atomic
transaction across all filesystem operations; recovery handles their visible
boundaries conservatively.

Published identity strings are local logical names, not the stable OS object
versions or remote leases required by `DeletionGate`. This slice does not
satisfy that separate guarded-deletion adapter contract. Future capture must
also provide bounded queueing, permission/routing events and lawful restarts.

## Test command and scope

Run `sh scripts/test-storage.sh`. It uses the installed compiler module with
Java-8 source/classfile targets and JVM 21, without dependencies or Android tools.
Java-8 runtime/API and Android compatibility remain unverified. Fixtures are tiny
synthetic bytes in ignored `app/build/storage-tests/`; no downloaded or user audio
is read. Corruption tests deliberately alter only those synthetic fixtures.

Tests cover RIFF bounds, zero/invalid input, exact mono/stereo frame counts,
odd read boundaries, short tails, route epoch transitions, sequence collision,
cache-full preservation, incomplete frames and simulated partial system writes,
all named persistence fault points, same-size corruption, recovery/reconfirmation,
exclusive ownership, unsupported directory sync/atomic rename and callback failure.
True process termination/power loss, Android behavior, hostile filesystems,
physical block quotas, performance/battery use and real storage media are not tested.

Author verification on OpenJDK 21.0.12.1: 361 storage assertions, the unchanged
7,068 policy-core assertions and 14 existing policy adversarial cases passed.
All 15 named storage-operation fault hooks were exercised. Final compile plus
these suites took 4.23 seconds on CPU 6/nice 19; sampled compiler/test peak RSS
was 95.54 MiB, or 104.41 MiB including its observer. The final test script uses
32 MiB Java heaps. These are sampled local measurements, not strict cgroup peaks
or benchmarks. Independent review of this new storage slice is still required.
