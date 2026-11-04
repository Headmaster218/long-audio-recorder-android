# Fresh repository delivery validation — 2026-11-04

Tested published implementation commit:
`906e58bcd4e671fef43a888d1af7518f54fe203b`.
The evidence-only commit containing this report does not change implementation.

## Delivered improvement

The repository now has one fresh-clone entry point:

```sh
python3 scripts/prepare-delivery.py --ref 906e58bcd4e671fef43a888d1af7518f54fe203b --output ../recorder-delivery
```

It assembles the seven committed checkpoints, verifies source provenance, runs
supported host suites, and optionally produces an unsigned APK with an existing
SDK. Old-root direct builds fail instead of silently producing an outdated app.
Existing output is retained, uncommitted changes are excluded, and generated
outputs stay local. The [delivery guide](repository-delivery.md) describes inputs,
logs and explicit exclusions.

Independent review found a verifier exemption that could admit an extra Java file
under a directory named `__pycache__`. It was fixed before the tested publication:
only actual `.pyc` files directly under `scripts/__pycache__` are exempt, and
symlinks are checked first. Regression tests cover both cases. Review found no
remaining blocking delivery issue.

> Later November 4 review found executable Python caches were not harmless.
> The [cache-integrity correction](delivery-bytecode-integrity-20261104.md)
> supersedes the cache exemption above without relabeling historical results.

## Exact published-source result

A fresh 261-file assembly of that exact commit passed the full one-command
host-plus-unsigned-build workflow. All 42 offline suites passed, including six
assembly tests and twelve delivery-verifier tests. Source hashes were checked
before and after host tests and again before building. A later verification after
the separate network fixtures also passed. No GitHub Actions run existed for this
commit when checked; this is cloud-host evidence, not a GitHub CI pass.

The direct SDK build passed AAPT2 resource compile/link, all production Java
sources against official API 37, D8, packaging, ZIP alignment, badging and manifest
inspection. The existing two deprecated-API warnings remain; there were no errors.

- APK size: 161,538 bytes, unsigned and not installed or attached.
- APK SHA-256: `84b6113a7e372e1fa438f9925be96f8d19bca6fd13a347417f0f1fa89a25d807`.
- Sampled build observer/process-tree peak: 144.32 MiB.
- Generated per-build disk allocation: 2,404,352 bytes.
- The APK receipt now includes the full source commit as well as its hash.

The cloud runtime used OpenJDK 21.0.13, Python 3.12.14 and Git 2.52.0.
Google Platform 37 revision 2 and Build-Tools 37 were restored from the exact
archives/hashes documented in [the earlier SDK report](unsigned-sdk-validation-20261103.md).
Sizes, SHA-1 from freshly retrieved official repository metadata, and recorded
SHA-256 values matched before extraction. The SDK license text hash still matched
the previously accepted text. No SDK files, signing material or APK were uploaded.

## Separate SDK and real-wire checks

Using the **exact direct build's classes directory**, not a similarly named host
test classes directory:

- Production network-admission fixture: 944 assertions passed.
- Production enrollment-consent fixture: 26 assertions passed.
- Real FTPS/TLS loopback fixture: all nine scenarios passed in 23.549 seconds.
  Sampled process-tree peak: 148,307,968 bytes (about 141.44 MiB).

The wire cases cover successful verified transfer, untrusted control certificate,
wrong control hostname, wrong data certificate, corrupt and truncated readback,
partial disconnect, lost final reply reconciliation, and handshake cancellation.
Every synthetic original remained unchanged, all raw sockets closed, and the
fixture removed ephemeral keys. No real audio, real credentials or external
server was used. Four pinned official PyPI test archives totaled 263,282 bytes;
local fixture installation used 1,194,168 bytes. Existing setuptools was 84.2.1
rather than the historical manifest's 84.0.0; the fixture packages themselves
matched all pinned versions/archive hashes. No system package was upgraded.

The offline aggregate intentionally reports these SDK/wire fixtures as NOT RUN
within that command. The separate results above do not change its honest scope.
The [sanitized evidence summary](delivery-validation-20261104.json) contains suite
results and bounds, with no raw recordings, credentials, keys or local paths.

## Limits retained

No Android runtime, physical phone/Xiaomi/OEM, physical-server deployment,
power-loss durability or all-day recording acceptance is claimed. The optional
Gradle path was not qualified. Real JVM loopback TLS does not validate Android's
trust provider or actual Wi-Fi/mobile changes. This completes source-delivery and
cloud-test preparation, not device or server rollout.
