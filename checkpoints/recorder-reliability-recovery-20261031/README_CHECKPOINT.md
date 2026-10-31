# Recorder reliability recovery checkpoint — 2026-10-31

Source-only development backup, not a release. The eight root-relative patch files are preserved byte-for-byte from tested local commit `76b87454836bbb9f602a2e8c10762acc9e78a1b8` (78,510 bytes). Their accompanying recovery note records the pre-publication state; this README supersedes its final local-only statement. No APK, SDK, signing key, credentials, recording, transcript, database or runtime log is included.

## Restore an integrated working tree

Use a clean checkout of this development branch, whose parent is published commit `e4bc549d9cd15c75a837f77e569e12595b3a8738`. All 228 existing files and full parent history are retained. The root is an older baseline: apply the following overlays in exactly this order, copying only `app`, `scripts` and `docs` into the repository root. Do not copy checkpoint READMEs over the root README.

```sh
for checkpoint in \
  upload-threshold-destroy-guard-20261025 \
  input-routing-20261025 \
  transfer-budget-recovery-20261030 \
  recorder-reliability-recovery-20261031
do
  for directory in app scripts docs; do
    if [ -d "checkpoints/$checkpoint/$directory" ]; then
      cp -R "checkpoints/$checkpoint/$directory/." "$directory/"
    fi
  done
done
```

Do not apply these instructions to a dirty checkout. No delete, reset, force push or history replacement is required. Local recovery Git intentionally has a recovery root and does not impersonate the remote historical object graph.

## Scope and evidence

Typed pre-resource admission Busy preserves ownership and permits only later explicit Start after existing evidence gates; real partial-open/storage uncertainty remains fail-closed. Explicit Stop dominates late Pause/completion and cannot resurrect clean-pause eligibility.

Fresh October 31 validation: 25 host suites; recovery 1,347 assertions and 124 Stop/Pause action-order cases; production spool-admission 30 assertions; modeled budget SQL 45 and narrow budget contracts 34. Official API37 production compilation, D8 and unsigned packaging passed; compiled production SDK fixtures passed 944 admission and 26 consent assertions. Two existing deprecated API warnings remain. The three production diffs and fresh evidence were independently reviewed. Counts are not distinct device scenarios.

After assembly, run `sh scripts/test-recovery-session.sh` and `sh scripts/test-spool-admission.sh` for the focused regressions; see the root-relative `docs/reliability-recovery-20261031.md` for provenance, all validation categories and remaining acceptance boundaries. This backup does not claim CI or phone acceptance: no actual microphone, Android lifecycle/UI, network, power-loss durability, OEM background survival or long-duration device test has run. No signing, installation or release is authorized by this checkpoint.
