# Actual CaptureEngine host flow fixture

Use a JDK with its compiler module. No Android SDK,
network, phone, credentials, server, or external libraries are used. Each run compiles into a fresh ignored build directory and retains synthetic evidence. Latch waits and joins have five-second
failure bounds; correctness does not depend on sleep durations.

## Production code under test

Run `sh scripts/test-capture-engine.sh` in assembled source. The runner compiles
the actual core sources plus CaptureEngine, InputDevices, RecordingSettings and
SpoolAdmission in that assembly, not a copied production snapshot. It retains
synthetic WAV/manifest evidence under `app/build/capture-engine-tests/`.

The real engine starts its real producer worker. The real DirectorySpool and
PcmSegmentWriter serialize data, sync files/directories, publish manifests, and
reconcile PCM accounting on the host filesystem. Assertions inspect actual
published closure metadata, frame counts, WAV lengths and every PCM payload byte.

## Scripted boundaries and limits

`app/src/testFixtures/capture-engine/android/` supplies intentionally narrow Android API stand-ins: one
fixed mono phone microphone, finite positive reads then zero reads, no routing
or configuration callbacks, unavailable hardware timestamps, and selectable
configuration/stop/release failures. Clocks use monotonic host time.

`HostAdapters.java` replaces app plumbing outside the capture flow: permissions
are always granted; transfer notifications are no-ops; retirement floors are
empty; export admission is unblocked. Its directory protocol delegates to real
NIO durability operations and provides a one-shot latch to hold initial stage
creation before append. RecorderState is an in-memory observation sink.

RecordingService itself is NOT exercised. These tests do not establish Android
notification, permission, OEM routing, hardware release, real-time latency,
all-day recording, or FTPS behavior. A simulated release return/exception proves
only the engine's handling of that return/exception, not physical mic release.

## Seven flows

1. Normal positive PCM append then explicit STOP: one STOP manifest and exact PCM.
2. Positive read then unavailable active configuration: 32 unconfirmed samples,
   observation and accounting failures, no invented file/first-PCM event.
3. Writer gated before its first append while two blocks are accepted. Request
   Pause, Stop, then Pause. Release gate: both blocks drain, STOP wins, no pause.
4. AudioRecord.stop throws: release still attempted, interruption manifest.
5. AudioRecord.release throws: uncertain ownership retained, interruption manifest.
6. Both cleanup calls throw: both failure bits retained, one completion.
7. Another normal recording after preceding runs: process spool admission is reusable.

Each completed flow requires producer termination, storage-worker termination,
exactly one typed completion, and confirmed spool closure. Failure cases verify
ownership gating and reject clean-pause status. No production bug was reproduced.
