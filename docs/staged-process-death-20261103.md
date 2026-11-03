# Staged audio after abrupt host process exit

## Finding and scope

The terminal-aware writer holds at most one full segment in `.part` until the
next nonempty append or explicit close. This is not an in-memory-only buffer:
`DirectorySpool.open` syncs the intent, initial WAV, staging directory and spool
directory; each successful `Stage.append` forces PCM file data and metadata.
No extra synchronization was added because those operations already exist.

An interrupted `.part` has no trustworthy terminal declaration simply because
its length equals the configured segment size. The current scanner preserves
it, reports observed whole frames and trailing bytes, and does not promote it.
Length and a placeholder header cannot authenticate staged PCM or reconstruct
producer observations that existed only in memory. Automatic salvage would
require a separately designed, versioned recovery-evidence protocol and explicit
uncertain-gap semantics. This checkpoint deliberately does not invent STOP,
continuity, hardware-frame mapping or lost capture anchors.

## New executable evidence

Run `sh scripts/test-staged-process-death.sh` on a Java compiler/runtime host.
The test starts fresh child JVMs and calls `Runtime.halt(73)` without executing
writer shutdown, spool close, preserve, finally blocks or shutdown hooks. Each
child has a 20-second bound; an unreached halt or a different exit code fails.

Twenty-one scenarios cover:

- A one-byte incomplete frame, a six-byte partial segment and an exact eight-byte
  full segment after append returns.
- Stop finalization immediately before header patch, final WAV sync, WAV hashing,
  manifest write, manifest sync, staging-directory sync, publication rename,
  root-directory sync and final validation, for both partial and exact-full tails.

The parent reacquires the released OS lock and uses production recovery and
catalog code. It checks exact deterministic synthetic PCM bytes, frame/trailing
counts, ordinal reservation and refusal to reuse sequence zero. Staged objects
remain absent from the committed catalog and are rejected by confirmation,
maintenance and enrollment. Objects already renamed `.ready` can be revalidated
and resynced; their STOP came from the child's explicit Stop, never inference.
Independent assertions check placeholder/finalized headers and manifest presence.
Snapshots before and after recovery prove the scan and checks leave regular-file
names and contents unchanged in these scenarios; filesystem metadata is not compared.

Result: **405 assertions across 21 abrupt child exits passed**. Independent review
also identified and fixed a test-harness lease leak that could have hung the
parent process if admission rejection regressed. Synthetic
fixture data and compiled outputs stay local under ignored `app/build/`.

## What remains unproven

This kills a process, not the host kernel or storage device. Dirty kernel pages
can survive process exit, so even the pre-fsync cut points may remain readable.
The result does not prove stable storage across power loss, Android lifecycle or
OEM behavior, microphone continuity, cross-process capture resume, automatic
partial salvage, FTPS delivery, performance or all-day operation. No production
behavior or on-disk schema changed in this checkpoint.
