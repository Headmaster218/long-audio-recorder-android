# Explicit sealed-recording recovery in the Android app

## User-visible flow

The active assembled MainActivity now has **Check / recover sealed recordings**.
It runs only after a user tap, while recording and permission prompts are idle.
It explains that complete final manifests and matching WAV hashes are required,
raw/damaged tails stay untouched, recording is not resumed, and existing opted-in
upload/cleanup policies may later apply to recovered completed files.

A process-wide application-context worker owns the operation. A SpoolAdmission
maintenance reservation excludes capture opening, active capture, export and other
maintenance before a fresh physical spool owner is opened. A repeated tap cannot
start another worker. Screen recreation retains the same coordinator/status and
does not initiate work. A durable in-flight preference reports uncertainty after
process loss; there is no automatic restart or retry. Success/error messages show
confirmed recoveries and preserve uncertainty for any operation that failed.

The scanner's `validatedManifest` flag is only a current read-only selection hint.
Every selected `.part` is revalidated by `recoverFinalizedPart` before mutation.
Only already sealed objects can be renamed. Incomplete/corrupt objects are counted
and retained. One pass promotes at most 32 objects; another explicit tap can
continue. Already-ready objects are not republished, and previous outcomes are not
reinterpreted as a new STOP.

**Performance boundary:** the 32-object cap bounds promotions, not scanning I/O.
The scan validates/hashes all applicable objects within the existing finite spool
inventory cap before promotion, including ready objects. It is off the UI thread
but has no cancellation and may take time. Start and exports remain blocked for
the reservation's lifetime. The screen explicitly discloses this limitation.
Device I/O latency and background-process survival remain unqualified.

The active MainActivity is selected from the existing input-routing overlay;
new coordinator/core sources live in the root source tree. No overlay ordering,
recording permissions, upload destination, automatic consent, cleanup consent,
microphone start policy or network endpoint changed.

## Fresh checks

- `test-sealed-recovery-coordinator.sh`: **268 assertions across 8 host fixtures**.
  Uses production coordinator, admission and filesystem core with explicitly
  modeled Android/retirement/export adapters. Covers explicit-only start,
  repeated taps/retained recreation, opening/active capture and export/maintenance
  exclusion, marker commit failures and reload, mixed sealed/raw/corrupt bytes,
  33 candidates over two passes, empty/already-ready passes and uncertain failure
  followed by explicit fresh-owner retry.
- `test-finalized-part-recovery.sh`: **242 assertions across 27 fixtures**, including
  abrupt child exits and actual-thread exclusion. Optional local fixture export
  uses the `sealed.delivery.root` JVM property and production FTPS encoding.
- Earlier abrupt-process and terminal-boundary suites still pass **405** and
  **765** assertions respectively. These are host checks, not phone execution.
- All **33 non-SDK host shell suites** pass on the final integrated source.

## Official-SDK build

The same pinned official Google Platform 37 revision 2 and Linux Build-Tools 37
archives were restored and verified against the hashes recorded in
[the preceding SDK validation](unsigned-sdk-validation-20261103.md). Accepted
license text is unchanged. A first compile exposed unsupported lambda linkage in
the direct SDK bootclasspath; the new production code was converted to anonymous
classes. The final source then passed resource compile/link, complete production
Java compilation, D8, unsigned APK packaging, alignment, manifest/badging and ZIP
integrity checks. Only the two pre-existing FTPS deprecation warnings remain.

- Unsigned APK: **159,641 bytes**.
- SHA-256: `fe4474fdaaf369dcfa59603566c18b2a5302f5e4183640b3efc95d54375b6460`.
- Sampled build process-tree peak: **149.03 MiB**.
- Against these newly compiled classes, host SDK fixtures pass **944** network
  admission assertions and **26** enrollment-consent assertions. Targeted Java-8
  buffer-linkage checks pass; this is not full Java-8/Android runtime qualification.

No signing, installation or physical-device testing occurred. SDK, APK, synthetic
audio, databases and runtime outputs remain local; only source/tests/docs are
eligible for the GitHub checkpoint. Existing version text alone does not identify
the build; use source identity and APK hash.

## Recovered-delivery backend boundary

Eleven locally generated recovered deliveries passed actual backend transport and
metadata-semantic inspection. Nine STOP cases, including anchored version 2,
staged their exact bytes and reached real local ASGI/SQLite strict source
admission, one four-frame source each. Each successful PUT response was deliberately
discarded and reconciled; repeated finalization did not issue duplicate PUTs. INTERRUPTION
was refused before backend requests/job creation. ROTATION staged but could not
finalize without STOP. Original delivered files remained byte-identical.

This deliberately stops at the pre-seal boundary: no native worker, inference,
network service, deployment, recording-work rows or final recording publication.
Source admission is not completed backend processing, physical continuity,
power-loss durability, Android lifecycle or all-day acceptance.

The reusable `scripts/verify-recovered-backend-deliveries.py` accepts explicit
backend-source, fixture and new private output paths. It requires the backend's
runtime/test dependencies and helpers, and must not run with Python `-O`.
The fixture export is opt-in through `sealed.delivery.root` when running
`FinalizedPartRecoveryTest`; generated files must remain local.

The backend checkout had no Git HEAD available. Tested SHA-256 identities include:

- `recorder_delivery.py`: `27062fee264c5ef30a506dfa5cd36c4fdcc007ececfdbe73a8bb15da1ab90c4a`
- `recorder_metadata.py`: `fd8bb8ec51246fd62564773612a8e1ed4a2c4ba13bb0e2ede21d7532cda3c0fd`
- `recorder_capture_intake.py`: `ac937e31e7968582a64df2b6f7d8a90808adbd394b13c55b0cc55ef039c46745`
- `recorder_intake.py`: `f3017ce159e13c8d52fe9ff9829031a60c76e8a97509259e2ac94ba95deb0561`

The repeated verification made nine actual source PUTs, nine reconciliations of
discarded success responses, and no replay PUTs. Its 577 total requests stayed
inside the test ASGI transport; no external HTTP or FTPS service was contacted.
