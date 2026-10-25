# Bounded Android input-selection overlay

Base: android-threshold-candidate01-20261025 (parent identifies published checkpoint fb5252cb). This directory contains only replacement/new source, test and documentation files. It is not a complete project. No frozen base files were changed. No tests, build, SDK, device, network, permission, signing, install or Git actions were executed by the author.

## Behavior

- A new visible per-Start selector offers phone and enumerated source-capable wired/USB devices. Exact external ID and input kind are checked, not merely “some headset.” Device IDs are connection-scoped, not stable identity credentials. Refresh resets choice and fallback consent.
- Bluetooth, USB accessories outside the allowlist, non-input devices and other routes are explicitly unsupported. No communication mode, SCO, call-audio routing, audio-focus takeover, new permissions or dependencies.
- Fallback is unchecked by default and copied into the immutable permission/start snapshot. Input choices are not persisted in the four-default settings store and are not automatically restored after Activity recreation. Save does not grant input authority.
- Preference acceptance is not route success. Before and after each positive read, the adapter checks the exact observed AudioRecord route. Active-recording-configuration device must agree. Initial null routes, disagreement, preference rejection and unexpected input terminate visibly without an automatic retry. Conservative consequence: a device requiring delayed route settlement may stop even if Android would eventually route it; this phase does not wait, flush, or assume a usable route.
- Selected-only loss ends capture, not a clean resumable pause token. Explicit Start is required. INPUT_POLICY_DENIED is appended to CaptureEvidence.Failure, preserving earlier ordinals; the existing retry whitelist excludes it even alongside route-unavailable or policy-silenced evidence.
- Consented fallback chooses an enumerated built-in mic only when selected input is absent. It is sticky for that AudioRecord run. Experimental automatic recovery creates a distinct run and checks the original request again; this is stated in the UI. No route loss itself is silently retried.
- Metadata stores immutable requested selection/policy separately from observed device and a phone-fallback marker. A pre-first-read or later running-recorder preference change, or observed routing callback, marks the block/epoch uncertain. Zero reads do not consume that baseline. Since Android's native buffer can span multiple reads, sample-attribution uncertainty stays latched for the rest of that run in metadata and read anchors, excluding clean FirstPcm evidence; only the transition opens an epoch, not every subsequent block. There is no exact switch-frame claim.
- Rejected positive reads remain unconfirmed in the existing accounting rather than being credited as written. Existing release, join, foreground visibility, journal, stop/pause/destroy and single-recorder ownership paths are unchanged.

## Root-only integration and verification

Create a fresh candidate from the base, excluding .git and generated app/build, then overlay this directory. Do not modify the frozen base. In the fresh candidate:

1. sh scripts/test-input-routing.sh
2. sh scripts/test-app-review.sh
3. sh scripts/test-capture-evidence.sh
4. sh scripts/test-recovery-integration.sh
5. sh scripts/test-recovery-session.sh
6. sh scripts/test-capture-anchors.sh
7. sh scripts/test-interruption-recovery.sh
8. sh scripts/test-upload-threshold.sh
9. python scripts/build-android-unsigned.py --sdk /workspace/scratch/12ec3121e93f/android-sdk-recovery-20261025 --deadline-utc <root-selected-future-UTC-deadline>

Use the installed official platform 37.0/build-tools 37.0.0 expected by the unchanged build script; no download. Root should retain normal resource/disk guards. Existing old textual wiring scripts may have stale baseline assumptions independent of this overlay; report failures rather than weakening them.

New host tests cover default phone, accepted preference with unknown/wrong actual route, exact wired/USB selection, absent/lost/replaced device, kind/ID mismatch, fallback consent off/on, sticky per-run fallback/replug, phone disappearance, unsupported inputs, preference rejection, initial callback/transition uncertainty, delayed callbacks, zero-read baseline, immutable Intent/permission snapshot, duplicate/stale/canceled permission callbacks, failure ordinal compatibility and recovery denial despite route/silence flags and unconfirmed PCM. Existing recovery/session tests supply unchanged pause/destroy, stale engine callbacks, release and forced-silence coverage. This is policy and lifecycle-model coverage, not actual Android adapter execution or hardware proof.

## Remaining device proof

Compilation and host ports cannot establish actual OEM routing, null-route timing, callback ordering, buffered pre-switch audio, microphone audibility, headset hotplug, Bluetooth support, screen-off, calls, audio-policy silencing or long-run battery behavior. Physical route changes may occur between observations or before callbacks arrive; all metadata describes observations, never a sample-exact physical route guarantee. A real-device matrix remains required before claiming wired/USB behavior works reliably.

## Independent static review

A separate read-only reviewer reported no remaining static blocker after checking the final fixes: attribution uncertainty latches across subsequent stable reads; anchors/metadata retain it and exclude FirstPcm; epoch boundaries remain separate; configuration/routed-device agreement and nonretry policy denial remain intact; UI/comments/docs consistently describe per-run fallback. The reviewer executed no tests/build/hardware actions. Host and SDK results must be recorded separately by the root executor.
