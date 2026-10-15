> Historical checkpoint description. This working candidate adds default-off service integration; see [current integration status](recovery-service-integration-draft.md) for executed checks and remaining limitations.

# Surviving-service interruption controller: source draft

Status: **UNEXECUTED and UNINTEGRATED**. Neither Java compilation nor host tests,
Android SDK build, installation, microphone use or device tests were performed.
This is a new source slice above the immutable settings reconstruction at
`3f92df3a3047e5fc4553fdfc7f4c974e210de62c`, tree
`091819edb6d507de80056763f409cdc35b423ffb`. It is not restoration of former dev9.
Existing service/capture/storage/FTPS/export source and version bytes are unchanged.
The app still stops on errors until a separately reviewed Android adapter exists.

## Actual policy implemented

`InterruptionRecovery` is a pure Java, synchronized state machine. It uses no
Android classes, filesystem, network, threads, scheduler or AudioRecord. States
are STOPPED, STARTING, RUNNING, BACKOFF and PAUSED. STARTING is deliberately
distinct: receiving a create lease is not evidence that microphone PCM exists.

Construction is stopped and opt-out. A later explicit user action can admit an
initial recorder only when the caller attests that a microphone foreground service
is already authorized. This flag is an adapter precondition, not proof of Android
permission or an API for starting a service. Nothing restores authority from disk.

Each admitted create/use attempt receives an opaque object-identity lease, bound
to one controller generation. The controller keeps ownership through failure and
Stop/Pause until the exact physical recorder has been released, or pending creation
is confirmed cancelled. A stale completion cannot become RUNNING or retire a newer
lease. Generation numbers never wrap. Tokens must never be serialized/recreated.

Automatic retries require explicit per-session opt-in and are limited to observed
microphone contention, unavailable requested route, and dead recorder. Storage,
permission, service-authority and unknown failures are terminal for recovery.
Reasons describe observations: microphone contention is not evidence of a phone
call, and this code never captures call audio. Maximum retries are configurable
from 0 to 8. Delays are exponential, clamped to a configured 100 ms to 300 s range.
The budget covers the entire explicit session; brief successes do not reset it.

One exact Retry object can admit one new lease only after its deadline, old-recorder
release and a fresh authorized-surviving-FGS check. Early, duplicate, foreign or
cancelled callbacks do nothing. Stop and Pause revoke pending work; permission/FGS
authority restoration alone cannot resume it. Opt-out during healthy capture or
the initial explicit start affects future recovery without stopping that capture;
opt-out during a retry cancels the retry and requires release.

There are no automatic boot/background microphone starts, sticky-service changes,
force-stop overrides, wake locks, alarms, worker restarts or permission additions.

## Gap evidence and bounds

The first accepted failure emits OPENED with its observed elapsed-realtime time.
Further failures during the same outage accumulate a bounded reason bitmask and
retain first/last reasons. Only confirmation of first valid attributable PCM emits
RECOVERED with an observed end time. Stopping, exhaustion, opt-out or loss of
authority emits TERMINATED with an unknown gap end (`endMillis = -1`); its separate
`observedAtMillis` says when recovery ended. It never invents zero-length loss.
These are observation boundaries, not exact missing hardware frames, loss counts
or claims of continuous/call recording. They cannot retroactively account for
unobserved silence before detection.

A FIFO outbox has at most `2 * (maxRetries + 1)` events, at most 18. There can be at
most maxRetries recovered outages plus one terminal outage in an explicit session.
Peek does not consume an event; exact FIFO acknowledgement is required. A new
explicit session cannot discard unacknowledged evidence. Persistence failure must
leave events unacknowledged. No automatic eviction or history reset exists.

This outbox is volatile, not a durable gap journal. A future adapter must persist
events with an external process/run identity before acknowledging them: controller
generation numbers alone are not unique across controller recreation/process death.
After abrupt termination, an outstanding gap end may remain unknown. Original PCM,
partial objects, producer accounting and metadata retain their existing authority.

## Required Android integration boundary, not implemented here

1. Serialize controller decisions and physical create/start/stop/release operations
   on one coordinator lane. Recheck the exact current lease immediately before an
   operation; a policy return value by itself cannot fence arbitrary asynchronous
   AudioRecord operations. A stale create completion must dispose its recorder and
   acknowledge that lease, never attach it to a newer run.
2. Keep at most one actual AudioRecord owner. Drain/account already accepted PCM
   through the existing writer. Never replay a whole failed append or discard a
   partial fragment to make a retry pass. Require release acknowledgement; a stuck
   release must fail visibly rather than authorize an overlapping recorder.
3. Map recording/routing callbacks to conservative reasons. Re-enumerate the
   explicitly chosen input policy after reconnect; do not silently switch devices.
   Confirm recovery only after valid attributable PCM, with new run/epoch boundary
   and honest seam uncertainty. PCM partitioning and wire bytes need review gates.
4. Keep the appropriate, visible microphone FGS only within the already authorized
   user session and a bounded recovery wait. Recheck OS permission and service
   authority before every attempt. If a fresh foreground launch is legally blocked,
   ask for an explicit visible user action rather than starting from the background.
5. Persist gap events off the producer/UI lane under an independently reviewed
   bounded journal protocol. Crash/start reconciliation must not resurrect old
   recording authority or claim an exact gap end from wall-clock time.

Current RecordingService tears down after CaptureEngine errors. Connecting this
controller requires deliberate lifecycle changes and device acceptance; merely
adding it to the core source set does not enable recovery in the app.

## Finite tests prepared, not executed

`InterruptionRecoveryTest` and `scripts/test-interruption-recovery.sh` cover:
default opt-out and authority; the recoverable-reason whitelist; physical-release
barrier; early/duplicate/stale/foreign callbacks; all budgets 0..8; capped delay;
no reset after brief success; repeated causes in one gap; maximum FIFO occupancy;
exact acknowledgements; cancellation in initial, running, backoff, released and
retry states; opt-out; unknown terminal gap ends; clock and generation overflow;
and 16 bounded Stop-versus-retry races. They exercise policy objects only.

Source review found that authority loss during BACKOFF or failed retry admission
terminated correctly but omitted that cause from the gap reason flags. The first
checkpoint is preserved separately. Both routes now accumulate SERVICE_AUTHORITY_LOST
in the same open gap; dedicated fixture assertions cover the two routes. These
assertions remain unexecuted and do not turn source review into a runtime result.

The shell script is supplied for a separately approved execution gate. It compiles
only these two Java files using the Java compiler module and small JVM limits,
into a fresh output directory. It downloads nothing. No PASS result or assertion
count is claimed while execution is prohibited. Independent source review and
later compilation/test execution are required before integration.

Real acceptance must include phone/VoIP contention, route loss/reconnect, repeated
dead-recorder recovery, Stop/Pause races, locked screen, Doze/battery saver, revoked
permissions, task-manager Stop, force-stop, process death, reboot and partial-file
integrity. Record OS/device build, observed gaps, battery/thermal/storage behavior
and sustained 24-hour outcomes. Xiaomi-specific supported settings are secondary;
no setting is silently granted or assumed effective.

512 MiB still holds only about 4.66 hours of default raw PCM before overhead.
Automatic transfer enrollment and safe retention remain separate requirements;
this controller does not prevent a full cache from stopping recording.

## Official platform constraints checked for the design

- [Microphone FGS requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)
  and [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start):
  while-in-use permissions restrict fresh background/boot microphone starts.
- [Audio-input sharing](https://developer.android.com/media/platform/sharing-audio-input):
  capture can be policy-silenced, routes can change, and calls receive priority.
- [Force-stopped package state](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state)
  and [foreground-service Task Manager Stop](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping):
  respect user stopping; abrupt stop may provide no callback.
- [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby):
  background CPU/network work is constrained; foreground service/wake locks are
  not a general bypass or an unconditional all-day-survival guarantee.
