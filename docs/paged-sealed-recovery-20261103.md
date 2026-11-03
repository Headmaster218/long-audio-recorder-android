# Paged and cancellable sealed-recording checks

## Why the app flow changed

The initial recovery button validated the whole spool before promoting up to 32
sealed objects. That unnecessarily rehashed completed audio and held the storage
reservation for the whole scan. The app now uses `SealedRecovery.runPage`:

- Enumerate the existing bounded namespace, selecting only the next 32 staged
  `.part` identities after a lexical cursor. Completed WAV payloads are not opened
  or hashed by this pass.
- Validate each selected staged object completely before any promotion. Raw,
  corrupt or incomplete objects stay untouched and still advance the completed
  page cursor, so they cannot permanently block later sealed objects.
- Recover only the already sealed subset through the same full-hash, original
  metadata, no-replace and durable-publication protocol.
- Commit the next cursor together with clearing the in-flight marker only after
  the whole page succeeds and storage is released. A failed preference commit
  does not advance the coordinator's authoritative cursor, even if the platform
  preference cache changed in memory.
- A completed sweep resets the cursor. New objects inserted before a saved cursor
  are picked up in a subsequent explicit sweep; this is not a frozen snapshot or
  an automatically repeating background scan.

Progress survives screen recreation and process restart through ordinary private
preferences. Interrupted/cancelled pages retain the prior completed-page cursor.
Any objects already published disappear naturally from later staged-only pages;
they are not republished. Unreadable progress preferences cause a safe explicit
restart from the beginning, never automatic action or audio modification.

## Cancellation boundaries

**Cancel recovery checks** sets a cooperative cancellation flag. Namespace
selection and pre-publication file hashing check it; hash checks occur between
bounded read blocks. Cancellation before rename leaves the staged object intact.
Once rename begins, cancellation is deliberately ignored until root sync and final
verification finish. The confirmed count can therefore increase by that current
object after Cancel, but the next object is not started. The reservation remains
held until the worker closes the physical owner. No thread is abandoned and no
second worker is created to bypass blocked I/O.

This is **not a hard time bound**. The 32 limit bounds staged-object count, not
bytes or elapsed time. Large selected segments may be hashed more than once to
preserve validation. Fresh-owner inventory still enumerates/stats namespace
metadata, and retirement initialization, platform reads/fsyncs and post-rename
validation may block. Those operations cannot always be cancelled promptly.
The UI explains that an in-progress publication must finish safely before
Start/export unlock. Physical-device latency remains unmeasured.

The old full-inventory core helper remains for compatibility; the app no longer
calls it. No format, original close reason, anchors, upload consent, cleanup
policy or uncertain-tail promotion rule changed.

## Verification

- Coordinator/core host suite: **417 assertions across 14 fixtures**, including
  persisted/recreated cursor, 33 mixed staged objects across pages, a valid object
  behind 32 raw tails, failed completion/start-marker retries, cancellation while
  validating and immediately before/after rename, safe current-publication
  completion, lexical ordering and ready-payload exclusion.
- Explicit finalized-part suite: **261 assertions across 31 fixtures**, retaining
  abrupt process-exit and actual-thread exclusion tests plus cancellation-boundary
  and staged-only selection checks.
- The original abrupt-exit suite remains **405 assertions across 21 child exits**.
- Independent source review found no blocking integrity, ordering or cursor issue
  under the existing cooperating-owner/private-spool assumptions.
- All **33 non-SDK host shell suites** pass on final integrated source. Fresh
  official-SDK resource/Java/D8/unsigned-APK compilation, ZIP/alignment/manifest
  checks, **944** admission assertions, **26** consent assertions and targeted
  Java-8 buffer linkage checks also pass. Only the two existing FTPS deprecation
  warnings remain.

Final unsigned APK: **161,538 bytes**, SHA-256
`c6bd3c8852b3fb7535b864812e93bc56500993a247e4af9b3145197673091ce3`.
Sampled build process-tree peak: **147.57 MiB**. All 61 production input files
match the final selected root/overlay sources. The official SDK/license is the
same pinned and previously accepted version as the preceding app checkpoint.
APK/SDK/test media remain local and unsigned; no binary is included in Git.

Host adapters are not Android runtime behavior. No installation, physical-device,
power-loss, hard-latency, unattended recording or all-day qualification is implied.
