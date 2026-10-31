# Local reliability recovery — 2026-10-31

## Provenance

The cloud workspace reset removed the unpublished October 30 reliability candidate. This is a fresh reconstruction, not a claim that old artifacts or old test results survived. The immutable source snapshot was recovered through the GitHub connector from published commit `e4bc549d9cd15c75a837f77e569e12595b3a8738`, tree `6c26edd6380cd87ba3b9a341b1033791dbcd1fe6`: 228 files / 1,641,465 bytes, each Git blob SHA and size verified.

The working baseline applies the published `upload-threshold-destroy-guard-20261025`, then `input-routing-20261025`, then `transfer-budget-recovery-20261030` overlays. All original checkpoint directories remain intact. Local Git starts with an explicitly labeled recovery root and overlay commit; it does not pretend to contain the remote's full historical Git object graph. No existing Git/history was deleted.

## Reconstructed fixes

1. **Pre-resource admission busy:** `SpoolAdmission.Busy` denotes denial before capture ownership or filesystem work. `CaptureEngine` records this narrow case as initialization failure without claiming spool opening. Existing journal/physical-release gates can then release session admission for a later explicit Start. It never authorizes automatic retry or clears another owner's admission. Genuine partial-open/storage/journal/release uncertainty still fails closed.
2. **Stop dominates late Pause:** a monotonic explicit-Stop flag prevents later Pause or stale completion from reviving clean-pause eligibility. Ordinary Pause remains eligible only under existing evidence gates. Stop does not skip cleanup or create another recorder.

Both defects were reproduced anew before production edits. The Stop regression failed on the old source; the busy reproduction ran actual production admission with the old CaptureEngine evidence ordering modeled and observed `NO_HANDLE`, writer `NOT_OPENED`, spool `UNCONFIRMED`, and `storageClosed=false` even after maintenance ended.

## Fresh validation

- 25 existing JVM host shell suites passed on the reconstructed source, including the extended recovery-session suite: 1,347 assertions and 124 Stop/Pause action-order cases.
- Actual production spool admission: 30 assertions, including maintenance/opening/live-owner contention, explicit retry with a real host filesystem spool, injected constructor failure and subsequent lock reacquisition. Narrow CaptureEngine source-order contract passed; it is not an Android runtime test.
- Transfer-budget modeled SQLite checks: 45; narrow budget source contracts: 34.
- Official API37 AAPT2 resource compilation/link, production javac, D8, unsigned APK assembly, zipalign and manifest checks passed. Restored packages match the previously approved official package SHA1/SHA256 pins, and SDK license text matches the previously inspected hash. No new key, signing, installation or SDK upgrade occurred.
- Compiled production SDK-fixture admission (944 assertions) and enrollment-consent (26 assertions) checks passed. Existing deprecated API warnings remain.

Raw logs, SDK/downloads and unsigned build outputs stay outside this source checkpoint. No historical test counts are reused as current evidence, and unrelated legacy source-pattern checks are not relabeled as passed.

## Remaining acceptance boundary

No real microphone, Android service/UI, permission revocation, actual network, OEM screen-off/process survival, filesystem power-loss durability, or 12–48-hour device endurance was tested. Process death, force-stop and reboot still require explicit user restart; no sticky/boot recording or persistent resume authority is introduced. Test on the intended device with consented/synthetic audio, exercising screen-off/power saving, call contention, mic privacy/permission changes, controlled cache-full conditions, upload interruption, and repeated Start/Pause/Stop. Do not deliberately fill the whole device or publish recordings/credentials/logs.

This source change is local-only unless separately authorized for remote publication.
