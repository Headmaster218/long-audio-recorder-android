# Persistent GUI settings reconstruction draft

Status: UNTESTED. Source preparation only; not compiled, installed, or executed.
Base: public dev7 commit 167f7f1b5256dcea8589117d1a1b2243cebd293b.
This overlay is a reconstruction proposal, not recovered dev9 source.

## Contract

An explicit Save stores schema version 1 plus sample rate, channel count, segment
seconds, and cache MiB in a separate private preference namespace. Start uses the
displayed settings without saving. Opening the app and Save never record or
resume. Invalid saved fields fall back as one complete defaults snapshot, with a
warning and no automatic repair. Unrecognized schema versions or types block
Save without changing the record; the guard is checked again at Save time.

Save submits all fields through one SharedPreferences editor commit and reports
success only when commit returns true. A failed commit does not establish durable
storage; Android may already have changed its in-memory preferences. The UI
therefore reports uncertainty rather than promising rollback. Reads that throw
also prevent blind Save. The small synchronous commit is deliberately minimal;
UI latency and actual device durability remain unverified.

Only the four numeric fields are saved. No credential, endpoint, user identity,
device identifier, recording-active flag, capture ID, resume token, or queue
setting is added. Existing provenance, status, credentials, and queues are
unchanged. Built-in microphone enumeration remains the existing per-run logic.

The existing immutable RecordingSettings object is captured before permissions.
Stop/Pause clear pending permission authority before dispatching service control.
Permission request IDs are process-local, non-reused, and distinct from FTPS's
6201 code. Duplicate and stale callbacks cannot consume another pending request.
Activity destruction cancels authority; ordinary recreation can restore numeric
view text but cannot restore authority to record. Exhausting the reserved request
range fails visibly rather than recycling IDs. The existing foreground-visible
and permission checks still apply.

Intent decoding retains dev7 defaults for missing extras. Making it strict would
change existing internal START compatibility and is unnecessary for GUI
persistence, so that proposed hardening was deliberately omitted. Service,
capture runtime, manifest, network, queue, and status files are not modified.

## Review and verification still required

The fixture contains a tiny host-only android.content.Intent substitute; never
include testFixtures/settings in the application source set. Later authorized
host compilation should combine existing core sources, RecordingSettings.java,
RecordingSettingsStore.java, and these two fixture files in an isolated output
directory. No runner or compilation was executed during this preparation.

Focused source covers defaults, complete-record round trip, invalid/missing/wrong
types, boundary validation, segment-capacity rejection, save failures,
future-version preservation including changes after load, duplicate/stale
permission callbacks, cancellation, and Activity-instance identity separation.
The model test does not execute MainActivity or Android SharedPreferences.

Independent review must check Activity wiring and then authorized device tests
must cover Start -> Stop/Pause -> permission grant; cancel -> new Start -> old
callback; permission denial; notification permission; rotation and process death;
Save versus unsaved edits; save failure; actual relaunch persistence; and current
built-in microphone enumeration. Framework permission-dialog timing may require
another explicit Start when the Activity is not resumed, matching dev7's guard.
Run the existing app-review and applicable regression checks only when authorized
and resource budget permits. There is no 24-hour reliability claim.
