# Independent first Android app review

Frozen source: `345bbf675388c210237f3cd91e9763d846dd23dc`, reviewed 2026-10-09.
Scope: Android source/security/lifecycle review, existing unsigned SDK-build
evidence, and bounded logic/build checks. No signing, installation, emulator,
device access, keys, real microphone input or 24-hour runtime test.

**Final review result:** the two source blockers below were corrected at
`86d926938863a7ddcd23ea53227593dda6e4ef0b`. Independent helper tests, integration
source checks and an official-SDK unsigned rebuild pass. Original failure traces
remain preserved. This is not device/runtime acceptance or a signed release;
see the final independent receipt below.

## Original findings: two behavior corrections required

### 1. Persisted pause state can authorize stale epoch reuse after process death

`RecorderState.starting()` sets the persisted `active` flag but leaves a prior
`paused=true` value intact. `RecordingService.startRequested()` consults that
persisted pause value without checking whether it belongs to a clean pause in
the current process.

Deterministic source trace:

1. A clean pause stores capture C, next epoch 1, paused=true, active=false.
2. Explicit resume starts capture C/epoch 1 and writes active=true, but leaves
   paused=true. It may already have written files for epoch 1.
3. Process death resets static fields. On the next explicit Start, the service
   sees paused=true and reuses capture C/epoch 1 for another run.

New run UUIDs and spool sequences prevent direct file overwrite, but they do not
make that reused epoch a fresh monotonically allocated boundary. Merely clearing
the preference asynchronously does not establish a reliable cross-process resume
protocol. Use a consumed clean-pause token scoped to this process, or implement
a verified durable identity/recovery protocol. A new process must not infer a
safe resume from stale SharedPreferences.

### 2. Storage errors can omit a warning for the in-progress PCM block

`CaptureEngine.run()` removes a block from `ready` before appending it. Its
`finally` returns that block to the free pool even when the append fails. The
outer catch includes the uncommitted-RAM warning only when `ready` is nonempty.

Deterministic source trace: one 2,048-sample block is dequeued, its storage append
fails, and the queue is otherwise empty. The current block can be uncommitted,
but `ready.isEmpty()` selects an empty warning suffix. Some prefix may actually
have reached staging, so this must be reported conservatively as possible
uncommitted data, not an exact measured loss.

Count positive AudioRecord reads and successfully acknowledged writer appends,
or otherwise explicitly track the in-progress block. Reconcile after the producer
has stopped so held, queued and in-flight data are included. Do not rely only on
the queue's size or claim a precise hardware-loss count.

Run the preserved source-trace reproductions with:

```sh
python3 scripts/reproduce-app-review-baseline.py
```

This deliberately reads the frozen Git revision. Its deterministic trace and
structural assertions are source evidence, not Android execution, framework
simulation, or a device test.

## Notification dismissal: clarify behavior, do not invent a stop requirement

The initial review considered terminating capture on notification dismissal.
That recommendation was withdrawn: an ordinary swipe is not explicit Stop,
microphone-consent revocation, or evidence that Android requires recording to
end. Automatic stop-on-dismiss would silently weaken the requested continuous
recording behavior.

Android 14 allows users to dismiss notifications built with `setOngoing(true)`;
the development docs must not imply that the notification remains permanently
non-dismissible. Preserve explicit Stop/Pause controls and normal permission
handling; explain that dismissing the notification alone does not stop capture,
and do not force a repost loop. [Android 14 behavior changes](https://developer.android.com/about/versions/14/behavior-changes-all).

Android separately exposes microphone-use indicators on supported Android 12+
devices and an Active apps Stop workflow for foreground services on Android 13+.
The latter kills the app without a cleanup callback, so preserved files and a
fresh explicit run remain the recovery basis. [Microphone indicators](https://developer.android.com/training/permissions/explaining-access),
[foreground-service Stop behavior](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping).

These are documented platform behaviors, not observations from this app on a
device. No rule requiring termination merely because the notification was
swiped was established by this review.

## Other reviewed boundaries

- The launcher Activity is the sole exported component. RecordingService is
  non-exported and declares the microphone foreground-service type. The manifest
  requests only microphone, notification and the two foreground-service
  permissions. No network, external-storage, boot, accessibility, phone-state,
  battery-exemption or manual wake-lock permission is present. Backup is disabled.
- Start originates from an explicit visible Activity action, checks microphone
  and applicable notification permissions, and does not automatically start from
  Activity creation or recreation. The service returns START_NOT_STICKY.
- Pause/Stop actions use immutable PendingIntents. Task removal does not secretly
  restart capture; process death has no promised cleanup callback. Notification
  permission/channel disabling is checked during capture under the app's current
  documented policy.
- The capture and storage threads share sixteen reusable blocks through bounded
  queues. AudioRecord reads are nonblocking; queue exhaustion interrupts instead
  of overwriting queued audio. The writer thread owns spool operations.
- One AudioRecord persists across ordinary file cuts. Positive short-array PCM16
  reads are serialized explicitly as little-endian bytes. Actual client format,
  route and physical-format descriptors are inspected; unknown client attribution
  stops. Observed changes produce uncertain epochs. This does not measure the
  exact sample of an asynchronous route transition or prove no driver overrun.
- Cache checks occur before file growth; storage errors preserve existing files.
  No upload, export, eviction or actual deletion adapter exists in this slice.
  UI configuration bounds rate/channel/duration/cache values and rejects a cache
  ceiling unable to fit a complete selected segment plus reserves.
- Android directory forcing uses public OS APIs and checks the opened descriptor
  is a directory. This builds on the independently reviewed root/parent sync and
  atomic-publication contract; real Android filesystem behavior is still untested.

## Build and acceptance limits

The author supplied an offline SDK build using the official API-37 android.jar
as the explicit bootclasspath, aapt2, D8 and zipalign. The unsigned artifact at
`app/build/direct-1791565691715649576/recorder-unsigned.apk` is recorded with SHA-256
`5e529ac980526a08354f97e0d09a610a127518cbd0a5593a80d3e3ea6522bdde`.
The packaged manifest reports min API 29, target API 37 and the same component/
permission boundaries. This original author build is not an independent device
test. A corrected-source independent build and logic regression evidence should
be appended after the two fixes, preserving the original findings above.

Still required: actual permission/dialog timing, repeated controls, Activity and
Service lifecycle, process-death/force-stop, notification dismissal, device route
changes, microphone privacy/silencing, screen-off recording, real disk-full and
crash durability, performance, battery/thermal behavior, and sustained capture.
No signed/installable release, Android runtime acceptance or all-day recording
claim follows from source review or an unsigned SDK build.

## Final independent receipt

Production snapshot tested: `86d926938863a7ddcd23ea53227593dda6e4ef0b`.
Original independent findings: `f10027439164ef100bdf6eee57dfa3bc53884242`.
No production source was changed by the reviewer after this snapshot.

### Corrected behavior and integration

- The service obtains resume authority only by consuming the process-local
  `CleanPauseGate` held by RecorderState. It does not read SharedPreferences to
  authorize capture/epoch reuse. New process state starts with no token. Explicit
  Stop, errors and starts clear the capability; a failed start cannot reuse an
  already consumed token. Persisted paused=false is supplementary, not the fence.
- Every positive AudioRecord read is accounted before route attribution or
  enqueue can fail. A block is acknowledged only after the whole writer append
  returns successfully. Final reconciliation is after producer termination, and
  the conservative warning is prepended so bounded status cannot truncate it.
  Held, queued, in-flight and partial-frame sample counts are covered. Overflow
  retains an unknown-count warning even when saturated numeric differences are
  zero. This accounting does not measure driver losses or prove durable samples
  after a real power failure.
- Notification swipes continue recording as agreed. The Activity and development
  documentation now explain dismissibility; no stop-on-dismiss, active-notification
  disappearance stop, or forced repost loop was introduced.

### Logic and source checks

`sh scripts/test-app-review.sh` compiles the production core/helpers and all
JVM tests once, then executes these suites:

```text
CoreTest: 7068 assertions passed
AdversarialCoreTest: 14 named cases passed
StorageTest: 389 assertions passed
AdversarialStorageTest: 11 named cases passed
AppRunSafetyTest: 58 helper assertions passed
AdversarialAppRunSafetyTest: 7 named cases passed
```

The seven new independent cases cover process-boundary and one-shot tokens,
revocation/invalid inputs, two simultaneous token consumers, empty-queue
in-flight plus held partial-frame accounting, atomic rejection/full drain,
saturation preserving uncertainty, and concurrent read/ack updates. These
execute actual production helpers on JVM 21; they do not simulate or execute
Android Activity, Service, permissions, notifications or AudioRecord.

`python3 scripts/check-app-review-wiring.py` also passes its four narrow source
checks: resume authorization, accounting call order/reconciliation, notification/
foreground-start policy, and manifest boundaries. The targeted NIO linkage check
passes against the Android-bootclasspath production classfiles. Source assertions
and a targeted linkage check are not a complete Android API/runtime verifier.

### Independent unsigned SDK build

The independent build used the installed official API-37 SDK and build-tools
37.0.0, with an explicit Android bootclasspath and minimum API 29. Resource
compilation/linking, javac, D8, APK assembly, zipalign verification and packaged
manifest/badging inspection completed successfully. No fake platform stubs,
host-JDK fallback for production compilation, dependency downloads, signing
keys, emulator or installation were used.

Independent output:

```text
app/build/direct-1791567407065995529/recorder-unsigned.apk
43597 bytes
SHA-256 f633757f8612b67d89dde66e77e44e92f2bdf80941e460aa4c11fcccb20001b2
```

The corrected author artifact was independently rehashed:

```text
app/build/direct-1791567111804337549/recorder-unsigned.apk
43597 bytes
SHA-256 d864989d28571ce3e8c5ea76f203c2d13b8fc1cbe45c6c34bdddde5c6624890a
```

All four uncompressed APK member names and content hashes match between the
author and independent builds. The `classes.dex` ZIP entry timestamp differs,
so these are content-equivalent builds, not byte-for-byte reproducible archives.
Both checks found no META-INF signing entries or APK signing-block magic. Neither
artifact is presented as signed or installable. Build directories preserve the
manifest dump, alignment result, generated classes/dex and build receipt.

### Resources and stopping point

All build/test processes inherited CPU 6/nice 19. The independent build sampled
131.80 MiB observer-plus-child RSS; the consolidated regression run took 5.43
seconds and sampled 117.25 MiB. The 256 MiB sampled process-tree cap, 24 MiB
generated-disk cap, 2 GiB host-memory headroom, and main-project free-disk floor
plus reservation remained guarded. No guard triggered. The build completed
before its 17:40 UTC deadline. Worktree size after verification was 6,940 KiB;
build output remains well below the generated-disk limit. These are sampled
measurements, not kernel-enforced peak guarantees.

No test/compiler/build process remained after completion, and CPU 6 was released.
`git diff --check` passed. No identified source blocker remains open within this
limited review. Permission/lifecycle/device/filesystem/thermal/battery and
continuous 24-hour recording acceptance remain unperformed, as listed above.
