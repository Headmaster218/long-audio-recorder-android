# Explicit recovery of a sealed staged segment

## Narrow production API, not automatic recording recovery

`DirectorySpool.recoverFinalizedPart(exactPartId)` completes publication only for
a `.part` directory that already contains a complete, valid intent, finalized
WAV header, exact payload length, PCM/WAV hashes and checksummed manifest. It
never creates or rewrites a manifest, patches a header, truncates a tail, guesses
a close reason, synthesizes capture observations or overwrites a destination.
STOP, INTERRUPTION, ROTATION, schema version and anchors remain as originally
sealed. Raw full-boundary staging without a manifest still remains uncertain.

The initial checkpoint provided this explicit core API only. The subsequent
[user-triggered app flow](sealed-recovery-app-20261103.md) calls it through an idle
background worker; scan and CaptureEngine never invoke recovery automatically.
It does not restart microphone capture or grant upload consent. Existing scans
still report `.part` conservatively. Device qualification remains pending.

## Ownership and ordering

Recovery requires a fresh exclusive spool owner that has never attempted to open
a capture stage, with no active stage, maintenance lease or unresolved publication.
The idle-only operation holds the owner monitor so open, maintenance admission
and close cannot race its I/O. Successful recovery may be followed by capture
using the already reserved next ordinal. Once capture was attempted, reopen the
owner before any recovery attempt.

1. Check bounded object entries, intent identity, original manifest and full WAV
   hashes; reject an existing destination before writes.
2. Force existing intent, WAV and manifest, then sync the staging directory.
3. Revalidate hashes and exact original journal bytes.
4. Publish using the existing no-replace atomic directory protocol.
5. Sync the spool root; revalidate exact journal bytes and final object hashes.
6. Only then mark the existing logical-byte reservation eligible and confirmed.

Payload and manifest bytes are unchanged, so no extra logical cache charge is
introduced. If sync/publication fails, recording and further recovery on that
owner are blocked. Close, reopen and inspect the actual `.part`/`.ready` state;
do not blindly retry an uncertain rename. Existing `.ready` recovery still uses
`confirmReady` to re-sync and validate. No failed call deletes either object.

The existing private-directory/cooperating-owner assumption still applies.
This does not defend against a hostile process mutating files behind the lock.

## Host evidence

`sh scripts/test-finalized-part-recovery.sh` currently passes **242 assertions
across 27 fixtures**. Fixtures are created by abrupt child-JVM termination before
publication. Coverage includes:

- Original STOP, INTERRUPTION and ROTATION reason retention.
- Byte-identical version-2 capture anchors and production FTPS metadata envelope.
- Seven additional abrupt child exits during recovery, before file/directory
  syncs, rename and post-rename validation; reopen and safely finish/confirm.
- Recovery of the original manifest-sync, stage-sync and pre-publication death
  points, including a complete manifest not yet explicitly forced by its writer.
- Missing/corrupt manifest, altered PCM, placeholder header, unexpected entry,
  identity mismatch, existing destination, symlink and active-writer rejection.
- A post-rename root-sync exception blocks reuse of that owner; fresh ownership
  confirms the already published object without manufacturing another segment.
- Deterministic latch-based actual-thread tests pause recovery at directory sync
  and prove capture open, close, maintenance and second recovery block on the
  owner monitor until publication completes. A duplicate recovery then rejects.

Independent review found no blocking flaw under the documented cooperating-owner
assumption. The suggested actual-thread exclusion tests were added and passed.

All 32 non-SDK host shell suites passed against integrated source in the declared
overlay order, including the earlier 405-assertion abrupt-exit suite, terminal
boundary checks, storage/recovery, cleanup/enrollment and synthetic FTPS codec
fixtures. The first aggregate invocation stopped at the SDK-only consent script
because required SDK arguments were absent; the supported non-SDK selection then
passed. Fresh Android SDK compilation, consent/admission SDK checks and APK build
were not run in this executor.

Synthetic bytes and compiled test outputs stay local. These are host filesystem
and process-exit tests, not torn-write/power-loss tests, real FTPS transfer,
backend acceptance, Android runtime or all-day qualification.
