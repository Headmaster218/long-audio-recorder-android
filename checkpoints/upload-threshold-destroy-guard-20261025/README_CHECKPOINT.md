# Upload threshold and destroyed-pause development checkpoint

Apply these root-relative source files over Android recorder commit 0e2a02dc5edd0a3bcd5cdd8c45f214d18fca0544 in a fresh checkout. This is a source checkpoint, not a signed release or a device qualification.

Two independent changes: (1) validated explicitly saved upload-byte threshold, preserving the 9,600,000-byte default and all prior consent/destination/network/charging gates; (2) destruction permanently revokes the clean-pause continuation capability, including delayed callbacks. The second defect was reproduced against the unmodified baseline before its two-line fix.

Fresh root validation on 2026-10-25: threshold helper 1,634 assertions, host SQL threshold/migration 126, source wiring 28; configured network admission 944; existing core 7,068, scripted FTPS 868, automatic discovery 22, consent 26, continuous enrollment 1,318, local cleanup 54, continuous cleanup 42, history retirement 5,096, queue SQL 27, cleanup SQL 30, history SQL 81; recovery suites 470/15/1,310/656; new destroyed-pause ordering 35. Counts include repeated assertions and are not distinct real-world scenarios. The first regression command stopped because its enrollment-consent script arguments were omitted; corrected arguments ran the remaining suites successfully. No original failure was relabeled.

The full production sources compiled against the official Android API 37 SDK, D8 and unsigned APK packaging/alignment succeeded. Two existing deprecated-API warnings remain. The unsigned build is not installable as delivered and is not included. No test key, password, credential, recording or runtime artifact is included.

Use scripts/test-upload-threshold.sh, test-upload-threshold-sql.py and check-upload-threshold-wiring.py and the existing suites described in docs/upload-threshold-candidate.md. test-ftps-admission.sh needs the official android.jar and compiled production classes in its documented location; test-enrollment-consent.sh takes production classes and android.jar arguments. Compile the new DestroyDuringCleanPauseTest.java alongside the production core classes and run its main class for the focused 35 assertions.

Android lifecycle, device UI, power loss, real server interoperability, microphone capture, background endurance and battery/thermal behavior remain unverified. Existing delivered APK versions do not include this source checkpoint unless rebuilt and separately signed. Do not treat host tests or unsigned compilation as end-to-end phone acceptance.
