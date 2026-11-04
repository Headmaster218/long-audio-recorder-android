# Repository delivery

## One supported entry point

From a fresh clone of `development/recorder-reliability-recovery-20261031`, run:

```sh
python3 scripts/prepare-delivery.py --output ../recorder-delivery
```

This command resolves committed HEAD once, applies all seven ordered checkpoints,
creates `ASSEMBLED_SOURCE.json`, verifies recorded bytes/modes and unrecorded source
inputs, and runs all supported offline host suites sequentially. A failing suite
produces a nonzero exit and retained logs. No network, phone, server, credential,
signing, installation, upload or deletion is part of this command.

The input checkout remains historical source plus checkpoints. **Do not build its
root app directly.** Direct SDK builds now reject it; the optional Gradle entry
also rejects an unassembled checkout. The Gradle path still requires the user to
choose compatible installed Gradle/AGP versions and is not a validated delivery
path. The direct SDK script is the supported Linux build path.

The output directory must not exist, and its parent must exist. Nothing is
replaced or cleaned automatically. Choose a new output for each attempt, including
a retry after failure. Uncommitted changes and ignored/untracked data are excluded.
Use `--ref FULL_COMMIT_SHA` to pin the source. Older commits predating this entry
point do not contain its delivery runner; use their documented assembly workflow.

## Outputs and checks

- `ASSEMBLED_SOURCE.json`: source commit, overlay order and file hashes.
- `app/build/delivery-checks-*/results.json`: per-suite exit codes and explicit
  untested areas. Adjacent logs retain each suite's output, including failures.
- Source files under `app/src`, scripts and documentation: suitable for review and
  future development. To modify the project, edit/commit the appropriate tracked
  checkout source or checkpoint and create a new assembly; modified assemblies
  deliberately fail delivery verification.

Host checks exercise Java policy/storage, recovery, input routing, queue and
cleanup logic, synthetic Android fixtures, SQLite contracts, delivery encoding,
assembly provenance and source wiring. The runner excludes SDK-backed admission
and enrollment-consent tests because they require compiled Android classes, and
excludes the real FTPS/TLS loopback runner because it needs separate dependencies
and network fixtures. These are stated as NOT RUN, not silently treated as passes.
Generated test data stays local under ignored build directories.

## Optional unsigned Linux build

Install the official Platform 37.0 and Build-Tools 37.0.0 first, then pass `--sdk`
and `--deadline-utc` together. The command does not install/download an SDK.
Expected SDK inputs are `platforms/android-37.0/android.jar`, plus `aapt2`,
`zipalign`, and `lib/d8.jar` under `build-tools/37.0.0` (and their native runtime
libraries). Use an explicit future timezone-bearing deadline.

After successful host checks, the build re-verifies source and runs resource
compile/link, SDK Java compilation, D8, packaging, zip alignment and manifest
inspection. The unsigned APK and SHA-256 receipt remain under
`app/build/direct-*/`. The 24 MiB build-output budget applies to that individual
build, not earlier retained test logs/classes; global disk and memory safety
floors remain enforced. No signing key is read or created.

A successful unsigned build does not run SDK-backed host fixtures automatically.
It is not installable until separately authorized signing. Do not identify a
build only by `0.1.0-dev13`: use the source commit and exact APK hash.

## Runtime scope

Visible microphone recording, bounded WAV spool, explicit manual export,
opt-in destination-pinned FTPS enrollment, separately opted-in verified local
cleanup and explicit sealed-file recovery are implemented development features.
Automatic recovery remains default-off. Unsealed/uncertain tails are retained,
and recording does not silently restart after an explicit sealed-recovery check.
SMB/plain FTP and immutable server publication are not implemented.

The user's local-server deployment and physical-phone tests are deferred. This
handoff makes source and repeatable cloud/host checks available, but does not
claim actual Xiaomi lifecycle behavior, power-loss durability, all-day recording,
real-server credentials/connectivity or deployment acceptance. Read the feature
links in README before enabling transfer or cleanup.
