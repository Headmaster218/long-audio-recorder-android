# SDK / loopback wire checkpoint — November 1, 2026

Fixture, test and documentation only. No production Java changes. This additive checkpoint follows `42765fa6b5c0e55dd0a5afb9daa990d7e24e4e0c`; the four root-relative files are exact copies of local progress commit `90788da502ce6f26518ae51e5eddd08e27403213`.

## Apply in a clean checkout

Preserve Git and all existing files/history. First apply the existing checkpoints in this order:

1. `upload-threshold-destroy-guard-20261025`
2. `input-routing-20261025`
3. `transfer-budget-recovery-20261030`
4. `recorder-reliability-recovery-20261031`
5. `repeated-start-lifecycle-audit-20261101`
6. `sdk-wire-readiness-20261101` (this checkpoint)

For each checkpoint copy only its available `app`, `scripts` and `docs` subdirectories into the repository root; never overwrite the root README with checkpoint READMEs. For example, after applying the first five:

```sh
for directory in app scripts docs; do
  if [ -d "checkpoints/sdk-wire-readiness-20261101/$directory" ]; then
    cp -R "checkpoints/sdk-wire-readiness-20261101/$directory/." "$directory/"
  fi
done
python3 scripts/test-ftps-wire-readiness.py
```

The new test deterministically reproduces the fixture's previously observed empty-readiness-JSON race and validates same-directory atomic publication/failure cleanup. Fourteen checks pass. The restored official SDK unsigned build and actual compiled admission/consent checks passed; after this fixture-only fix all nine bounded real JVM loopback TLS scenarios passed. Independent review found no scoped blocker. Initial failed evidence remains local and is not relabeled as a pass.

See `docs/unsigned-sdk-wire-validation-20261101.md` for exact official-package pins, license-hash limits, fresh results, reproduction commands and remaining Android-device boundaries. Source backup only: no release, signing, installation, APK, SDK, fixture dependencies, private audio, credentials, databases or runtime logs included.
