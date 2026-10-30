# Optional FTPS file-data budget: fresh reconstruction

This is a new local candidate, not the lost October 25 transfer-budget implementation and not a published release. Its base is the exact GitHub source commit `3711ec2acddd2389d104d6e7f75944367e0177c5`, with the upload-threshold/destroy-guard checkpoint applied first and the input-routing checkpoint applied second. Earlier tests and APKs do not qualify this reconstruction.

## Behavior

- Default OFF. Enabling and setting a positive decimal-byte limit require explicit Save and confirmation. Disabling keeps historical charges, performs no new charging, and removes only the optional budget gate.
- The queue stores one manual cycle, not a calendar/day/month or carrier quota. Reset is a separate confirmed operation on the saved state. It leaves the saved enabled flag and limit unchanged and ignores unsaved text edits.
- Every enabled upload, reconciliation retry and deferred cleanup readback commits a complete conservative file-data reservation before constructing a network connection. A failed, cancelled, unknown or never-started attempt keeps its whole charge; there is no refund. Crash recovery cannot clear charges. The conservative consequence is that a journal error after reservation may charge an attempt that never connected.
- A reservation requires `3 * (WAV bytes + local metadata bytes + 512 transport-envelope bytes + 1024 marker bytes) + 6 * 32768` bytes. This covers the current protocol's one write and two readbacks per file, plus bounded read-overrun buffers. Read-only retries use the same conservative estimate. Overflow and invalid enabled estimates fail closed. Disabled operation bypasses estimation entirely to preserve legacy behavior.
- This is not a hard cap on operator traffic. DNS, FTPS control, TLS, retransmissions, kernel/socket buffering and other overhead are not counted. It does not enable cellular, metered networks, roaming, VPN routes or ambiguous routes. Charging, unmetered Wi-Fi, local-network permission, exact destination credentials, TLS and source-verification gates stay in place. Upload now does not bypass the budget.
- Insufficient budget opens no connection, consumes no charge or retry counter, retains the queue/source and defers the check by five minutes. Save/reset does not clear existing pause, backoff or consent. A later check may run previously authorized queued work.

## Persistence and concurrency

Schema 6 adds a separate `transfer_budget` singleton table; settings, profiles and queue tables are unchanged from schema 5. Upgrades from 1 through 5 retain their prior migrations and add a default-off row. SQLiteOpenHelper owns the upgrade transaction. The existing no-backup location and synchronous FULL policy remain.

Enabled reservation reads validated integers and writes all state fields in one transaction using a revision compare-and-set. `endTransaction()` must return before the method reports successful admission, so an uncertain commit cannot authorize a connection. State revisions also reject stale confirmation dialogs. Revision exhaustion fails closed rather than wrapping.

The existing single queue worker serializes all setting commands and transfer I/O. Save/reset refuses an active job both at submission and at execution. A job admitted immediately after the execution-time check queues behind that same worker, so its network operation cannot overlap the reset. No new executors, network calls, credentials or permissions are introduced.

## Validation boundaries

`test-transfer-budget.sh` tests the real Java policy helper, including retries, crash-state reconstruction, disable/reenable preservation, reset/stale/busy refusals, overflow and unchanged hard network policy.

`test-transfer-budget-sql.py` runs the exact production DDL and modeled transactions/CAS on host SQLite, covering versions 1–5, rollback, restart, constraints and retained settings/queue rows. It does not execute Android SQLiteOpenHelper or ContentValues.

`check-transfer-budget-wiring.py` checks narrow source-order contracts for both network entrances, disabled bypass, persistence and UI snapshots. Source checks do not execute Android callbacks or prove thread scheduling.

Host regressions and independent static integration review are recorded separately in the parent gate directory. The four older baseline source checks remain failed or unavailable due obsolete source matches and a missing historical Git object; they are not silently reclassified as passing.

There is no fresh official Android SDK compilation, APK, signing, install, actual network transfer, phone lifecycle test, power-loss proof or all-day acceptance for this candidate. No source has been published.
