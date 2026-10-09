# Proposed architecture

Design proposal, not implemented or hardware-validated. See research.md for
platform evidence. SDK/dependency versions must be selected and checked when
implementation is authorized; documentation may include newer/preview APIs.

## 1. User experience and settings

One dashboard: Start, Pause, Stop; actual microphone, elapsed captured time,
known gaps, pending bytes, remaining cache time, network policy, and upload
progress. Recording has an ongoing notification with Stop/Pause actions and
the platform microphone indicator. Explicit microphone permission and a
visible user action precede a new capture service. Request notification
permission and require visible recording notifications by product policy;
Android technically permits starting an FGS without that permission.

Settings, with proposed conservative defaults:

- Audio: 16,000 Hz, mono, signed 16-bit little-endian PCM. Expose supported
  rates/channels with validated actual format. Default source MIC; compare
  VOICE_RECOGNITION or supported UNPROCESSED later using real recordings,
  without promising that processing is absent on every device.
- Fragment: proposed five minutes, configurable in seconds/minutes. Split
  files by frame count while keeping the same AudioRecord running. Do not
  stop/start the recorder at routine file boundaries.
- Input: phone; selected connected input; prefer headset with phone fallback;
  selected input only, pause when unavailable. Default phone until the user
  selects otherwise. Show requested and actual route separately. No promise
  of simultaneous phone/headset capture or seamless automatic switching.
- Spool: configurable maximum bytes plus minimum free-space reserve. Show
  estimated hours at the selected format. Full-spool default: stop recording
  safely and warn visibly. Never evict pending/unverified data automatically.
  A future destructive retention mode must be an explicit separate choice.
- Upload triggers: pending-byte threshold, optional oldest-item age, session
  stop, and explicit Upload now. Triggers do not override network/charging
  constraints or quota. Proposed initial policy: unmetered Wi-Fi, charging,
  cellular disabled. GUI must explain the chosen AND/OR rules clearly.
- Cellular: explicit enable, app-transfer daily/monthly cap, timezone/reset
  rule, roaming policy. It is an app budget, not a carrier billing guarantee.
- Destination: SFTP, SMB3, FTPS; plain FTP compatibility only as a separately
  acknowledged insecure mode. No server or credentials chosen in this pass.

PCM sizing, excluding metadata and filesystem overhead:
16,000 frames/s * 1 channel * 2 bytes = 32,000 bytes/s;
five minutes = 9,600,000 bytes;
24 hours = 2,764,800,000 bytes (2.765 GB / 2.575 GiB).
48 kHz mono PCM16 is three times larger. Reserve space for the current
fragment, journal, temporary conversion, and filesystem overhead.

## 2. Components

Use Kotlin and a small native Android UI when implementation begins.

1. RecordingService: microphone foreground service; owns the capture state
   machine and AudioRecord. Keep disk/network work out of its read loop.
2. RouteController: enumerates input devices, subscribes to device/routing
   and recording-configuration callbacks, verifies actual route, records
   interruptions, and enforces the user's fallback policy.
3. FragmentWriter: bounded in-memory buffer, continuous sample counter,
   append-only PCM staging, checksums, checkpoints, rotation, finalization.
4. SpoolStore: app-private persistent files plus a transactional index.
   Do not store irreplaceable pending audio in Android cacheDir. Reconcile
   orphaned files and records after any interruption.
5. UploadCoordinator: evaluates GUI policies and enqueues unique, bounded,
   resumable WorkManager jobs. Capture does not depend on network availability.
6. TransferAdapter: protocol-specific capabilities, authentication, upload,
   resume, verify, publish, and optional durability acknowledgment.

Capture states: stopped -> starting -> recording; then paused, interrupted,
recovering, storage-blocked, or stopping as needed. Start errors remain
visible. OS/process recovery must not evade foreground-start restrictions.
Reboot recovers local data and can invite the user to resume; it does not
silently launch microphone capture. Respect user force-stop/FGS Stop.

## 3. Audio, route, and time semantics

AudioRecord is the baseline. Request the preferred input, then inspect the
active route; a successful preference call alone is insufficient. Modern
BLE recording has a direct preferred-device path. Classic SCO support is a
separate compatibility spike: communication routing affects shared audio
policy and must not be assumed equivalent to arbitrary recorder routing.
Never switch audio mode to impersonate a phone call just to steal the mic.

Record requested/client/device formats separately. If 16 kHz capture fails,
stop with a clear explanation unless the user enabled adaptive capture.
Adaptive capture uses a supported format and an explicitly recorded,
stateful conversion to 16 kHz mono. Keep resampler state across ordinary
fragment boundaries; reset and mark a discontinuity after a capture restart.
Optional FLAC is lossless for the PCM supplied to its encoder; downsampling
to 16 kHz is a separate lossy transformation. FLAC is a later measured
storage/energy option, not a prerequisite for the reliable PCM baseline.

Proposed versioned per-fragment metadata:

- capture_id: user session identity; remains stable across delayed uploads.
- sequence: persistent strictly increasing fragment number within capture.
- run_id and continuity_epoch: new run after process/AudioRecord recreation;
  new epoch for a route/format change, policy silence, or uncertain seam.
- frame_start and frame_count within that epoch, channel count, sample rate,
  PCM encoding, payload byte length, payload SHA-256. Offsets count frames,
  not individual interleaved channel samples. Optional capture-wide recorded
  frame ordinal is ordering only, never evidence that no audio was lost.
- boot_id/session-generated boot marker; monotonic timestamp anchors from
  AudioRecord where available, including clock domain and uncertainty.
  Do not compare monotonic clocks across reboot.
- wall-clock anchor and UTC offset/timezone, separately from sample time.
  Record clock corrections; calendar dates never establish a seam.
- requested/actual input, source, processing indicators, known silence or
  lost/unknown intervals, reason for closing, recovered-tail status.
- previous fragment ID/hash, continuity assertion, and its evidence.

Only assert a trusted file seam when the same uninterrupted stream/epoch,
format, exact adjacent frame offsets, and absence of observed errors/gaps
support it. Missing evidence yields an uncertain seam, not invented silence
or continuity. An asynchronous route change may have an uncertain boundary;
mark that uncertainty instead of assigning an unjustified exact sample.
AudioRecord sample position resets on stop/start. Backend ordering must use
identity/sequence/epoch, never upload order or a date folder.

Calls, competing apps, mic privacy toggle, permission revocation, route loss,
device errors, and queue overflow produce explicit events. Zero amplitude
alone is not proof of OS silencing; use recording configuration callbacks.
Do not claim ordinary-app access to phone-call audio. On ERROR_DEAD_OBJECT,
finalize salvageable data, create a new run if lawful to resume, and preserve
an interruption record. Do not request phone-state access by default merely
to infer an interruption already reported by the audio APIs.

## 4. Recoverable local writes

Use a single writer. Write complete interleaved frames to an exclusive
fragment-id.pcm.part file. Periodically force data to storage and atomically
update a small checkpoint containing durable frame/byte count and identity.
Checkpoint frequency is a measured durability/battery tradeoff; no promise
of preserving samples still in RAM at abrupt power loss.

On close: drain the writer, force data, compute/finalize hash, atomically
write immutable sidecar, rename within the same filesystem, and commit index
state READY. These are multiple operations, not a pretend cross-file atomic
transaction. Recovery reconciles every possible crash boundary idempotently.
Use AtomicFile for small replaceable metadata; it is not concurrent-writer
locking and is not the streaming audio container.

On restart: discover staged files, compare with durable checkpoints, align
recoverable tails to full frames, rehash, and publish them as recovered with
uncertain tail/gap semantics. Never append a fresh process to the old epoch.
Keep audit events if a partial or corrupt tail must be excluded.

The capture loop never waits on uploads. If disk cannot keep up or the bounded
buffer fills, stop/mark a gap rather than silently overwrite buffered audio.
Reserve enough disk for finalization and a visible storage-full report.

## 5. Transfer and safe deletion

Proposed states: READY -> UPLOADING -> REMOTE_VERIFIED -> REMOTE_COMMITTED ->
LOCAL_DELETE_ELIGIBLE -> LOCAL_DELETED. Persist transitions and evidence.
Index writes/cleanup are retryable; a crash after remote commit must not
overwrite a different object or require a duplicate upload.

All adapters use collision-resistant capture/sequence/hash paths and private
temporary names. Resume only after proving the existing prefix matches this
exact local object; otherwise begin a fresh temporary object. Size equality,
TCP success, a transfer-complete response, or a locally uploaded hash sidecar
alone is not remote content verification.

Required delete gate:

1. Remote payload has expected byte count and SHA-256, established by a
   trusted server checksum facility or authenticated full remote readback.
2. Publish final payload without overwriting conflicting data. Use atomic
   rename only when that server/filesystem capability has been verified.
3. Upload and verify immutable metadata; publish a commit marker last. Readers
   ignore temporary/incomplete objects and require the marker plus hashes.
4. Persist local receipt tying destination/path/size/hash/metadata/commit
   marker to the exact local fragment. Recheck final objects before deletion.
5. Only now release local payload under the selected retention setting.

Content verification is different from survival of a NAS power failure.
Request SMB FLUSH or negotiated SFTP fsync where supported, record what was
acknowledged, and state the server's limitations honestly. A sidecar/rename
is not a universal durable-storage guarantee. If strict remote durability
cannot be established at a destination, retain local audio and show a blocker
rather than silently weakening the deletion policy.

SFTP: authenticate server identity; no accept-all host-key policy. Probe
resume/fsync/rename features. SMB: prefer SMB3 encryption, require appropriate
authentication, and exclude SMB1. FTPS: validate certificates and protect
both control and data channels. FTP: binary mode and probed REST/SIZE support;
never present cleartext FTP as secure. Library selection, licenses, Android
compatibility, and maintained dependency versions remain unreviewed.

Checksums/readback, retries, metadata, and protocol overhead consume bandwidth.
An upload queue may contain a whole day, but transfer in restartable bounded
units rather than forcing one enormous daily file. Date directories are
optional. No custom backend API is required for this file/sidecar contract.

## 6. Scheduling and power

Keep microphone capture and data-transfer lifecycles separate. Never label
uploads as microphone work to avoid dataSync limits. WorkManager is suitable
for deferrable constrained chunks, but neither exact timing nor unlimited
runtime is guaranteed. User-initiated data-transfer jobs may suit a genuine
Upload now action; do not mislabel automatic threshold uploads as such.

WorkManager charging/network constraints plus per-chunk policy checks are
both needed. Wi-Fi and unmetered are separate choices. Observe capability
changes and avoid silently falling back to cellular. For strict Wi-Fi-only,
use sockets bound to the approved Network where supported; cancel on loss.
For a LAN NAS, lack of validated public Internet must not by itself block an
otherwise permitted local transfer. Probe the actual destination. Treat
ambiguous VPN/mixed transports conservatively when cellular is forbidden.

Persist a quota ledger, reserve an upper estimate for the next chunk before
sending, account for retries and verification traffic, and stop before the
configured app budget. Exact carrier accounting cannot be guaranteed by an
ordinary app. Network policy changes mid-transfer must stop/reconcile safely.

Start with system media wake-lock behavior and WorkManager power management.
No indefinite manual wake lock or battery-optimization exemption by default.
Add a tightly scoped manual wake lock only after traces demonstrate a real
need; release it on every stop/error path. Measure screen-off capture, heat,
and battery on the real Mix Fold 4/OS/headset combination before any all-day
claim. Fold/unfold and activity recreation must not restart the service.

## 7. First implementation order and acceptance gates

1. Freeze the versioned metadata and pure policy/state-machine contracts.
   Lightweight deterministic tests: frame arithmetic, gaps/epochs, byte
   budgets, clock changes, cache-full behavior, and safe-delete gates.
2. Build user-visible service + phone MIC + continuous PCM rotation, journal
   recovery, and GUI audio/cache settings. No networking required yet.
3. Device checks: screen off, fold/unfold, calls, competing recorder, mic
   privacy/permission change, Bluetooth connection loss, forced termination,
   reboot, and a deliberately full spool. Audit gaps against known input.
4. Add one secure adapter and failure-injection tests: wrong same-size remote
   data, truncated upload, changed prefix, quota change, disconnect during
   verify/rename/commit, and crashes at each delete-gate transition.
5. Add remaining adapters and headset policies after capability tests. Then
   run extended screen-off/power measurements and optional FLAC comparisons.

No SDK or dependencies were fetched and none of these tests ran in this pass.
Next decisions can wait for implementation: actual phone Android/HyperOS
version, headset profiles, chosen NAS/server capabilities, cache size, quota,
and desired transport. No user question is needed for this research seed.
