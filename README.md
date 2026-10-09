# Android recorder

Version: **0.1.0-dev5** (manual verified-export source candidate).

Native Android app source with a reviewed Java policy/storage core for a visible recorder.
First device of interest: Xiaomi Mix Fold 4. Generic Android APIs come first;
device-specific workarounds require measured evidence on the actual device.

**Status: dev5 manual SAF export, 2026-10-09. Independent source/host review,
synthetic JVM export checks, existing regressions and official-SDK Java source
compilation pass. A local development test APK was built and signed from reviewed
source commit `0ed19a14cdfe99920cf9c69a08d316a90383ba2d`, using the same test
certificate as dev4; no new key was created. It is not a public binary release.
Real Android lifecycle/provider behavior, Xiaomi testing and 24-hour operation
remain untested. This later documentation update was not the APK source.**
This is an independent Git repository for the Android recorder project.
Public project: [long-audio-recorder-android](https://github.com/Headmaster218/long-audio-recorder-android).

- [Architecture and implementation plan](docs/architecture.md)
- [Official-source findings](docs/research.md)
- [Implemented policy core, integration contracts and test limits](docs/policy-core.md)
- [Independent review, historical failures and final verification](docs/policy-core-review.md)
- [WAV writer/storage development slice and limits](docs/wav-storage.md)
- [Independent WAV/storage review and preserved failure evidence](docs/wav-storage-review.md)
- [Manual SAF export, safety boundaries and verification](docs/manual-export.md)
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
Manual SAF export is implemented in this dev5 source candidate. Upload, verified
server handoff and recording deletion remain unimplemented.

## Android development app

The [native app/service slice](docs/android-app.md) adds a visible microphone
foreground service, bounded capture queue, local WAV spool, runtime permission
flow and basic configuration UI. Independent review of production `86d9269`
passed at `546b317`: 58 helper assertions and seven app adversarial cases in
addition to all policy/storage regressions, four source-wiring checks and a
fresh official-SDK unsigned build. These checks do not run Android lifecycle,
permissions, AudioRecord, notifications or filesystem behavior.

Missing product functions include automatic upload, SMB/FTP and verified backend transfer,
retention/deletion, durable cross-process resume, headset input selection and
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
