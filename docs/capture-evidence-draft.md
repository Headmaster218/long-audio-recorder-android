# Capture completion evidence: source-only WIP

Status: **UNEXECUTED, uncompiled, no Android/device validation.** This is a small
contract change on parent `fb448c2dd9fe876b76344a8551d13bf231aa70f3`.
It does not connect `InterruptionRecovery` to `RecordingService`. Version remains
`0.1.0-dev7`. No APK or claim of working automatic recovery accompanies it.

## Why this contract is needed

The existing engine has two workers and an explicit-stop service. Its first-error
string could hide a subsequent `AudioRecord.release()` failure. A producer thread
ending therefore did not establish that its microphone handle was released.
Initialization could also fail after acquiring a handle but before returning it,
and a failed `Thread.start()` left a non-null, unstarted producer whose handle was
not released by the old `producer != null` cleanup branch.

The contract now records those facts independently. The current service still
receives its existing text/paused/next-epoch callback and stops. It does not retry,
hold a new recovery timer, start in the background or override user cancellation.
The only new default-visible output is an explicit physical-release warning if
release threw or was not confirmed. Existing accounting warnings remain first.

## Typed outcomes

`CaptureEvidence` is a bounded, per-engine, synchronized accumulator. Its final
immutable result binds `captureId` and fresh `runId` and includes:

- The first observed failure plus a bit set of **all recorded failure categories**.
  Later storage, accounting, stop, callback-cleanup and release errors cannot be
  hidden by an earlier route loss, dead recorder or policy-silence error.
- Release: `NO_HANDLE` means no `AudioRecord` handle returned from the builder to
  this engine; it says nothing about allocator internals. `UNCONFIRMED` means a
  handle was obtained but no normal release return was recorded. `RETURNED`
  records a normal `AudioRecord.release()` return. `THREW` records a release
  exception and stays uncertain; it is never upgraded by worker termination.
- Writer and spool closure separately: `NOT_OPENED`, `UNCONFIRMED`, `CLOSED`.
  Attempting construction sets uncertainty before construction. Writer close
  means `stop()`/`interrupt()` returned; spool close means `close()` returned.
  Closing the spool cannot substitute for successfully closing the writer.
- Whether the producer terminated, and the immutable final `PcmReadAccounting`
  snapshot after joining it and completing attempted storage cleanup.
  `allReadSamplesConfirmed()` means the read/append counters reconcile. It does
  not establish finalization, server transfer, hardware continuity or durability.
- The first eligible appended-PCM observation, if one was made.

Failure categories describe observations, not inferred telephony state. Android
policy silence is `POLICY_SILENCED`; it does not prove a telephone call occurred.
Dead recorder and route loss have separate categories. Permission/visibility,
unknown platform exceptions, storage, queue pressure and malformed/uncertain PCM
remain distinguishable. Neither the first category nor successful release alone
is a safe automatic-retry decision.

Only normal Java exceptions are categorized. Process death, native failure or
fatal VM errors can prevent a final callback altogether. Missing evidence is not
success. Results and first-PCM events are volatile, not a crash journal.

## First appended PCM

The engine offers a default no-op `Completion.firstPcm(engine, observation)` hook.
It is emitted at most once on the storage worker, after `writer.append()` returns
and `accounting.appendConfirmed()` succeeds. It carries no PCM buffer or samples,
only identity, sample count/channels, read ordinal, the observed read-time interval
and append-observation time in elapsed-realtime nanoseconds.

Policy-silenced, unexpected/uncertain-route, incomplete-frame or regressed-clock
blocks do not qualify. Existing PCM bytes/anchor collection and preservation are
unchanged, including preservation of ineligible blocks. A later eligible block
can be the first event. After emission the engine avoids additional event clock
queries. The event is not emitted at `startRecording()`.

An earlier good block can finish appending after Stop or a producer failure. The
event records that earlier observation; it is **not** permission to revive a run.
A future consumer must bind the exact engine, lease and generation, check its
current cancellation state and reject stale callbacks. The append time is not a
sample-exact end of a hardware gap and the content is not proven audible.

The hook must be nonblocking and constant-space. No disk/network work belongs in
it. A runtime exception from a newly installed first-PCM observer is recorded and
interrupts the run conservatively; the current no-op observer cannot throw.
An override of the typed final `Completion.finished(...)` receives the frozen
evidence. Its default adapter forwards to the unchanged legacy callback once.

## Cleanup and preserved behavior

One release helper records normal return versus exception. Both producer cleanup
and initialization cleanup use it. The caller releases a returned handle when the
producer did not start; otherwise it joins the producer, which owns release.
There is no second release attempt after an exception, no ownership grant to a
replacement recorder and no timeout that pretends a blocked worker has stopped.
The existing wait-for-producer behavior remains and can wait indefinitely.

Initialization validation records its typed cause before attempting release.
Normal release cannot erase a prior stop/callback failure. All storage and
accounting causes remain in the final result even if the text keeps the first
human-readable error. No raw source files are deleted or replayed.

`RecordingService`, settings/UI, PCM slicing, WAV/manifest/anchor codecs, spool
publication, export and FTPS are unchanged. No periodic scan, capture-thread I/O,
additional PCM copy, queue expansion or automatic restart is introduced. The
service still drops its engine reference and exits through its existing callback.
It does not yet gate a later manual start on uncertain physical release. A future
service ownership change must address that explicitly before claiming recovery
ownership safety across runs.

## Prepared finite fixtures; none executed

`scripts/test-capture-evidence.sh` is a prospective focused host entry point. It
compiles only `PcmReadAccounting`, `CaptureEvidence` and `CaptureEvidenceTest`,
then performs narrow source-order checks. It requires a permitted Java compiler
module and Python 3, but downloads nothing. External execution limits must be
approved before invoking it; this checkpoint provides no pass/assertion count.

Prepared cases cover no handle, obtained-but-unreleased handles, release failure
after every primary category, initialization rejection, not-started producer,
stop/callback cleanup failures followed by successful release, writer/spool
closure separately, unconfirmed samples and counter overflow, one eligible
first-PCM observation, ineligible blocks, exact run/timing identity, malformed
observations, terminal immutability and duplicate/phantom acknowledgements.
Source-order fixtures cover append-before-event, join/close/snapshot/final order,
normal-versus-exception release, unstarted-worker cleanup and the legacy service.
They do not substitute for fault injection into Android's `AudioRecord`.

The existing historical `check-capture-anchor-wiring.py` uses an exact dev6/dev7
source projection and `check-app-review-wiring.py` also constrains older engine
text. Their allowlists have not been updated in this narrow WIP. A future source
qualification must review the precise contract delta and update projection tests
without weakening PCM/anchor protection, then run all legacy regressions. A
plain restored source snapshot also lacks the Git objects those gates require.

Before enabling recovery: compile against the official Android SDK, execute these
fixtures and complete legacy regression inventory, add bounded engine cleanup
fault fixtures, and review a service adapter that waits for released ownership,
closed storage and reconciled PCM before considering a replacement. Durable gap
evidence must be persisted before acknowledging the controller's volatile outbox.
Permission, input contention, route changes, screen-lock/Doze, user Stop/Pause,
process death and vendor behavior still require real Android validation.

Official constraints remain applicable:

- [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)
- [Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input)
- [Microphone foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)
- [Foreground-service background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

This contract does not record calls, grant microphone permission, introduce a boot
receiver, bypass force-stop or promise uninterrupted/all-day recording.
