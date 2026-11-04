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
