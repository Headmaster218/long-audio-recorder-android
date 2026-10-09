# Android recorder

Version: **0.1.0-dev2** (policy-core development seed).

Research, design and Java policy core for a user-visible, long-running Android recorder.
First device of interest: Xiaomi Mix Fold 4. Generic Android APIs come first;
device-specific workarounds require measured evidence on the actual device.

**Status: Java policy/metadata core and deterministic JVM tests, 2026-10-09.
No APK, Android application implementation, SDK, Gradle wrapper, downloaded
dependencies, Android build, or device validation.**
This is an independent Git repository for the Android recorder project.
Public project: [long-audio-recorder-android](https://github.com/Headmaster218/long-audio-recorder-android).

- [Architecture and implementation plan](docs/architecture.md)
- [Official-source findings](docs/research.md)
- [Implemented policy core, integration contracts and test limits](docs/policy-core.md)
- [Independent review, historical failures and final verification](docs/policy-core-review.md)

Run the dependency-free core tests with `sh scripts/test-core.sh`. Tests currently
run on JVM 21 with Java 8 source/classfile targets, not on Android or Java 8.
The reviewed core passed 7,068 deterministic assertions and 14 independent
adversarial cases. Assertion counts include repeated checks, not distinct
scenarios. Real durable storage, transfers and phone recording remain untested.

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
