# Capture observations (dev7 source candidate)

This adds bounded observational metadata, not a hardware-to-PCM mapping. All
summaries explicitly say `UNESTABLISHED`. No inferred lost-frame count, silence
insertion, trimming, timestamp-based splitting, device identity, deletion,
automatic transfer, or backend integration is added. The PCM writer's exact
byte partitioning and existing route/stop behavior remain intact.

## Producer measurements and units

`CaptureEngine` brackets each positive PCM read with `elapsedRealtimeNanos` and
carries an immutable `CaptureAnchors.Read` in the existing bounded queue block.
The ordinal counts **positive accepted reads**, starting at zero per run; zero
returns are not retained or numbered. Cumulative read offsets count interleaved
PCM16 **samples**, not multichannel frames. The writer associates exact sample
slices with each segment and its existing run/epoch/frame offsets. One read can
span multiple segments; each retains the original read ordinal and brackets.
Writer/storage queue latency never substitutes new timestamps.

Wall-clock milliseconds are bracketed separately by elapsed realtime nanos.
Uptime milliseconds support a conservative elapsed-versus-uptime discrepancy
flag. Bracket widths are observational uncertainty, not UTC accuracy or
microphone latency. No identity stronger than a process-scoped random UUID is
claimed; it persists only for the life of the process. Existing `runId` is the
AudioRecord start generation. Pause/restart starts a new run and epoch.

Optional AudioRecord timestamp polls use BOOTTIME at most once per second.
Raw successful frame positions retain all 64 bits, including values beyond
2^32; there is no AudioTrack-style 32-bit unwrap. Failed status codes and query
exceptions are explicit, without invented frame/time values. Timestamp failure
alone does not stop capture. Read, wall and timestamp-query brackets and the
configuration counter before/after attribution remain distinct observations.
They cannot identify which hardware frame corresponds to an array boundary.

Only six immutable read references are retained per open segment: first/last
read, first/last attempted query and first/last successful query. Counters record
distinct positive reads, attempted queries, unavailable/exception queries and
reads carrying anomaly flags. Successful queries equal attempted minus
unavailable. Older intermediate observations are intentionally not recoverable.
This is constant memory per segment; there is no all-day event list.

Flags preserve run start/reset uncertainty, configuration/route changes,
clock regression, timestamp regression/repetition/staleness, wall-clock jumps,
and possible suspend-domain discrepancy. Wall change >2 seconds against elapsed
time, elapsed/uptime discrepancy >1 second, and a timestamp >2 seconds older
than its query (or ahead of query completion) are conservative diagnostic
thresholds, not calibrated measurements. A repeated timestamp can be benign.
A missing flag does not prove uninterrupted capture. Anomalies retain their raw
observations and do not change PCM bytes or establish a mapping across epochs.

## Persistence and compatibility

The ready directory still permits exactly `audio.wav`, `intent.bin` and
`manifest.bin`. Intent stays version 1. A manifest without observations stays
version 1, including when decoded and re-encoded. Its bytes and queued FTPS
metadata hashes remain unchanged. No existing metadata, capture/run IDs, source
hashes or WAV bytes are rewritten by migration.

A manifest with observations uses header version 2. Existing intent fields and
manifest fields precede a strictly parsed anchor extension (anchor schema 1,
BOOTTIME identifier 1, mapping string, sample range, counters, flags, and bounded
read records). The existing SHA-256 checksum protects the entire payload. The
8 KiB maximum includes the checksum. Unknown versions, timebases, enum values,
flags, trailing bytes, truncation and corrupt checksums fail validation. Large
allowed source strings can make v2 exceed 8 KiB; finalization then fails closed
and retains staged audio rather than increasing the limit or dropping fields.

New readers accept old v1 objects; old readers **cannot read new v2 objects**.
Do not downgrade an app and expect new recordings to be readable there.
FTPS's existing RFT1 byte envelope is unchanged and contains the explicit local
manifest schema. Consumers must support local manifest v1 and v2; RFT1 itself
does not imply a v1 local manifest. No backend consumer is implemented here.
Source allowlists, exact-byte hashes, markers and full readback checks stay
unchanged. Readback is integrity verified at that time, not remote durability
or permission to delete originals.

## Verification and remaining gates

Run `sh scripts/test-capture-anchors.sh` and
`python3 scripts/check-capture-anchor-wiring.py` alongside existing lower-level
core, storage, safety, export and FTPS scripts. The golden v1 bytes were produced
by independently compiling unmodified baseline core source at
`60767916fd4cdd735c5cec2cef643aa3e37523a3`. Synthetic tests cover exact stereo PCM
partitioning, one read spanning chunks, producer-value retention under writer
delay, bounded long summaries, v1/v2 transport serialization, unavailable
queries, anomalies, process-domain separation and failed/oversized finalization
retaining audio. Source-wiring checks are not Android runtime tests.

No APK build/signing, phone installation, resource validation, timestamp
accuracy, battery improvement, screen-off/all-day behavior or loss-free capture
is established. Real device tests must still cover routing/calls/silencing,
clock changes, queue pressure, storage failure, process/power loss and controlled
reference-signal seams. Independent review remains required before publication.

Official API references checked 2026-10-10:

- [AudioRecord.getTimestamp](https://developer.android.com/reference/android/media/AudioRecord#getTimestamp(android.media.AudioTimestamp,%20int))
- [AudioTimestamp fields and clock domains](https://developer.android.com/reference/android/media/AudioTimestamp)
- [SystemClock](https://developer.android.com/reference/android/os/SystemClock)

### Host verification at this source candidate (2026-10-10)

All 17 final lower-level checks completed successfully on CPU0/nice19. Focused
anchors: 91 assertions. Existing suites: 7,068 core assertions, 14 adversarial
core cases, 389 storage assertions, 11 storage adversarial cases, 58 app helper
assertions, seven app adversarial cases, 301 export assertions, 868 scripted
FTPS assertions, 27 host SQLite checks, and 533 production-method FTPS admission
fixture assertions. Counts include repeated checks, not distinct scenarios.

All production Java sources compiled with Java 8 source/classfile targets
against the official API-37.0 revision-2 android.jar. Two existing FTPS API
deprecation warnings remained. The sole app resource symbol was supplied by a
clearly marked **compile-only placeholder R.java**, not aapt output. This proves
Java/API source compatibility for the checked sources, not Android resource
linkage, DEX/APK packaging, runtime behavior or platform acceptance.

Archive: `https://dl.google.com/android/repository/platform-37.0_r02.zip`,
67,281,901 bytes. Its SHA-1 matched official `repository2-3.xml`:
`ed8ebf7f8822a4de5686d427f237d2fa30ff7410`.
Archive SHA-256:
`840b23e827f96e64aea4c89a1194aac3dc5f6bad37edb231c5c795d890330e8d`.
Extracted `android-37.0/android.jar`: 43,044,472 bytes, SHA-256
`bf1b4387cc7ca94fc6ef684f040d9d16fbf16248e181819f020736ea2053f177`.
Only that jar was extracted; no build-tools or Gradle dependencies were fetched.

The final supervisor's highest sampled process-tree RSS was 146,780,160 bytes,
below the 512 MiB allowance. The scratch tree remained below 256 MiB. Samples
are observations, not a kernel-enforced peak-memory guarantee.

Initial test setup failures were retained and corrected: the admission script
requires an SDK jar and compiled production classes; the historical FTPS wiring
script depended on a dev5 commit absent from the shallow checkout. Its unchanged
service/cache/deletion comparisons now pin the verified dev6 base. For changed
CaptureEngine, a separate explicit observation-only projection must recover the
entire dev6 file byte-for-byte, and the FTPS checker invokes that comparison.
An early new test also incorrectly expected `.stage` instead of the existing
`.part` suffix; only the test expectation was corrected. No production recovery
suffix changed. None of these initial failures is represented as a passing run.

### Independent-review correction R1

The first observation-only projection used a broad wildcard. Independent review
showed that injected stop logic inside the removed region could evade that gate;
the production capture code itself contained no such injection. The gate now
requires exactly one complete, frozen literal observation block and exactly one
occurrence of every other permitted addition before projecting back to the dev6
source. Thirteen in-memory negative cases exercise the same production validator,
including the original injected-stop reproduction, queue clearing, changed
polling/timebase, altered sample accounting, route/stop policy, PCM serialization,
and duplicated/deleted additions. Any unexpected code is retained for comparison
or rejected before projection. No production source or resources changed for R1.
