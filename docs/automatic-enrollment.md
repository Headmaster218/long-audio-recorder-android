> Dev9 historical enrollment behavior. Dev10 adds separate default-off [idle verified-transfer local cleanup](verified-local-cleanup.md); originals are retained unless that distinct policy is enabled.

# Opt-in automatic FTPS enrollment (dev9)

This source slice adds automatic queue enrollment, not device-qualified all-day
recording or transfer. Automatic enrollment defaults OFF, including after the
additive SQLite v1-to-v2 migration. Existing queue rows, profiles, credentials,
receipts, pause state and attempt identities are retained.

## Consent and destination

The native Enable confirmation names the saved destination and discloses existing
and future finalized WAVs plus technical segment metadata (capture/run identity,
format, input route, timing/sequence and hashes). Consent persists the exact
profile revision. Saving a different profile never redirects enrollment. Disable
stops new enrollment; already queued transfers remain authorized until Pause.
Pause cancels owned transfer sockets and defers catch-up. Resume is available
even for an empty paused queue. No credentials are created or saved by enabling.

## Enrollment and recovery

A normal chunk enters through PcmSegmentWriter's successful Published callback,
after DirectorySpool.finish has forced, renamed, synced and validated it. The
queue owner re-admits its exact ready ID and compares the complete metadata bytes
before enqueueing. Runtime exceptions in transfer notifications cannot bypass
capture completion or change capture evidence into a failure. A missed event can
be rediscovered on process restart or explicit re-enable.

Enable and process initialization perform one names-only scan, capped at 4,096
entries and 500 not-already-queued candidates. Ordinary chunk notifications and
transfer jobs do not rescan history. Candidates are not upload eligibility.
For each unqueued candidate, the background worker acquires the existing exclusive
spool lock and calls DirectorySpool.confirmReady: content hashes, force every
file, sync directories and validate again. Partial, malformed, symlink, corrupt
or incomplete objects cannot become eligible. One candidate is processed per
one-second-spaced pass, with cooperative ten-second cancellation checks during
hashing. OS reads/fsync calls themselves cannot be forcibly time-bounded.

A narrow process acquisition monitor prevents idle catch-up from racing a new
capture's spool acquisition. Catch-up never steals the capture lock. If recording
already owns it, historical catch-up waits until capture closes; newly finalized
chunks still enroll. A continuous recording session can therefore defer an older
backlog until Stop/Pause. This limitation is visible in transfer status. A recording
start may wait for the current single catch-up confirmation to release admission.
No microphone restart, recovery-session decision or loss-free claim is added.

The unique (source, destination revision) key includes terminal history. Restart
or re-enable cannot duplicate an existing row or create a new remote attempt for
it. A persisted scheduler wake covers outstanding idle catch-up between commits.
On a fresh process, scanning repairs death between publish and queue insertion;
Android force-stop/OEM restrictions may still prevent wakes until app reopening.

## Bounds and data retention

- At most 32 finalize notifications can occupy the worker queue; overflow disables
  enrollment visibly rather than accumulating unlimited callbacks.
- At most 500 queue/history rows and 32 profiles remain the existing limits.
  Capacity includes verified receipts. Capacity/confirmation/discovery failures
  disable enrollment visibly and keep all files and already queued work.
- Catch-up's candidate list is bounded to 500 IDs; no whole WAV is loaded in RAM.
- Existing capture cache limits and STOP_AND_ALERT behavior are unchanged. Nothing
  is evicted, including after verified remote readback. A default 512 MiB spool
  still holds only about 4.66 hours of 16 kHz mono PCM16 before overhead/reserves.
  This slice does not provide all-day retention or automatic deletion.

## Transfer policy remains unchanged

Runnable pending WAV bytes must reach 9,600,000, or the user must tap Upload queued
now. Small tails can wait indefinitely below that threshold, as disclosed in the
Enable confirmation. Charging, unmetered unambiguous Wi-Fi, local-network
permission and trusted TLS still gate every attempt. Automatic enrollment never
requests credentials or permissions in the background. Session-only credentials
still require re-entry after process death; locked/unavailable saved keys block.

The existing durable attempt state machine, complete remote byte/hash readback,
read-only bounded reconciliation and no-overwrite/no-delete behavior remain.
There was no real FTPS connection, permission prompt or credential use in this work.

## Verification and limits

Synthetic host checks:
- 22 new discovery/confirmation assertions: bounded scans, duplicate-known IDs,
  missing/invalid names, symlink root, exclusive ownership, cancellation, corrupted
  bytes, partial rejection, re-open/reconfirmation and retention.
- 12 migration/pinning/idempotence SQL/source assertions. Production DDL executes
  in host SQLite; ContentValues updates are modeled, not Android-executed.
- 13 narrow source contracts cover callback isolation, admission, cancellation,
  notification/candidate bounds, pinned revision, pause/resume and durable wake.
- FTPS admission 533 assertions execute production factory/route methods against
  host API fixtures modeled on Android 29–37, not actual Android runtimes.
- Existing core 7,068; storage 389; export 301; FTPS 868; FTPS queue SQL 27;
  capture anchors 91; capture evidence 129; interruption policy 1,310; recovery
  integration 656; recovery session 325. Independent adversarial cases: core 14,
  storage 11, app 7; app helpers 58. Counts include repeated synthetic checks.

Official installed API-37 SDK plus Java 21 direct unsigned build passed for
versionName 0.1.0-dev9/versionCode 9, min API 29, target API 37. The final unsigned
APK is 117,288 bytes, SHA-256
`c8b17618800872a4e772100719128dd4a8a7ad8980e90807b5220ab537fe6f60`.
Sampled build process-tree RSS was 152.73 MiB. No dependencies were downloaded,
no APK was signed, installed or sent. An intermediate build caught lambdas not
supported by this direct Android bootclasspath; anonymous callbacks fixed it.
An initial admission-test invocation omitted its required SDK argument; the final
run uses the current compiled classes and installed SDK.

The legacy git-dependent FTPS wiring checker cannot complete in this extracted
source candidate (no .git and it refers to a pre-recovery RecordingService).
This is not an aggregate all-checks pass. Export wiring and targeted Java-8 buffer
linkage are separate source/classfile checks, not Android runtime evidence.

Not established: Android SQLite migration/process/power-loss durability,
JobScheduler/permission lifecycle, concurrent UI/capture/transfer timing,
background/OEM/force-stop behavior, microphone quality, real FTPS/TLS/server
interoperability, battery/thermal limits or 24-hour operation. These remain device
acceptance gates; a successful compile or synthetic filesystem test is not proof.
