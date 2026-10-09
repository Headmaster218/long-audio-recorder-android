# Manual verified SAF export source candidate

Version 0.1.0-dev5, based on frozen local dev4 `6e190a4`. This source feature is
pending independent review and Android runtime acceptance. No dev5 APK, signing,
upload or publication is part of this change. There are no new dependencies,
permissions, providers, services, credentials or automatic jobs.

## User flow

1. Load the completed-segment list, then select one segment. Pages hold at most
   100 entries ordered by object identity. Next page and first-page refresh make
   older/later objects reachable without retaining an unbounded list in memory.
2. Tap Save selected WAV. Android's `ACTION_CREATE_DOCUMENT` picker asks the user
   to choose the specific destination and filename. A cloud DocumentsProvider
   may transmit it; the screen says so. This is not an SMB/FTP implementation.
3. The background worker validates the original, streams it, closes the target,
   then reads the target back to compare total bytes and SHA-256. The UI displays
   VERIFIED, UNVERIFIED, FAILED or CANCELLED plus the copy's byte/hash receipt.
4. Every source remains untouched. Exporting even a VERIFIED file does not
   authorize retention/deletion or feed the policy core's `DeletionGate`.

Only completed WAV bytes are copied. The app-private intent and manifest remain
private; exporting their contents, batch transfers, playback and recover/salvage
of partial recordings are separate work. The filename identifies the capture hash
and sequence, but is not a human calendar timestamp.

## Source admission and immutability

`CommittedSegments` is a read-only catalog rooted at the canonical Android
`Context.getFilesDir()` anchor plus `audio-spool`. It requires a strictly shaped
`segment-<64 lower-case hex capture hash>-<nonnegative sequence>.ready` directory,
exactly `audio.wav`, `intent.bin` and `manifest.bin`, all regular files, bounded
checksummed metadata, matching capture identity/sequence/intent and expected WAV
size. `.part`, temp paths, path traversal, unexpected files, links at the root,
segment, metadata or WAV paths, and foreign-catalog Entry instances are rejected.
Catalog Entry construction is private; UI selection comes only from the admitted
page. Metadata validation during listing is not itself content verification.

Before target writing, full WAV/header/PCM hashes are validated with a bounded
buffer. The copy computes the WAV hash again. Source metadata, file identity where
available, size and times are compared to the listed Entry. Source content is
validated again after copying and after target readback. Any discrepancy fails
closed. A metadata-only snapshot is never enough to mark a copy verified.

This uses the existing private, single-owner immutable `.ready` namespace.
The recorder may add new committed objects but never changes a ready object.
It does not acquire the spool writer lock or re-sync owned files, and does not
claim recovered source durability merely because a `.ready` entry is readable.
It is not a security boundary against a rooted device or a hostile same-UID
process racing ancestor replacement between checks. Links/substitutions present
at admission or observed during verification are rejected. No source is opened
for write, renamed, truncated, repaired, promoted or deleted by this feature.

## External target limitations

The only accepted picker result is a document `content:` URI. It receives only
the picker’s temporary read/write grant; there is no persisted URI grant or tree
access. The URI is not converted to a filesystem path or stored for unattended
retry. The target is already created by the picker, so even a pre-copy rejection
can leave an empty external document.

The provider is opened in sequential `w`, closed, then reopened in sequential
`r`. There is no requirement for seek, rename or `fsync`, and no claim of atomic
external commit. `w` may not truncate on every provider; trailing bytes cause a
verification failure. Missing read permission, unreadable targets or a read/close
exception after successful copy produce UNVERIFIED. Short, extra or mismatched
bytes produce FAILED. A write/flush/close error produces FAILED. Cancellation
keeps all originals and can leave a partial target. No automatic external cleanup
is attempted because provider recovery/delete semantics are not assumed.

VERIFIED means a closed target yielded the expected bytes and SHA-256 on readback
at that moment. It cannot establish provider persistence, later cloud upload,
server-side acceptance, power-loss durability or the absence of later edits.
It is deliberately not a transfer receipt that grants deletion eligibility.

## Lifecycle, repetition and bounded work

`ExportSession` is a synchronized process-local single-flight state machine.
Duplicate clicks cannot queue more scans/copies; stale picker/session callbacks
cannot start another copy. `ExportPickerTicket` binds each Activity result to a
monotonically increasing per-Activity request code and one-shot session token;
both survive saved-state recreation. An old picker result cannot be rebound to a
newer selection. Request codes never wrap; after exhaustion a fresh screen is
required. No picker or worker is automatically restarted during recreation.

The coordinator holds application context only. One background-priority worker
per process performs scans and copies with a 32 KiB streaming buffer and bounded
metadata/page memory. It remains observable from a recreated Activity. An
interruption marker is committed before opening the picker. After process death
an interrupted/unknown result is shown, and stale saved picker replies are
ignored. No persistent task or URI permission retries the operation. The marker
is conservative; a crash after completed I/O but before clearing it can still
report unknown. It is not a durable completed-export history.

Cancellation is cooperative between reads/writes and phases. A provider may
block inside open, read, write or close; cancellation waits for that call to
return. The worker is not abandoned and no second copy starts while blocked.
There is no bounded completion-time or process-survival guarantee. Keep the app
visible until completion. Screen-off, heavy concurrent recording/export I/O,
provider/OEM behavior and process killing require device tests. Existing recording
settings, permission start gates, service controls and capture source are unchanged.

## Source locations

- `core/CommittedSegments.java`: bounded catalog and source verification.
- `core/VerifiedExport.java`: sequential copy, close/readback and honest outcome.
- `core/ExportSession.java`, `core/ExportPickerTicket.java`: testable repetition,
  cancellation, token and recreation model.
- `android/ExportControls.java`, `android/ExportCoordinator.java`: native UI/SAF
  adapter and one application-owned background worker.
- `android/MainActivity.java`: appends controls and forwards state/results/refresh.
- `app/src/test/.../ExportTest.java`: synthetic host tests.

## Verification and remaining acceptance

Author's final source check on 2026-10-09:

- 288 export assertions covering multi-buffer copy, write/open/null/flush/close
  failures, unreadable/revoked/zero-read/short/extra/corrupt targets, source changes
  before/during/after copy (including same-size changes with restored mtime),
  source metadata changes, traversal/link rejection, 103-object pagination,
  cancellation, duplicate/stale callbacks, request-code exhaustion and the
  Activity-recreation/process-boundary state model.
- Existing 7,068 policy assertions, 14 policy adversarial cases, 389 WAV/storage
  assertions, 11 storage adversarial cases, 58 app-run safety assertions and
  seven app-run adversarial cases pass.
- Existing app wiring, new export wiring and targeted Java-8 buffer-linkage checks
  pass. These are narrow source checks, not Android instrumentation.
- All production Java sources compile against the installed official API-37
  `android.jar` with Java-8 source/target and the unchanged dev4 aapt-generated
  `R.java`. No D8, new resource packaging, APK generation or signing was performed.

Reproduce host tests with `sh scripts/test-export.sh`. The guarded aggregate
runner `python scripts/run-export-checks.py` pins child checks to CPU 6, nice 19,
monitors process-tree RSS, caps the worktree at 24 MiB and requires 5 GiB free disk.
Optional `--sdk <existing-sdk> --resource-java <existing-generated-R.java>` also
performs the source-only Android compile. It never downloads dependencies.
The runner requires a Linux host exposing CPU 6 and the existing Java compiler
module. Plain test scripts do not themselves impose the resource guard.

Unrun: real SAF chooser/cancellation, runtime URI grants/revocation, actual
Activity rotation/process death, local/cloud DocumentsProviders, install/update,
GUI visual/accessibility usability, SDK API-29 device linkage, simultaneous
recording/export under load, Xiaomi behavior, power loss and long-duration use.
These must not be represented as passed by the host state-machine model tests.

## Official API references

- [Android SAF file creation and user-selected access](https://developer.android.com/training/data-storage/shared/documents-files)
- [ContentResolver stream modes and provider differences](https://developer.android.com/reference/android/content/ContentResolver)
- [AssetFileDescriptor stream ownership](https://developer.android.com/reference/android/content/res/AssetFileDescriptor)
- [Android process lifecycle](https://developer.android.com/guide/components/activities/process-lifecycle)
