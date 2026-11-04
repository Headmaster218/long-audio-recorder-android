# Recovering capacity occupied by retained verified receipts

## Reproduced failure and chosen behavior

Five hundred verified detailed queue rows can occupy the entire queue while all
originals remain local (NONE/RETAINED cleanup states). Enabling the existing
future-only cleanup option does not select those old recordings. The 501st source
therefore remains safely refused even after future-only consent is enabled.

The application now offers a separate **Review existing verified recordings for cleanup…**
review. It displays the first up to eight eligible recordings in queue order and
their exact destination,
source identities, byte counts and earlier verification times. This bounded review
does not provide arbitrary per-recording selection. The confirmation
explicitly authorizes fresh same-attempt readback followed by permanent local
cleanup for those recordings only. Cancel changes nothing. It never grants
retroactive deletion merely by enabling the future-only option.

The selection is frozen and validated atomically against the saved profile,
current consent, pause state, original attempts/hashes/receipt timestamps and
cleanup state. Changed or malformed batches fail as a whole. Sources needed by
another unverified destination are excluded. No network transfer, deletion or
history retirement occurs merely by accepting the selection. At most eight rows
become DEFERRED; the existing readback, safety gates, budget, local identity proof,
quarantine/purge and durable retirement protocols do the remaining work.

Only successful cleanup can make a receipt eligible for existing exact history
compaction. No verified metadata row is simply dropped to manufacture capacity.
Missing or corrupt remote objects block cleanup and retain the original. Remote
future loss remains possible after any successful readback; the dialog discloses
that this is not immutable backup protection. Other destinations, unavailable
credentials, budget, failures or bounded history fragmentation may still prevent
capacity recovery. This is a safe recovery option, not guaranteed queue clearing.

## Revocation ordering fix

An older queued Enable command could previously clear the immediate in-memory
flag set by a later Disable. Cleanup now uses a monotonic revocation epoch:
queued Enable must still match, Disable increments it immediately, and saturation
fails closed. Historical review captures the epoch when its dialog opens, so a
Disable followed by re-enable cannot revive an old dialog. Historical requests
never clear revocation or enable consent. Previously committed partial purge
replay retains its original documented semantics.

## Executed checks

- All 44 offline host suites passed with the queue API regressions included.
- Official Android 37 SDK compilation, D8 and unsigned APK packaging passed.
- 15 actual compiled revocation-order assertions and 357 actual controller/journal
  assertions passed, including stale dialog, queued Disable/Pause, changed profile,
  frozen selection, missing persisted opt-in and busy-worker rejection.
- 178 queue API assertions passed after a 1,016-assertion capacity setup. They
  cover max-eight atomic selection, malformed/stale rows, destination protection,
  persistence/reopen and revocation. These prior receipts are explicitly synthetic.
- Actual production capture/admission/enrollment regressions remain separate:
  944 admission, 26 enrollment-consent, 105 notification-overflow and 144 actual
  coordinator/SQLite catch-up assertions passed with the new compiled classes.
- Real loopback FTPS success: two recordings genuinely uploaded and verified,
  with 498 unrelated synthetic receipts filling the queue. The fixture submitted those two exact rows to the production controller API
  (it did not exercise the UI, which displays the first up to eight eligible rows). Fresh readbacks used the same attempts with no
  new upload, actual cleanup and retirement freed two slots, and actual catch-up
  admitted source 501. 2,027 Java assertions passed. Final state was 499 originals,
  499 detailed rows and two retired identities; unrelated originals were unchanged.
- The success server recorded four sessions, two MKD, six STOR and 24 RETR
  (production verification reads each object twice). All six remote object hashes
  independently matched the genuine receipts; no remote rename or deletion occurred.
- Real loopback missing/corrupt remote negative: 517 assertions passed. Both
  selected originals remained, all 501 local sources were unchanged, all 500 rows
  remained, no retirement or replacement upload occurred, and source 501 stayed
  safely refused. The success case also checked queue state after SQLite
  close/reopen; the negative case checked state before closing.

Run `scripts/test-historical-cleanup.sh CLASSES ANDROID_JAR` for the SDK-backed
controller tests. `scripts/test-queue-host.sh` includes the historical queue tests.
The separate `scripts/test-historical-cleanup-wire.py` runner requires already
prepared pinned wire dependencies; use `--help` for inputs and `--negative` for the
missing/corrupt case. It never contacts a user server.

## Limits

These are finite cloud host tests using synthetic audio. Only the two selected
wire receipts are real uploads; the 498 fillers are not. Android network, permission,
power, Handler and scheduler boundaries are modeled. SQLite uses actual host
SQLite through adapters. The narrow test-only android.system.Os adapter uses real
Linux dev/inode/ctime and NIO directory force, but does not establish Android
native descriptor or hostile path-race semantics. Production cleanup/retirement
classes are unchanged and execute against that boundary. This is not device/OEM,
Android power-loss, all-day microphone or local-server qualification. No signed
release, main merge, real recording or real credential was produced.
