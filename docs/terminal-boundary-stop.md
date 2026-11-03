# Exact rotation-boundary Stop without fabricated EOS

## Defect and fix

The eager writer publishes a full segment as ROTATION during append. If Stop
arrives at that exact boundary, there is no staged segment left, so no STOP
metadata is emitted. A backend must not infer end-of-stream from that ROTATION.

Current CaptureEngine now explicitly uses `PcmSegmentWriter.terminalAware`.
It retains at most one full segment in its existing staging file until the next
nonempty append or explicit close. Another append publishes ROTATION before
starting the next segment; Stop publishes STOP directly; interruption or epoch
change publishes INTERRUPTION. Empty append does not publish, and stopping an
empty writer creates no zero-frame object. Existing ready files are never
rewritten. No new metadata/transport schema or additional network permission is
introduced. STOP with frameCount equal to targetFrames is already schema-valid.

The public legacy constructor remains eager for existing callers, old fixtures
and byte/behavior compatibility. Old captures remain exactly as they were: a
ROTATION-only capture is still nonterminal. The new factory is explicitly wired
in the latest CaptureEngine overlay, and the assembly helper verifies the chosen
committed overlay manifest. Historical commits without that manifest still use
the original six-overlay sequence.

## Tradeoff and failure boundaries

One full segment becomes upload/cleanup eligible only after more PCM or an
explicit close. Its PCM is still synced by the existing spool append path; no
unbounded memory buffer is added. A crash/owner loss before publication leaves a
full `.part` object with uncertain recovery state, not an upload-ready STOP or
proof of end-of-stream. Existing recovery scanning preserves it and does not
promote it. A storage/manifest/listener/admission failure cannot be repaired by
calling Stop on the now-failed writer. Already-published ROTATION files remain
unchanged. Interrupt/restart is not Stop, even if no new PCM follows the epoch
change. This favors honest terminal evidence over eager final-file publication.

CaptureEngine still calls stop only for an explicit STOP with no recorded
failure; other outcomes call interrupt. STOP is the producer's declaration about
the accepted stream, not proof of physical microphone continuity, resource
release, durable remote storage or successful backend import.

## Fresh finite checks

- `sh scripts/test-terminal-boundary.sh`: 765 assertions across 47 local-storage
  scenarios, including varied append slicing, exact and partial tails, empty
  Stop/append, legacy behavior, interruption, epoch change, observed stereo
  splitting and held-boundary reads, restart without subsequent PCM, incomplete
  frames, manifest/listener/cache failures, and held-stage recovery rejection.
  Its source-wiring checks are narrow static checks, not Android execution.
- `sh scripts/test-ftps-exact-boundary.sh FRESH_OUTPUT`: actual production
  writer/spool/codec generates three 4-second, 16 kHz mono synthetic silence
  deliveries. Reasons are ROTATION, ROTATION, STOP, with the final STOP segment
  exactly full. 126 assertions verify metadata, anchors, hashes, byte readback and
  combined 192,000-frame PCM. Generated objects stay local.
- All 30 supported non-SDK host shell suites passed. Existing legacy storage,
  transport, enrollment, cleanup, capture-anchor and recovery suites remain
  passing. The assembly suite passes six tests, including historical refs.

No fresh Android SDK compilation/APK, real microphone/lifecycle test, physical
power-loss experiment, OEM test or all-day qualification is claimed. The exact
boundary fixture uses artificial observations and cannot establish hardware
frame mapping. The earlier real-TLS run is not counted as a rerun of this change.
