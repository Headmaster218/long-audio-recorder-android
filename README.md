# Android recorder

Version: **0.1.0-dev4-source** (unsigned Android app candidate).

Native Android app source with a reviewed Java policy/storage core for a visible recorder.
First device of interest: Xiaomi Mix Fold 4. Generic Android APIs come first;
device-specific workarounds require measured evidence on the actual device.

**Status: native Android Activity/microphone service source and an unsigned SDK-built APK,
2026-10-09. Core/storage JVM regressions pass. The app remains unsigned, uninstalled,
not device-tested and pending independent app review. No Gradle/runtime dependency
or signing key was downloaded.**
This is an independent Git repository for the Android recorder project.
Public project: [long-audio-recorder-android](https://github.com/Headmaster218/long-audio-recorder-android).

- [Architecture and implementation plan](docs/architecture.md)
- [Official-source findings](docs/research.md)
- [Implemented policy core, integration contracts and test limits](docs/policy-core.md)
- [Independent review, historical failures and final verification](docs/policy-core-review.md)
- [WAV writer/storage development slice and limits](docs/wav-storage.md)
- [Independent WAV/storage review and preserved failure evidence](docs/wav-storage-review.md)

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
in the common paths inspected. The existing runtime does provide the Java
compiler module used by the core test script. No tools were installed.

The reviewed WAV/storage slice passed 389 storage assertions, 11 independent
storage adversarial cases and a targeted Java-8 buffer-linkage check. Run
`sh scripts/test-storage.sh` and `sh scripts/test-storage-adversarial.sh`.
These results do not establish full Java-8/Android compatibility, actual
power-loss/process-death durability, sustained performance or 24-hour capture.
No Android recording implementation, upload adapter or deletion adapter is included.

## Android app candidate

The new [native app/service slice](docs/android-app.md) adds a visible microphone
foreground service, bounded capture queue, local WAV spool, runtime permission
flow and basic configuration UI. An offline unsigned APK build is available for
static inspection. It is not signed, installed or device-tested; independent
app review is still required. No signing key or dependency was downloaded.
