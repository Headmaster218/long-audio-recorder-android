# Fresh unsigned SDK and loopback TLS validation — 2026-11-01

## Provenance and SDK scope

Restored public commit `42765fa6b5c0e55dd0a5afb9daa990d7e24e4e0c`, tree `0e8ac8736becce30d245d6e2f9c6f1b2b6295084`: all 239 blobs / 1,751,504 bytes verified against Git blob hashes and sizes. Applied the five documented source overlays in order. Existing Git was preserved; the new local recovery root does not pretend to contain full historical Git objects.

The project already had explicit Android SDK agreement authorization. Only the pinned official Google Platform 37 revision 2 and Linux Build-Tools 37 archives were restored, not a broad SDK installation. Official `repository2-3.xml` currently matches the earlier public source pins:

- `https://dl.google.com/android/repository/platform-37.0_r02.zip`: 67,281,901 bytes; SHA1 `ed8ebf7f8822a4de5686d427f237d2fa30ff7410`; SHA256 `840b23e827f96e64aea4c89a1194aac3dc5f6bad37edb231c5c795d890330e8d`.
- `https://dl.google.com/android/repository/build-tools_r37_linux.zip`: 66,135,704 bytes; SHA1 `70954e99f4c3d9d46ee70fa32624672fe7cd6ebe`; SHA256 `01af179347cbcd9c208b7f8171f7b21f6dd1d2f85bcd15e88caa51d5d7b86060`.

Both reference `android-sdk-license`, with text dated January 16, 2019. Current text SHA256: `1f8729233617b193fd619213792ae16a41b95d2bbbf525dfe66998252ba68b16`. No historical license-text hash was available for comparison; this is NOT a claim that an old/current hash comparison passed. No preview package, new terms, signing key or signing operation was used. Retained extracted components are android.jar, aapt2, zipalign, D8 and zipalign's required libc++.so. SDK/archives are not source deliverables.

## Fresh results, not historical carry-forward

`build-android-unsigned.py` passed AAPT2 resource compilation/link, all production Java compilation, D8, unsigned packaging, zip alignment and manifest checks. Existing two deprecated API warnings remain. APK SHA256 `677023b6be70646f7c4d14e16530c6cc367a67bc0a7d68f540b2a0d503466c47`, 155,032 bytes. Recorded sampled observer/children peak was 146.8 MiB and generated build size 2,478,080 bytes, under the existing guards. Independent review checked source/blob provenance, compilation inputs, ZIP integrity, unsigned structure, alignment and manifest; it did not claim an independent second full build.

Actual freshly compiled production SDK fixtures passed 944 network-admission assertions and 26 enrollment-consent assertions. These use host API fixtures and do not execute Android runtime behavior.

The existing pinned loopback real-wire fixture initially passed success then failed while reading an empty `ready.json`. Investigation found a fixture readiness-publication race: `Path.write_text` exposes the new empty file before writing its JSON body; the runner watches file existence. This is not evidence of a production FTPS defect. The failed run is retained separately in local evidence and is not counted as passed.

Fixture-only repair: complete a same-directory temporary JSON file before atomically replacing `ready.json`; publish only after actual server bind/TLS setup/getsockname; remove temporary files on write/replace failure. A deterministic regression reproduced the visibility failure before the fix. Fourteen checks now cover interrupted visibility, same-directory replacement, retained complete prior content, failure cleanup and source ordering. Run `python3 scripts/test-ftps-wire-readiness.py`; it needs no third-party packages, keys or sockets. No production Java changed.

After repair, all nine real-wire cases passed: success, untrusted control, wrong-host control, wrong-data certificate, corrupt readback, truncated readback, partial disconnect, lost final reply and stalled handshake. Recorded sampled process-tree peak 142,548,992 bytes, elapsed 23.627 seconds, aggregate application wire data 205,399 bytes. Checks include local source preservation, socket/callback/listener cleanup, bounded cancellation, TLS credential gating and read-only reconciliation. Ephemeral fixture TLS keys/trust material were removed. No APK signing key was created.

Dependencies were restored from the existing exact hash/size-pinned fixture manifest: 263,282 download bytes, 1,194,148 installed bytes, scoped to ignored build output with no system upgrade. Existing runtime setuptools is 84.2.1 rather than the manifest's historical reused-runtime note 84.0.0; it built the pinned pyftpdlib source successfully. The four installed package versions/hashes were verified by the runner. The runner's hard-coded `base_commit` field is historical fixture-origin metadata (`8edff989...`), NOT the source tested here; the current source is the explicit `42765fa` recovery plus the fixture-only repair above.

## Reproduction and limits

Use the published recovery/apply-order instructions, then this checkpoint's fixture/test/doc overlay. With the authorized existing official SDK, run `scripts/build-android-unsigned.py --sdk SDK --deadline-utc DEADLINE`. Point `app/build/ftps-checks/android-classes` at that fresh build's classes before `sh scripts/test-ftps-admission.sh SDK/platforms/android-37.0/android.jar`; run `sh scripts/test-enrollment-consent.sh CLASSES SDK/platforms/android-37.0/android.jar`. The real-wire runner requires separately prepared pinned fixture dependencies and its explicit reviewed-fixture flag, CPU 3/nice 19, as described in the existing script. Never substitute stale production classes.

This establishes JVM sockets/TLS against synthetic loopback servers, not Android routing/trust provider/service lifecycle or actual remote-server interoperability. Actual microphone acquisition/read/release, permissions/notifications, OEM screen-off survival, filesystem power-loss durability and long-duration device recording remain untested. No signing, installation, release, main-branch merge, APK attachment, private recording, credential, database or raw runtime log is included in this source checkpoint.
