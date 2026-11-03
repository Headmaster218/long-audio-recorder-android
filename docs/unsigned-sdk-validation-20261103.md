# Fresh official-SDK unsigned build — 2026-11-03

## Source and tools

Built a fresh seven-overlay assembly of published source
`9a2c04bf9062548eb2107f7a87353c41180dfcd0`, including terminal-aware CaptureEngine.
All 237 source files matched the committed assembly receipt before validation.
No source change was needed to build. This is not the older baseline root build.

Under the project's existing accepted Android SDK agreement, restored only the
previously pinned official Google Platform 37 revision 2 and Linux Build-Tools 37
components. Fresh `repository2-3.xml` still declares the same archive sizes/SHA-1:

- `platform-37.0_r02.zip`: 67,281,901 bytes; SHA-256
  `840b23e827f96e64aea4c89a1194aac3dc5f6bad37edb231c5c795d890330e8d`.
- `build-tools_r37_linux.zip`: 66,135,704 bytes; SHA-256
  `01af179347cbcd9c208b7f8171f7b21f6dd1d2f85bcd15e88caa51d5d7b86060`.

Both archives were freshly downloaded from `dl.google.com/android/repository`,
size/SHA-1/SHA-256 verified before extraction. The current android-sdk-license text
SHA-256 is `1f8729233617b193fd619213792ae16a41b95d2bbbf525dfe66998252ba68b16`,
exactly matching the previously recorded November 1 text. No new terms, preview
SDK, system-wide tool upgrade, signing key, signing or installation was involved.
Selected components were android.jar, aapt2, zipalign, d8.jar and libc++.so; SDK
archives/components stay local and are not source deliverables.

## Fresh result

The existing guarded `scripts/build-android-unsigned.py` passed AAPT2 resource
compile/link, every production Java source against the official API 37 jar, D8,
unsigned APK packaging, zip alignment, manifest and badging checks. The two
existing deprecated API warnings remain: ConnectivityManager.getAllNetworks and
DnsResolver.getInstance. No new compiler error or warning was introduced.

- Unsigned APK: 155,623 bytes.
- SHA-256: `23f4b00d1037488fdf6a999ee45b3886c2874bbc873915e1e015f27a00018d44`.
- Sampled observer/process-tree peak: 148.23 MiB.
- Generated build disk allocation: 2,301,952 bytes.
- ZIP integrity, expected resources/classes and absence of signing metadata pass.

Against these freshly compiled production classes, SDK-backed host fixtures pass
944 network-admission assertions and 26 enrollment-consent assertions. Targeted
Java-8 buffer linkage checks also pass. These fixtures model Android API behavior
on the host JVM; they are not Android runtime/device tests or full Java-8 platform
compatibility qualification.

APK hashes identify this exact unsigned build, not a reproducible signing result.
The version label remains dev13, so version text alone cannot identify its source.
The APK is not signed or installable as delivered by this build step. It was not
uploaded to Git. Existing physical-device acceptance gates remain NOT RUN.

## Fresh real-wire regression

The original nine-case real FTPS/TLS suite also passed using those freshly
SDK-compiled production classes: success, untrusted control certificate, wrong
control hostname, wrong data certificate, corrupted readback, truncated readback,
partial disconnect, lost final reply and stalled handshake. It ran in 15.189
seconds with 141,557,760 bytes sampled process-tree peak and 205,399 application
wire bytes. All source-preservation, read-only reconciliation, certificate gating,
cancellation and socket/listener/callback cleanup assertions passed. Ephemeral
fixture key/truststore files were removed. The server was private loopback and
all credentials/audio were synthetic; only the prior hash-pinned fixture packages
were used. No real server/account/phone was involved.

Independent review verified source and SDK hashes, actual CaptureEngine bytecode
calling terminalAware, ZIP/unsigned structure (including absence of an APK signing
block), alignment and manifest configuration. This was an audit of the fresh build,
not a separate second full APK build. Binary build outputs and runtime logs remain
local. This documentation-only checkpoint does not change the tested production
source identified above.

The runtime still uses explicit Android API fixtures on a JVM. In this runner,
`host_api_fixtures=false` means the production classes were supplied from the
SDK build instead of compiled by `--host-source`; it does not mean real Android
APIs were executed. The report's historical `base_commit` and fixture-only source
hash map are not production build identities. Use the exact assembly commit,
verified build input list and APK hash above for production provenance.
