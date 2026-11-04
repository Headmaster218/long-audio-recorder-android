# Automatic enrollment survives a slow transfer backlog

## Reproduced behavior and fix

Recording supports one-second segments, but one transfer may occupy the single
queue worker for up to three minutes. Previously, filling the 32-slot finalized
notification allowance disabled the user's automatic enrollment. Recordings were
retained, but automatic transfer could stop until manually enabled again.

The production coordinator now retains consent and posts at most one additional
coalesced recovery task. That task records the existing five-minute capacity
wake. Bounded historical discovery then uses the existing source validation,
destination pin, single-use consent ticket and deduplication rules. Nothing is
uploaded by the notification callback and no unbounded list is retained.

Posting the recovery task from the rejected producer also handles the last-call
race: a producer may pause after failing to acquire a slot while all accepted
callbacks drain. Recovery no longer relies on a later accepted notification.

The bound is 32 publication callbacks plus one coalesced recovery task. Disable
still invalidates tickets immediately and clears the persisted wake; Pause keeps
consent and the pending wake but blocks transfer scheduling. Full queue/history
bounds and all transfer, authentication, cleanup and remote-attempt rules remain.
A process death before the task runs relies on existing startup discovery;
Android force-stop/OEM restrictions can still delay work until the app reopens.

## Tests on actual production code

The SDK-backed `test-finalize-overflow.sh CLASSES ANDROID_JAR` executes the actual
compiled `FtpsCoordinator.published()` and `EnrollmentConsent`, not an extracted
method. Baseline source fails `OVERFLOW MUST RETAIN AUTOMATIC CONSENT`; the fixed
implementation passes 105 assertions:

- Forty distinct real synthetic spool publications produce at most 32 admitted
  notifications plus one recovery task. All forty WAVs remain; eight missed
  publications are found by the actual bounded `FinalizedDiscovery.page` logic.
- Duplicate callbacks are tested separately.
- Disable, Pause, full queue, permit return, and a latch-controlled delayed failed
  acquisition preserve the appropriate state and do not lose the recovery wake.

The fixture replaces Android SQLite and main-thread scheduling boundaries, and
allocates the coordinator without its platform constructor. It proves the
coordinator's requests to those boundaries, not device scheduling, SQLite crash
durability, or complete catch-up execution. Discovery is a real core scan, but
subsequent confirmation/upload is covered by existing separate tests.

Implementation commit `f2ba89fd67a438802feec939e0d8a63c40230c78` passed all 42
previous offline suites, 944 SDK-backed admission assertions, 26 consent
assertions, and a fresh official API-37 unsigned build. The new actual
CaptureEngine fixture independently adds seven flows / 360 assertions, including
queued-PCM drain, Pause→Stop dominance and stop/release exceptions. No recording
bug was found in those seven flows. See [capture fixture scope](capture-engine-host-flows-20261104.md).

No real FTPS, phone, server, power-loss, OEM or all-day test was performed in this
batch. No binary, SDK archive, audio, credential or key is part of this checkpoint.
