# Authority and notification recovery checkpoint

Source-only development checkpoint, 2026-10-23. Apply these seven source/test files over baseline commit `9befdd37965fdf371b7ed3ff26281efc5228e927`. This document is the eighth file; no runtime data or build outputs are included.

## Minimal fix

- Treat authority-query failures after session admission as authority loss, rather than letting a runtime exception escape a recovery callback. Preserve pre-admission failure semantics.
- Contain notification visibility/publication failures at a narrow boundary. Latch notification failure before requesting session cancellation, retain the existing evidence/ownership cleanup gates, and never infer successful cleanup from a notification failure.
- Invalidate clean-pause eligibility after authority loss. Ensure service-stop is attempted even if foreground cleanup fails; cleanup failures still propagate.
- The notification-failure latch persists for the entire Service instance. Conservatively reject another start until Service recreation, even if later permission queries succeed.

## Verification and limits

Verification performed against the frozen candidate:

- Finite host checks passed: 470 recovery-session ordering assertions, 15 notification-boundary assertions, 1,310 interruption-policy assertions, and 656 integration evidence-gate/journal/policy assertions.
- Four authority-fault reproductions against the old code each failed with `SecurityException` and exit status 1. The fixed candidate received bounded review approval.
- Official Android API 37 resource processing with aapt2 and Java compilation passed. Two existing FTPS deprecation warnings remain.

These are host checks and a resource/Java compile, not DEX generation, an APK build, Android device acceptance, durability qualification, or proof of all-day recording reliability. No tests or compilation were rerun while assembling this source-only checkpoint.

## Publication scope

Destination: `Headmaster218/long-audio-recorder-android`, on a new development branch only. Publication was explicitly approved on 2026-10-23. This checkpoint is not a release, merge, deployment, or device installation.
