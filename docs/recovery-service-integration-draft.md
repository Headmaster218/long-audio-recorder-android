# Experimental surviving-service recovery integration

Unpublished source candidate based on `86c2fc3b10ee9366076d5a20361bb52694ae23de`.
It compiles against official Android 37.0 and has finite host tests. Android
Service/Handler/AudioRecord execution, installation and real-device acceptance
remain untested. No continuous/all-day recording claim or release is made.

## User-facing behavior

- A visible checkbox opts into experimental recovery for the next explicit Start.
  It starts unchecked, is not saved/restored, is captured with the pending Start,
  and resets after use. Saving existing recording defaults does not enable it.
- Only the already authorized, surviving microphone foreground service can retry.
  No receiver, alarm, WorkManager, sticky restart, background service launch,
  wake lock or new permission is added. System/user process termination creates
  no automatic restart authority. Permission restoration does not restart capture.
- At most eight retries per explicit session, delays 1, 2, 4, 8, 16, 30, 30, 30
  seconds. Brief successful capture does not replenish the budget. These delays
  are minimum backoffs, not guaranteed wall-clock scheduling while asleep.
- Stop/Pause cancel the exact scheduled retry and invalidate the generation.
  First-PCM callbacks cannot resurrect cancelled capture. Pausing a failed/backoff
  session does not create a clean-pause resume token.
- The input remains the requested built-in microphone. Startup errors are not
  automatically labelled microphone contention. Policy silence is only an Android
  observation, not evidence of a telephone call or permission to capture calls.

## Ownership, storage and uncertain PCM

`RecoveryGate` checks the complete failure bitmask, producer termination and
independent release/writer/spool evidence. NO_HANDLE is insufficient if the
producer is not terminated. THREW/UNCONFIRMED release never admits another recorder.
Storage uncertainty/failure also blocks replacement. A process-local static
session barrier survives service-object recreation; it is not a durable lease.
Process death relies on Android process/resource cleanup, without a claim about
native cleanup timing or a persisted permission to resume.

Finite unconfirmed read samples alone do not permanently forbid recovery when
release/storage are known safe and the observed failures are restricted to policy
silence, unavailable route, dead recorder and PCM accounting uncertainty. Overflow
or other failure bits remain terminal for automatic recovery. Preserve every source
file; record the counters as an upper bound on unconfirmed samples, not exact loss.
Never replay a possibly staged prefix. The replacement uses the same capture ID,
a new run ID and a new RESTART epoch with an uncertain seam. Clean pause additionally
requires actual clean engine completion, no failures and fully confirmed samples.

## Journal contract

One background journal operation is outstanding at a time. Retry admission waits
for both the retired-run record and the FIFO gap records to be durably acknowledged.
Records include session/capture/run identities, failure bits, release/closure state,
read/confirmed/unconfirmed counters, next epoch and first-appended observation.
Controller times are callback observations; physical gap endpoints remain explicitly
unknown. A first-appended observation is not proof of audible content or continuity.

Each authored record is at most 4 KiB. At most 128 ordinals are allowed per session;
the directory admits at most 512 data files and 2 MiB aggregate retained bytes.
File fsync, no-overwrite atomic publication and directory fsync must return before
acknowledgement. Failures keep the event unacknowledged and retain partial bytes.
Journal-full/failure stops recovery visibly; nothing evicts old evidence silently.

A partial for the exact active session/ordinal blocks that record. Unrelated
historical partial, unknown-name or oversized regular entries remain untouched,
count against global quotas, and produce a visible incomplete-history warning.
The 4 KiB bound governs newly authored records; oversized retained entries are
bounded by the directory total and are not parsed as trustworthy history. This is
not comprehensive validation/reconciliation of every historical ready record.
Same-target replay is idempotent only for exact bytes and successful directory sync.

## Verification and remaining gates

`scripts/test-recovery-integration.sh` passes 656 finite host assertions covering
release/producer combinations, every failure category and mixed masks, finite
uncertain counters versus overflow, independent closure gates, clean-pause
eligibility, no-overwrite/idempotency, every write/sync/publish fault, same-ordinal
partials versus unrelated retained partials, oversized history warnings, quotas,
and controller evidence acknowledgement plus stale/Stop callback rejection.

Capture evidence (129), interruption policy (1,310), anchor (91), export (301),
FTPS (868), core (7,068), storage (389), app helper (58), and the existing
14/11/7 adversarial case suites passed at their recorded candidate checkpoints.
The targeted Java-8 ByteBuffer linkage check passed. Official SDK compilation
includes all production sources and generated R. Existing FTPS deprecations remain.
These tests do not execute the Android service event loop. Historical exact-source
projection checks still need explicit review/rebaselining; no aggregate release
gate is claimed.

Before enabling by default or distributing: independent source review; executable
service/worker ordering fixtures; platform permission/visibility/route tests;
Stop/Pause during creation, active read, journal write and retry; system task-manager
Stop, force-stop, process death/reboot; locked-screen/Doze and actual phone/VoIP
contention; on-device storage durability and long-run thermal/battery qualification.
The existing 512 MiB default cache and manual transfer enrollment still limit
continuous use independently of interruption recovery.

Official constraints:
- https://developer.android.com/develop/background-work/services/fgs/service-types#microphone
- https://developer.android.com/media/platform/sharing-audio-input
- https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping

## Reproduce the source compilation without publishing SDK files

Development source metadata is 0.1.0-dev8 / versionCode 8 in VERSION, Gradle and
the direct-build manifest. No new installable APK accompanies this checkpoint. The earlier pinned capture checkpoint's
full-source compile and focused tests are separate from this modified candidate.

Official metadata: https://dl.google.com/android/repository/repository2-3.xml

- Platform 37.0 revision 2 archive: https://dl.google.com/android/repository/platform-37.0_r02.zip
  67,281,901 bytes; SHA-1 ed8ebf7f8822a4de5686d427f237d2fa30ff7410.
  Extract android-37.0/android.jar only (43,044,472 bytes).
  Jar SHA-256 bf1b4387cc7ca94fc6ef684f040d9d16fbf16248e181819f020736ea2053f177.
- Linux Build-Tools 37.0.0: https://dl.google.com/android/repository/build-tools_r37_linux.zip
  66,135,704 bytes; SHA-1 70954e99f4c3d9d46ee70fa32624672fe7cd6ebe.
  Extract android-37.0/aapt2 only (6,274,200 bytes).
  Binary SHA-256 0f1c0e560f99db510a76ebc99ad5c3e68438f62e8e8617018a716ca4a589a699.

Both identify android-sdk-license. Provision only with applicable authorization
and agreement handling. SDK binaries and archives are not repository deliverables.
Use aapt2 compile on app/src/main/res, then aapt2 link with the real manifest,
android.jar, minSdk 29 and targetSdk 37, generating R.java and a resource-only
intermediate. Compile every production Java source plus that generated R with
jdk.compiler/com.sun.tools.javac.Main, -source 8 -target 8 -bootclasspath android.jar
and -Xlint:all,-options. A resource-only intermediate is not an application APK.
No D8, package assembly, signing, installation or device operation is needed for
this source check. Tested bounds: CPU 2/nice 19, 256 MiB sampled process RSS,
120 seconds per bounded sequence, with small JVM heaps and at least 5.5 GiB free disk.
