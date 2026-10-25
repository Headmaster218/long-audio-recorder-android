# Configurable FTPS upload threshold: isolated source candidate

This overlay targets restored Android source `0e2a02dc5edd0a3bcd5cdd8c45f214d18fca0544`.
It does not change `RecoverySession.java` and can be layered onto the separately
reviewed destroy/pause fix. Nothing here establishes a successful build or test:
the author performed source inspection and wrote files only. The root executor
owns all compilation, tests, SQL execution and review of resulting evidence.

## User-facing behavior

- The FTPS screen adds an explicitly saved byte threshold. Units are exact
  decimal bytes, not MB/MiB. Accepted values are integers from 1 to
  9,223,372,036,854,775,807 inclusive. Blank, zero, negative, fractions, exponent
  notation, non-ASCII digits and overflow are rejected. The field admits up to
  32 characters so a too-long pasted value cannot silently truncate to a valid
  19-digit threshold, including Android's one-character surrogate-pair boundary
  adjustment; the parser rejects input longer than 19 characters. A plain text
  input preserves pasted signs and separators for parser rejection rather than
  allowing a numeric key listener to silently strip them.
- Fresh installs and migrated queues keep the existing 9,600,000-byte trigger.
  Age triggering remains disabled. Saving a new value is a separate confirmation
  that warns that lowering it may make already-authorized queued work eligible.
  Unsaved field edits never affect scheduling or the consent dialog's saved value.
- The threshold is queue-wide, retaining the prior behavior: sum WAV bytes of
  `QUEUED` and `RECONCILE` rows, including delayed rows. Distinct authorized copies
  for different destination revisions count separately. `RUNNING`,
  `NEEDS_CREDENTIALS`, `BLOCKED`, verified rows, metadata bytes and the active
  unfinished recording do not count. The sum saturates at `Long.MAX_VALUE`.
  Negative row sizes fail closed instead of subtracting pending bytes.
- This is a trigger, not a promise to empty the queue in one batch. Each completed
  row lowers pending bytes; below-threshold tails wait for more pending data or
  an explicit Upload queued now request. Thresholds above the achievable cache
  or pending-queue size may never be met. Cache-full behavior is unchanged.
- Upload queued now keeps its old meaning: unpause and mark currently runnable
  queued rows as explicitly requested. It does not bypass backoff, charging,
  unmetered Wi-Fi, runtime permission, destination binding, credentials or TLS.
  Later rows do not inherit this flag. Existing consent may continue historical
  enrollment after resume, but that is not new consent. Already-started attempts
  remain readback-only reconciliation and do not wait for the byte threshold.
- Saving the threshold neither enrolls recordings nor changes any automatic
  enrollment/cleanup consent, destination revision, credential, pause flag or
  retry time. Automatic enrollment still requires its original destination-bound
  opt-in. Existing separate cleanup authorization and its risks are unchanged.

## Persistence and concurrency

Queue schema version 5 adds one non-null positive SQLite integer column to the
existing singleton settings row. New schema and migration use the same literal
default. Upgrades from versions 1–3 retain their existing sequential migrations;
version 4 only adds the new column, without re-creating history tables.
`SQLiteOpenHelper` owns the upgrade transaction; this is source-level reasoning,
not an Android crash/rollback test. Unsupported versions continue to fail closed.

The getter requires an actual SQLite integer and validates positivity. The setter
validates both proposed and expected values, then updates only this column with a
compare-and-set predicate; a stale dialog cannot overwrite a different saved
value. Successful save is reported only after the update returns. No
SharedPreferences or credential-store changes are introduced. The existing
no-backup queue location and synchronous FULL policy remain in place.

All threshold writes, trigger reads and queue work run on the existing single
worker. The command boundary rejects submissions while an active transfer job is
present. A command racing job admission may be submitted before or after that job, but
both execute serially on the same worker, so a setting write cannot interleave
with transfer I/O. Both scheduling selection and network admission use the
configured policy, while the unchanged live network watchdog continues enforcing
hard gates independently of threshold. Threshold changes do not alter a transfer
already in flight. No new synchronization or network I/O is introduced.

## Tests supplied for the root executor

1. `sh scripts/test-upload-threshold.sh`: actual Java helper, boundaries, parser,
   long overflow, state filtering, gate matrix, legacy-default equivalence.
2. `python3 scripts/test-upload-threshold-sql.py`: production DDL on synthetic
   host SQLite; versions 1–5, modeled migration transaction rollback, restart, strict schema constraints, stale-save
   predicate, preserved consent/destinations/retries and Upload now row scope.
   Java ContentValues operations are modeled, not executed on Android.
3. `python3 scripts/check-upload-threshold-wiring.py`: explicit source contracts
   for the cross-component wiring; not behavioral proof.
4. Existing `test-ftps-admission.sh` with the official SDK and newly compiled
   production classes: extended fixtures exercise actual `FtpsNetwork.allowed`
   using custom low/high/max thresholds on fixture API levels 29–37, including
   blocked network/charging/permission conditions and manual override. These
   fixtures do not create connections or use real credentials.
5. Existing threshold-adjacent core, FTPS queue, automatic enrollment, cleanup,
   history migration, admission and recovery regressions. Two historic SQL
   fixtures remove the v5 column when reconstructing old schemas, preventing a
   duplicate-column artifact during migration tests. Their old assertions remain.
6. Official-SDK full-source compile and unsigned build only if the root's scope
   permits; no signing, installation, upload or publication is included.

## Remaining acceptance limits

No real Android UI, lifecycle, process-death/power-loss, TLS/server, sustained
capture or transfer run was performed by the author. Real-device settings save,
rotation/reopen, Cancel, repeated taps, stale dialogs, corruption handling,
active-transfer contention and threshold-trigger scheduling still need qualified
testing. Readback at transfer time still does not prove future remote persistence.
This candidate adds no new deletion or transfer guarantees.

## Independent source review

A separate read-only reviewer found no blocking correctness or security issue
after the GUI input fixes. Review covered migration sequencing/defaults,
single-column compare-and-set, serialized policy selection, preserved hard
gates/consent/destinations/credentials and pending-byte saturation. The review
identified numeric-input sanitization, long-paste truncation and UTF-16 boundary
cases; the final field preserves plain text and uses cap32. The review also
corrected an over-specific command/job ordering claim above: serialized execution
is the guarantee, not which racing submission reaches the worker first.
The reviewer executed no project code, tests, SQL or network operations.
