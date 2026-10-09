# Independent WAV/storage review

Reviewed production commit: `6b60ce5bed79985024ff77984a76f52ce90955b6`.
Date: 2026-10-09. Scope: pure-Java writer and local spool contracts, synthetic
fixtures on the current JVM/filesystem. No SDK, Android build/device, downloads,
real recordings, network, power interruption, or actual recording deletion.

**Final result:** all three findings below were corrected and independently
rechecked at `9d6ee022aa9b3559151d59ca6e2b3afcfea85c2a`. All 11 expanded storage
review cases, the original storage/policy suites and the targeted linkage check
pass. The original failures are preserved as historical evidence; see the final
recheck section for exact results and remaining platform limits.

## Result: corrections required

### 1. Newly created spool roots lack parent-directory durability ordering

`DirectorySpool.java:84-92` creates a missing root, possibly including missing
ancestors, then syncs only the root. Syncing a directory's contents does not
establish persistence of the entry in its parent that makes that directory
reachable. Later segment publication also syncs only this root.

The independent protocol-order test reproduced successful admission of a newly
created root with no parent sync. This is an ordering omission; no actual power
loss was attempted and no claim is made about what this specific filesystem
would lose during a particular crash.

Narrow safe choices: require an existing, durably established root with an
explicit caller contract, or correctly persist every newly created ancestor
entry before accepting PCM. Do not silently retain `createDirectories()` as
though syncing only the leaf establishes that guarantee.

### 2. Java-8 classfiles contain a Java-9 ByteBuffer method reference

`DirectorySpool.java:214` calls `block.clear()`. Compiling with the installed
JDK 21 and `-source 8 -target 8` produces classfile version 52 referencing:

```text
java/nio/ByteBuffer.clear()Ljava/nio/ByteBuffer;
```

That covariant-return method is not the Java-8 `Buffer.clear():Buffer` API.
The resulting call is not Java-8-runtime compatible. A narrow source correction
is to invoke the inherited API through `java.nio.Buffer`, while retaining a
real target-platform API check as a future acceptance gate.

Reproduce the targeted bytecode check after compilation:

```sh
python3 scripts/check-java8-buffer-linkage.py app/build/storage-review/classes
```

This checker deliberately covers only the known NIO covariant-buffer trap; it
does not certify all Java-8 APIs or any Android API level. Documentation already
states that Android and actual Java-8 runtime compatibility are unverified.

### 3. Partial recovery ignores unexpected object entries

`DirectorySpool.java:276-284` checks the partial directory's intent and WAV,
but does not enumerate other entries. A synthetic `.part` object with an extra
`unexpected-unverified-audio` file is reported `PRESERVED_PARTIAL` rather than
`CORRUPT_PRESERVED`, contrary to the documented unexpected-entry reporting.
No bytes were removed and no upload permission was granted, so this is a
reporting/invariant gap rather than demonstrated data loss.

Validate the allowed partial-directory entry set without deleting anything.
If a manifest exists from interrupted finalization, inspect its integrity and
binding rather than silently ignoring conflicting or corrupt finalization
evidence. Staging without a manifest may still report only the explicitly
documented weak, observed-prefix salvage information.

## Independent test evidence

`sh scripts/test-storage.sh` passed the author's 361 storage assertions. The
existing policy regression suites passed 7,068 assertions and all 14 named
policy adversarial cases against the same compiled production sources.

`sh scripts/test-storage-adversarial.sh` reported:

```text
PASS arbitrary offset buffers preserve exact mono/stereo/32-channel frames
PASS partial-frame epoch change preserves all bytes and becomes terminal
PASS format changes start a new uncertain epoch
PASS cache denial precedes payload growth
PASS raw/full hashes and checksummed identity substitution
PASS failure after actual rename requires recovery confirmation
FAIL new spool root requires durable parent reachability: created root admitted without syncing its parent directory entry
FAIL unexpected partial entries are reported and preserved: partial scan silently ignores unexpected object entries
Independent storage review: 6 cases passed, 2 cases failed
```

The separate targeted classfile check also failed on the ByteBuffer reference
above. The independent tests/report do not modify production implementation.

## Resource evidence

All test processes inherited CPU 6 and nice 19; JVM heap/metaspace limits were
32 MiB each, code cache 8 MiB. A process-tree observer stopped on a sampled
128 MiB limit or 30-second timeout; neither guard triggered. Host available
memory and free disk were checked before each run.

- Original storage compile/test: 4.02 seconds; sampled child peak 97.25 MiB,
  or 106.75 MiB including its observer.
- Independent compile/test: 2.90 seconds; sampled child peak 110.55 MiB,
  or 120.05 MiB including its observer.
- Policy regression execution: 0.31 seconds; sampled child peak 34.56 MiB,
  or 44.06 MiB including its observer.
- Checkout disk use rose from approximately 3,244 KiB to 4,364 KiB before
  report/checker additions, well within the 8 MiB new-disk allowance. Fixtures
  are tiny generated bytes and are preserved in ignored build directories.

Sampling is not a kernel-enforced or unsampled-peak guarantee. CPU 6 was
released after testing and no test/compiler process remained.

## What the passing results establish, and what they do not

The reviewed splitting logic preserves complete interleaved frames across
arbitrary successful buffer boundaries and segment cuts. Partial-frame stop or
epoch change is terminal and preserves staged bytes. Frame/format epoch seams,
raw versus full-WAV hashes, exact RIFF lengths, cache checks before growth,
identity validation, and recovery after a rename with uncertain completion
were independently exercised.

The implementation author exercises 15 named operation hooks, including file
and directory sync/publication boundaries. Those tests establish control-flow
responses to injected exceptions; they do not prove filesystem/controller
durability, actual process-death behavior or Android support. The exclusive
private-directory assumption remains necessary for no-replace publication.

`java.nio.file` availability, directory forcing, file locking and atomic moves
need actual Android/API/filesystem validation. Each append currently performs
a complete spool-size walk and forces the WAV; all-day latency, scaling, battery
and queue behavior remain unmeasured. No 24-hour capture readiness is implied.

After coordinated corrections, rerun both storage suites, the policy regressions
and the targeted linkage checker, then append the exact corrected commit and
results here without replacing this original failure evidence.

## Final independent recheck

Corrected production commit: `9d6ee022aa9b3559151d59ca6e2b3afcfea85c2a`.
Original review/test evidence commit: `ff3dddc846db03d5440e7cd13b05e6c36d33a88b`.
No production code was changed during this independent recheck.

### Verified corrections

1. Initialization creates at most a missing leaf under a caller-established
   durable parent hierarchy. Root sync is followed by parent sync before
   admission, including for an existing root. Parent-sync failure releases
   ownership and admits no audio; missing ancestor directories remain absent.
2. WAV verification invokes `clear()` through `java.nio.Buffer`. The targeted
   compiled-class check finds no Java-9 covariant ByteBuffer references.
3. Partial recovery enforces the allowed entry set. If interrupted finalization
   left a manifest, its checksum, intent binding, exact WAV header/length and
   both content hashes must validate. Failures are reported as corruption,
   preserving all evidence. Even a valid partial object is never promoted or
   granted upload permission by a scan.

The independent suite also now exercises a single 20,004-byte input across both
8 KiB storage-call boundaries and 5,000-frame segment boundaries, verifying
byte-exact reconstruction and the short final tail.

### Final command results

```text
sh scripts/test-storage.sh
PASS: 389 WAV/storage assertions; synthetic local JVM fixtures only

sh scripts/test-storage-adversarial.sh
PASS arbitrary offset buffers preserve exact mono/stereo/32-channel frames
PASS large input crosses both 8 KiB chunks and exact segment cuts
PASS partial-frame epoch change preserves all bytes and becomes terminal
PASS format changes start a new uncertain epoch
PASS cache denial precedes payload growth
PASS raw/full hashes and checksummed identity substitution
PASS failure after actual rename requires recovery confirmation
PASS new spool root requires durable parent reachability
PASS unexpected partial entries are reported and preserved
PASS parent-sync failure blocks admission and absent ancestors stay absent
PASS interrupted manifests validate before conservative recovery reporting
Independent storage review: 11 cases passed, 0 cases failed

CoreTest against the same compiled production sources
PASS: 7068 deterministic assertions; Java policy core only

AdversarialCoreTest against the same compiled production sources
Independent review: 14 cases passed, 0 cases failed

python3 scripts/check-java8-buffer-linkage.py app/build/storage-review/classes
PASS targeted Java-8 buffer linkage check; full platform compatibility remains unverified
```

All Java execution used the existing JVM 21, Java-8 source/classfile targets and
32 MiB heap/metaspace limits, under CPU 6/nice 19. The original storage
compile/test took 4.58 seconds with a sampled observer-plus-child peak of
120.73 MiB; the independent compile/test took 3.27 seconds and 119.84 MiB.
Policy regression execution took 0.31 seconds and 46.28 MiB. No 128 MiB/time
guard triggered. Total worktree size before this report update was 6,212 KiB,
within the 8 MiB ceiling; generated fixtures remain preserved. `git diff --check`
passed, and no compiler/test process remained after completion.

No identified finding remains open for this limited Java/local-filesystem
slice. This does not certify Java-8 runtime or Android API compatibility beyond
the targeted linkage issue, physical crash/power-loss durability, hostile/shared
directories, sustained performance, battery behavior, real devices or 24-hour
recording. Those acceptance gates remain explicitly unimplemented or untested.
