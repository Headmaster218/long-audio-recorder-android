# Repeated-Start cleanup regression checkpoint — 2026-11-01

Tests and documentation only; no production change or new feature. The test is copied exactly from local progress commit `aa51d738b57d478aef91c1f52ce5163f9284f3f8`.

## Restore and run

Use a clean working copy of this development branch, retaining existing Git/history. First follow `checkpoints/recorder-reliability-recovery-20261031/README_CHECKPOINT.md`: apply upload-threshold/destroy-guard, input-routing, transfer-budget-recovery, then recorder-reliability-recovery in that exact order. Finally copy this checkpoint's root-relative `app/` subtree into the root:

```sh
cp -R checkpoints/repeated-start-lifecycle-audit-20261101/app/. app/
sh scripts/test-recovery-session.sh
```

This additive checkpoint has parent `4ee9858a5bc430db06bdd4f3239cad83dde82d73`. All existing source files/checkpoints remain unchanged. Do not apply overlays to a dirty working copy. No reset, deletion, force push, or history replacement is needed.

## Bounded audit

Repeated explicit Start while the coordinator is active, stopping an attempt, or awaiting durable journal acknowledgement must not create a replacement recorder. Delayed PCM/Pause callbacks and duplicate completions must not restore clean-pause eligibility after Stop. Confirmed microphone release with uncertain storage must remain blocked.

The additional 42 host assertions cover known-closed and unconfirmed-storage outcomes against the production coordinator. Both passed without a production fix: no new production defect was established. The extended suite passes 1,389 assertions, retaining 124 Stop/Pause ordering cases. The existing separate DestroyDuringCleanPauseTest passed 35 assertions. These counts describe deterministic host ports, not distinct device scenarios.

Fresh existing host suites also passed, including scripted FTPS transport, automatic discovery, and continuous-enrollment cancellation. They are not actual Android consent/network admission or server interoperability tests. The official SDK, compiled Android classes, and pinned real-wire fixture dependencies were unavailable, so SDK-dependent admission/consent, unsigned build, and real-wire checks were NOT rerun. Prior results are not reused as fresh evidence. Those are environment blockers, not intrinsic phone requirements.

Actual AudioRecord behavior, Android service lifecycle and dispatch, permission/notification changes, OEM background survival, real filesystem durability and long-duration microphone recording remain device acceptance tasks. No SDK download, signing, installation, APK, credentials, recordings, transcripts, database, runtime logs or private audio outcomes are included. This is a source backup, not a release or CI/device acceptance claim.
