# Independent policy-core review

Review baseline: `f285ac583269929f2704e336c94ead77cd1792ec`, 2026-10-09.
This review concerns the dependency-free Java contracts only. It is not an
Android build, device test, storage-durability test, or network integration test.

## Original adversarial evidence

Run `sh scripts/test-core-adversarial.sh` separately from the author's original
`sh scripts/test-core.sh`. The review script compiles production sources and the
independent harness with Java 8 source/classfile targets, then runs on JVM 21.
Neither script validates a Java 8 boot classpath or an Android runtime.

At the baseline commit, the original harness passed 7,014 assertions. Most of
that count comes from repeated checks while splitting 1,000 deterministic
variable-length reads; it is not 7,014 independent behavioral scenarios.

The independent harness reported 9 named cases passing and 2 failing:

```text
PASS PCM exact arithmetic boundaries
PASS epoch counter overflow preserves pending tail
PASS invalid format transition is atomic
PASS cache limits cannot overflow or evict
PASS 16 trigger combinations
PASS 4096 hard-gate combinations, including Upload now
PASS concurrent reservation accounting and crash restoration
FAIL overrun snapshot remains persistable: java.lang.IllegalArgumentException: overflow
FAIL observed counter overflow fails closed: already-observed overflowing traffic must fail closed: expected 0, actual 90
PASS all nine receipt identity fields reject substitutions
PASS failed final recheck revokes delete eligibility
Independent review: 9 cases passed, 2 cases failed
```

The concurrent-reservation case models multiple outstanding reservations under
the documented single writer. It is not a multithreaded race or persistence test.

## Findings requiring correction

1. **An observed overrun can make durable quota snapshots unrepresentable.**
   Set both limits to `Long.MAX_VALUE`, reserve 10 and `Long.MAX_VALUE - 10`,
   then record 11 actual bytes against the first reservation. `recordBytes()`
   returns false as expected, but `snapshot()` throws while adding spent bytes
   and other pending reservations. Snapshot accounting must remain fail-closed
   and persistable, for example by conservative saturation.
2. **Cumulative observed-byte overflow leaves spendable budget.**
   Start both charges at `Long.MAX_VALUE - 100`, reserve 10, then record 101
   observed bytes. The arithmetic throws before mutation, leaving 90 bytes
   available despite the observed traffic having exceeded the limit. Rejecting
   hypothetical invalid input atomically is insufficient when bytes have already
   been emitted. Charge conservatively and latch out further reservations.
3. **The deletion integration contract needs explicit object identity and
   check-to-delete protection.** The nine existing receipt identity fields do
   reject substitutions. However, a path plus old hash is not a stable local
   file identity, and a successful final recheck is not a lease on mutable
   remote objects. The implementation must bind an immutable local version;
   the adapter contract must guard local path replacement, identify remote
   versions or enforce immutable publication, and prevent stale eligibility
   from deleting a different local object. Evidence acknowledgement APIs do
   not themselves obtain hashes, fsync acknowledgements, or atomic deletion.

The two numeric cases require extreme synthetic counters. They expose
fail-closed arithmetic behavior, not a claim that ordinary daily recording
will naturally reach those values.

## Other conclusions and boundaries

- Frame/byte arithmetic, exact splitting, rejected-transition atomicity,
  adjacent seams within an epoch, and discontinuity across epochs passed the
  focused checks. The Android adapter remains responsible for reporting real
  interruptions and avoiding invented continuity.
- Cache checks return stop-and-alert without an eviction operation. Estimates,
  free-space races, and durable finalization remain adapter responsibilities.
- The existing policy implements ANY triggers and ALL hard constraints.
  Upload now does not bypass charging, transport, metering, or roaming gates.
  Quota remains a separate operation which the coordinator must apply before
  every bounded I/O operation. This is a documented first slice, not the full
  configurable policy UI.
- Quota reservations survive crash reconstruction conservatively; retry bytes
  remain charged and active reservations prevent window rollover. A serialized
  persistent adapter must also prevent older snapshots from replacing newer
  ones and fence stale workers after restart. No durable store exists here.
- Production APIs inspected are basic Java APIs available in Java 8. Source
  inspection is not an actual Java 8 or Android compilation. The installed
  environment has no `ct.sym` Java-release signature archive, so source/target
  compilation alone must not be described as platform API verification.
- The original review runs inherited CPU 6 and nice 19. They took 2.69 seconds
  for the author's suite and 2.37 seconds for the independent suite; generated
  build files occupied 308 KiB. The first review observer did not enumerate
  child processes correctly, so its RSS sample is intentionally not evidence
  of a verified tree-memory peak. JVM heap/metaspace/code-cache caps were kept.

No SDK, dependencies, network, keys, remote repository, or actual deletion was
used. Implementation changes and post-fix evidence are tracked separately.

## Intermediate correction review

Implementation correction: `c062313a4016b6cf367941bd4a4064a94b17febe`.
The independent harness was adapted to the deliberately stricter deletion API
without retaining any evidence-free overload. It now covers all fourteen
receipt identity/version fields, guard invalidation/replacement/consumption,
and 64 ANY/ALL automatic-trigger combinations. The original evidence above is
preserved rather than replaced.

Against sources materialized directly from `c062313`, the adapted harness
reported 13 named cases passing and 1 failing. Both original quota regressions
passed. The remaining result was:

```text
FAIL already-in-flight overflow reconciliation returns false: reconciling observed overflow must also return false
Independent review: 13 cases passed, 1 cases failed
```

Reproducer: with both limits `Long.MAX_VALUE`, reserve 1 and
`Long.MAX_VALUE - 1`. Record 2 against the first reservation; that correctly
returns false. Reconcile `Long.MAX_VALUE - 1` bytes already in flight against
the second reservation. The cumulative value saturates, but this call returns
true because the per-reservation amount fits and the saturated total compares
equal to the limit. It must also return false whenever either cumulative
addition overflows. Stopping further emission does not remove the need to
account already-in-flight bytes.

This run took 2.12 seconds on CPU 6/nice 19. A corrected `/proc/*/stat` process
tree observer sampled 115.17 MiB of child RSS, or 124.67 MiB including the
observer, below the 192 MiB stop threshold. Sampling is not a kernel-enforced
or unsampled-peak guarantee. Host available memory and free disk were checked
before the run; no compiler/test process remained afterward.
