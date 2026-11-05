# Recording capacity and transfer visibility

The recording screen now shows a read-only capacity estimate during an active
run. It uses the actual running settings, the existing owner-generation charged
spool ledger, filesystem usable space, the unchanged 32 MiB free-space reserve,
and the existing 4096-object bound. It does not reopen the spool or scan storage
on the UI thread. Idle/paused/stopped sessions do not show a potentially misleading
estimate for settings that have not been admitted for recording.

The arithmetic subtracts two finalization reserves and all 16 capture buffers,
then budgets maximum intent and manifest bytes plus a WAV header per additional
whole segment. One object slot is also withheld. It discards partial-segment time
and credits neither pending transfers nor future cleanup. With an empty 512 MiB
spool, ample free space, and 16 kHz mono/300-second segments, the estimate is 55
whole-segment equivalents of time, about 4 h 35 min. This is intentionally below the raw
PCM-only ceiling of about 4.66 hours; it is not an all-day recording claim.

Sampling occurs on the existing storage worker at most once a second after a
successful PCM append. Unavailable measurements and samples older than five
seconds display unavailable/stale rather than a duration. The estimate assumes
steady settings/input and no competing disk consumption: route changes can create
extra segments, other applications may use storage, and hardware/OS/I/O failures
can stop earlier. Zero whole segments means less than one complete segment of
estimated headroom, not that no partial audio can be written. Telemetry errors do
not alter the recorder's storage admission, stop decisions, or cleanup policy.

The FTPS section separately counts detailed records not remotely verified versus
VERIFIED_AT_TIME records. Queued/running/retrying/blocked never imply a verified
copy. Loading/unavailable queue snapshots show unknown counts, not zero.
Counts are per destination record, not unique audio files; unenrolled local
files and compacted history are explicitly distinguished. Verification remains
full readback at a recorded time, not a future persistence guarantee.

No upload trigger, below-threshold tail behavior, cleanup consent, permission,
credential protection, recording visibility, notification or silent-recording
behavior is changed.

## Cloud validation

- `scripts/test-recording-capacity.sh`: 1,042 arithmetic assertions and 12 display/
  state assertions (including unavailable queue snapshots), including disk-bound/cache-bound conditions, exact boundary,
  object capacity, overflow, changed format, stale/absent measurement and idle.
- `scripts/test-capture-engine.sh`: actual CaptureEngine with modeled Android
  boundaries, 7 flows / 360 assertions, real local storage.
- Official installed Android SDK 37 compile and unsigned APK packaging passed.
- All results are host/cloud evidence. No physical microphone, Android UI,
  service scheduling, screen-off/OEM, all-day endurance, real Keystore or user's
  local server has been qualified by these changes.
