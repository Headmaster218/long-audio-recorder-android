# Catch-up through verified FTPS: cloud integration fixture

This fixture follows forty synthetic recordings through the actual production
coordinator, actual queue SQL, actual filesystem confirmation, and actual FTPS/TLS
upload/readback. Eight recordings initially miss the 32-slot notification queue.
It exercises a different limit from the 500-row persistent queue/history cap.

Run from assembled source using previously compiled production classes, the
existing official SDK jar, and the existing pinned wire-fixture dependencies:

```sh
python3 scripts/test-catchup-wire.py --production /path/to/direct-build/classes \
  --android /path/to/android.jar --dependencies /path/to/ftps-wire-dependencies/installed \
  --wait-capacity
```

Prepare dependencies separately with the existing `prepare-ftps-wire-deps.py
--download` workflow. No phone, physical server or real credential is used.
The optional `--wait-capacity` honors the actual five-minute persisted deadline;
without it, the fixture explicitly advances the queue's due timestamp for a fast
functional test. Both modes invoke the coordinator explicitly. They do not test
Android JobScheduler or promise device wake timing.

The real sequence is 32 callback admissions, persisted continuation, reopen,
confirmation and admission of the eight missed sources, and forty readback-verified
transfers. The first transfer JVM halts after 36 completed receipts. A new JVM
recovers the unchanged journal and finishes four pending rows without replacing
previous attempts. This is a between-transfer hard exit, not uncertain in-flight
network recovery. The server independently checks 120 files, 40 attempts, all
hashes and the absence of overwrite/delete commands. Originals remain intact.

## Platform boundaries

Production Java classes are unchanged. Android network route/power/permission
APIs are host fixtures; sockets and TLS are real and restricted to loopback.
The coordinator constructor and main Handler are replaced by a deterministic
harness. SQLite adapters forward actual production SQL and transactions to one
Python SQLite process per connection. They are not Android SQLite or power-loss
proof. Unsupported adapter operations fail instead of silently succeeding.

Separate `test-catchup-queue.sh CLASSES ANDROID_JAR` checks actual persisted pin,
timestamp and rows, plus Disable preventing all eight new admissions after reopen.
Existing authorized rows remain. `test-queue-host.sh` adds actual schema/capacity,
rollback and committed-attempt recovery checks across separate JVMs; its isolated
receipt-persistence test uses a synthetic result, not a real transfer claim.

Tests retain local synthetic evidence under ignored build directories. Ephemeral
fixture keys are removed in cleanup; no SDK, database, recording or key is stored
in Git. The integration has a ten-minute overall limit, finite subprocess limits,
a 16 MiB output cap and a 384 MiB sampled process-tree memory cap.

## Observed results, November 4

The complete production path passed twice and was independently audited:

- Actual persisted five-minute deadline: 319.638 seconds total.
- Final guarded functional repeat with explicit due-time advance: 21.435 seconds,
  sampled process-tree peak 182,853,632 bytes (174.4 MiB).
- Each run: 40 verified SQLite receipts, 40 distinct unchanged attempts, 120 remote
  file hashes matching receipts, all 40 original local WAVs retained and rehashed.
- Each run: 40 control TLS / 360 data TLS sessions, 120 uploads and 240 readbacks.
  Original 36 attempt IDs were unchanged after hard JVM exit; four pending rows
  completed. Listeners closed and ephemeral keys were removed.
- Actual queue/coordinator recovery and Disable checks: 144 assertions. Actual
  production SQLite schema, capacity, transaction rollback and reopen checks:
  524 assertions across separate JVMs. Full final offline workflow: 44 suites pass.

The final test-source tree at local `6030c5d7c333dfe364aa3a84b91961decf7cd0ae`
is identical to published `98ac5c02909b17063a4699edbff8d5b76621bf3a`. All 61
production inputs remain the SDK-qualified notification fix; this batch adds
qualification fixtures, not another app behavior change.

These results do not guarantee recovery from a full 500-row persistent history.
Rows lacking completed, authorized local cleanup are intentionally retained.
Disabling automatic enrollment prevents new admissions; it does not cancel
previously authorized queued transfers. Pause is the separate transfer control.
