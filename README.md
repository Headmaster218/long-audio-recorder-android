# Android recorder

Version: **0.1.0-dev6** (explicit-FTPS source candidate; no dev6 APK or release).

Native Android app source with a reviewed Java policy/storage core for a visible recorder.
First device of interest: Xiaomi Mix Fold 4. Generic Android APIs come first;
device-specific workarounds require measured evidence on the actual device.

**Status, 2026-10-09:** dev6 adds one explicit-FTPS backend for individually
selected completed recordings, a retained queue and full remote readback checks.
Scripted JVM protocol checks, host SQLite schema/recovery checks, existing
regressions and official-SDK Java source compilation pass. Independent dev6 review,
real TLS/server interoperability and all Android lifecycle/device validation are
still pending. No dev6 APK was built or signed. This is not accepted continuous
capture or unattended-transfer capability.

The prior dev5 test APK was built from reviewed source `0ed19a14`, with the same
local test certificate as dev4; its later documentation-only commit `e497f32` is
this feature's baseline. That APK does not contain these dev6 changes.

This is an independent Git repository for the Android recorder project.
Public project: [long-audio-recorder-android](https://github.com/Headmaster218/long-audio-recorder-android).

- [Architecture and implementation plan](docs/architecture.md)
- [Official-source findings](docs/research.md)
- [Implemented policy core, integration contracts and test limits](docs/policy-core.md)
- [Independent review, historical failures and final verification](docs/policy-core-review.md)
- [WAV writer/storage development slice and limits](docs/wav-storage.md)
- [Independent WAV/storage review and preserved failure evidence](docs/wav-storage-review.md)
- [Manual SAF export, safety boundaries and verification](docs/manual-export.md)
- [Explicit FTPS candidate, queue and verification boundaries](docs/ftps-transfer.md)
- [Proposed separately authorized local FTPS mock tests](docs/ftps-local-mock-plan.md)
- [Android app behavior, build path and incomplete features](docs/android-app.md)
- [Independent Android review, original failures and final verification](docs/android-app-review.md)

Run the dependency-free core tests with `sh scripts/test-core.sh`. Tests currently
run on JVM 21 with Java 8 source/classfile targets, not on Android or Java 8.
The reviewed core passed 7,068 deterministic assertions and 14 independent
adversarial cases. Assertion counts include repeated checks, not distinct
scenarios. The separate WAV/storage slice has synthetic local-filesystem tests; real
power-loss durability, transfers and phone recording remain untested.

Goals include configurable audio/fragment settings, honest input routing and
gap reporting, a bounded safe spool, and policy-controlled verified uploads.
The default recognition format is 16 kHz mono signed PCM16. A completed day
may upload later in a burst. Calendar date is optional organization metadata;
it does not define capture identity or audio continuity.

Continuous operation is a goal, not a guarantee across Android restrictions,
calls, permissions, process death, battery exhaustion, or OEM behavior.
Never conceal recording or silently discard unverified recordings.

Initial environment check found Git and a Java runtime. It did not find javac,
Gradle, adb, sdkmanager, kotlinc, an Android SDK environment variable, or an SDK
in the common paths inspected. The existing runtime provides the Java compiler
module. A minimal official API-37 SDK/build-tools subset was subsequently installed
and hash-verified for the direct Linux build. No Gradle dependencies were downloaded.

The reviewed WAV/storage slice passed 389 storage assertions, 11 independent
storage adversarial cases and a targeted Java-8 buffer-linkage check. Run
`sh scripts/test-storage.sh` and `sh scripts/test-storage-adversarial.sh`.
These results do not establish full Java-8/Android compatibility, actual
power-loss/process-death durability, sustained performance or 24-hour capture.
The Android source now wires the reviewed storage layer to microphone capture.
Manual SAF export remains implemented. The dev6 FTPS source candidate adds
explicitly selected, policy-gated transfers with readback-at-time receipts.
Immutable server handoff and recording deletion remain unimplemented.

## Android development app

The [native app/service slice](docs/android-app.md) adds a visible microphone
foreground service, bounded capture queue, local WAV spool, runtime permission
flow and basic configuration UI. Independent review of production `86d9269`
passed at `546b317`: 58 helper assertions and seven app adversarial cases in
addition to all policy/storage regressions, four source-wiring checks and a
fresh official-SDK unsigned build. These checks do not run Android lifecycle,
permissions, AudioRecord, notifications or filesystem behavior.

Missing product functions include automatic enrollment of all recordings, SMB,
plain FTP, cellular transfer/quotas, deletion eligibility, retention/deletion,
durable cross-process recording resume, headset input selection and
complete runtime event history. Windows builds, Xiaomi/OEM behavior, UI usability,
screen-off recording, real process/power loss, battery/thermal behavior and
24-hour operation remain untested. A development APK is for explicit testing;
continuous capture is not yet an accepted capability.

## Manual export source candidate

Use the export controls below the recorder controls to load completed segments,
select one WAV and choose a destination in Android’s system file picker. Copying
and SHA-256/byte-count readback run on one background worker with a 32 KiB buffer.
Only immutable `.ready` objects with matching bounded metadata are eligible;
`.part` objects and symlink/path substitutions are rejected. All source files are
kept for verified, unverified, failed and cancelled outcomes.

A successful readback means the selected provider returned the same bytes at that
moment. It does not confirm cloud sync, server durability, SMB/FTP transfer or
safe deletion. Interrupted copies can leave an empty or partial destination.
See [manual export](docs/manual-export.md) for limits and device-test requirements.
Run `sh scripts/test-export.sh` for the new host logic tests.

## Explicit FTPS source candidate

Save a destination, select a completed WAV, and confirm that exact saved
destination. Only these explicitly queued recordings may transfer, while charging
on unmetered Wi-Fi. The fixed trigger is 9,600,000 pending WAV bytes or explicit
Upload queued now. All sources remain, including after verified readback.
There is no automatic enrollment, local/remote deletion, plain FTP or SMB.

Run `python scripts/run-ftps-checks.py` for bounded, offline host/source checks.
The optional official-SDK source compilation requires `--sdk` and
`--resource-java` pointing to existing inputs; it downloads nothing and creates
no APK. See [FTPS limits and evidence](docs/ftps-transfer.md) before testing.
