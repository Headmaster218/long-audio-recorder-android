# Android recorder

Version: **0.1.0-dev** (design-only development seed).

Research and design seed for a user-visible, long-running Android recorder.
First device of interest: Xiaomi Mix Fold 4. Generic Android APIs come first;
device-specific workarounds require measured evidence on the actual device.

**Status: specification only, 2026-10-09. No APK, application code, SDK,
Gradle wrapper, downloaded dependencies, build, or device validation.**
This is an independent Git repository for the Android recorder project.
Public project: [long-audio-recorder-android](https://github.com/Headmaster218/long-audio-recorder-android).

- [Architecture and implementation plan](docs/architecture.md)
- [Official-source findings](docs/research.md)

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
in the common paths inspected. No tools were installed.
