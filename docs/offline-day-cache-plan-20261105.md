# Explicit 24-hour offline cache planning

The UI now offers **Plan 24-hour offline cache…** without changing the existing
512 MiB default or any saved setting automatically. The user reviews the plan and
chooses **Apply** to populate only the cache-size field. Save and Start remain
separate explicit actions. No recording, upload, deletion, cleanup consent,
credential change, permission request or disk preallocation is caused by planning.

The calculation uses the displayed sample rate, channels and segment duration,
24 hours rounded up to complete segments, maximum intent/manifest sizes, WAV
headers, finalization reserves, pending PCM buffers, retirement metadata and an
additional 5% planning margin. Existing spool bytes are added to the proposed
cache maximum. Required free space includes the unchanged 32 MiB reserve.
With no existing files, 16 kHz mono and 300-second segments, it proposes 2774 MiB
and requires 2,941,661,178 currently free bytes (about 2.74 GiB). Stereo, higher
sample rates and existing recordings increase the cost. Nothing is allocated yet.

An explicit check performs two bounded read-only inventories in a background
thread. Each allows at most 4096 root objects and 32768 files, with no recursive
walk beyond segment directories and no symlink following. The same SpoolUsage
key/charge rules as real capture reject duplicate segment-ordinal reservations.
Changed byte/object/file counts, unreadable or structurally unsafe entries fail
planning rather than inventing a successful result. The plan reserves four object
slots beyond existing objects and planned segments. Very short segments can exceed
the 4096-object ceiling even with ample disk; the user is told to choose longer
segments, for example 300 seconds. Existing files are never removed to make room.

If currently available space is insufficient, no Apply option is offered. The
message gives required and observed free bytes and suggests lower sample rate,
mono, or the user's own space management. At recording time the original storage
admission remains authoritative. If storage fills, recording stops and preserves
files. No circular overwrite or assumption of continuous networking is introduced.
The transfer queue has its own independent limits and gates; planning does not
promise immediate enrollment/upload of every segment or enable cleanup.

These checks are snapshots, not a lock or a storage reservation. Changes after
inspection, route changes producing extra segments, filesystem behavior, unhealthy
existing files, battery, Android restrictions and hardware/I/O failures can prevent
Start or interrupt recording earlier. The 24-hour figure is a capacity budget,
not evidence of all-day capture reliability. It also does not validate audio hashes
or repair old recordings.

Pause/rotation invalidates the UI's read-only planning ticket immediately. Late
results cannot open a stale dialog or release a different request's controls.
Both success/failure callbacks and thread-startup failures release their own
request. Background file inspection may finish after cancellation but cannot
change recording/settings/files or apply a stale result.

Validation: `scripts/test-offline-day-plan.sh` passes 134 host assertions covering
format/duration matrix, free/cache/object bounds, overflow, exact free boundary,
existing files, non-mutating inventory, links/nesting, concurrent capacity changes,
duplicate ordinal handling, and stale/paused planning tickets. These are arithmetic,
filesystem and UI-ticket model tests, not Android Activity/device execution.
Official SDK 37 compilation and unsigned APK packaging are also checked separately.
Physical-phone UI, screen-off/OEM and 24-hour endurance remain untested.
