# Visible Android recorder source slice

Development version 0.1.0-dev4 adds actual Android app/service source above the
reviewed dev3 JVM policy and storage layers. Independent source/helper review and
an official-SDK unsigned rebuild have passed. Local test signing is separate
from runtime acceptance: no installation, device run, 24-hour capture, screen-off
behavior or battery validation has occurred.

## Behavior and boundaries

Native Activity and microphone foreground service; minimum Android 10/API 29,
compile/target API 37. A visible Start press gates microphone and notification
permissions before starting the service. No boot receiver, sticky restart,
background-start exemption, accessibility, phone-state, network, storage-wide,
battery-exemption or manual wake-lock permission is requested. Notifications
must remain permitted and the recording channel enabled by product policy.

The service requests an ongoing foreground notification with Pause/Stop actions
and an entry point to the GUI. Android 14 may allow the user to dismiss it; a swipe
alone does not stop recording and does not trigger a forced repost loop. The app
controls and normal OS microphone/foreground-service controls remain available
where supported. Notification permission/channel disabling and microphone
permission loss still stop capture under the current product policy. Activity
recreation never starts or stops capture. Pause closes the current AudioRecord
run; an explicit Start in that same process consumes a clean-pause token and resumes
the capture with a new run/uncertain epoch and a new sequence. A new process has
no token and always starts a fresh capture, regardless of stale paused/capture/
epoch preferences. Cross-process resume is unsupported until there is a separately
verified durable identity/recovery protocol. Stop ends the capture. Errors require explicit user action; a dead
AudioRecord is not silently recreated. Android force-stop/process death cannot
be made to run cleanup code; preserved local files remain the recovery source.

The GUI configures client sample rate, mono/stereo, exact segment seconds and
logical spool maximum. Defaults are 16 kHz mono PCM16, five minutes, 512 MiB
spool, and a fixed 32 MiB free-space reserve. Settings must fit a complete segment
and metadata. AudioRecord must accept the exact requested client format.

This slice requests the built-in phone microphone only. Preferred-route success
is not treated as proof: active device and recording configuration are inspected
on reads. Physical device format and client PCM format are recorded separately
in each epoch's bounded actual-input descriptor. Android may convert between
them; no physical 16-kHz recording guarantee is claimed. Unknown configuration
or a client-format mismatch stops visibly. One just-read, unattributable RAM
buffer may be uncommitted; this is reported, not labeled continuous recording.

Capture uses PCM16 short-array reads, explicitly converted to little-endian bytes.
One AudioRecord stays alive across routine file rotation. Sixteen reusable
2048-sample blocks form a bounded queue; the capture thread never waits for the
filesystem. Queue exhaustion stops capture and marks an unknown gap. The writer
thread drains accepted queued PCM through the reviewed spool. Storage failure
preserves files. Production-used `PcmReadAccounting` counts every positive read
before attribution/enqueue and acknowledges a block only after its complete writer
append returns successfully. Final reconciliation occurs after the producer has
stopped and includes held, queued and currently dequeued blocks. Their conservative
difference is reported first in the bounded status message, even when the queue
is empty. A failed append may have already staged a prefix, so this is an upper
bound on unconfirmed samples, never an exact hardware-loss count or permission to
replay. Counter overflow fails closed with an unknown-count warning. Files are never
removed to make space. There is no export, upload or retention implementation.

Recording/routing callbacks and actual configuration changes create uncertain
route/format epochs. Callback/read observation cannot identify the exact sample
of an asynchronous hardware transition; descriptors say so explicitly. A known
policy-silenced block is marked, preserved and followed by interruption. Actual
route leaving the requested built-in input likewise stops. Zero amplitude by
itself is never used to infer Android silencing.

Last status is a bounded SharedPreferences record, not a complete durable event
journal. A clean-pause token is created only after successful in-process shutdown,
consumed once on explicit resume and cleared on start/Stop/error. It is never
serialized or reconstructed from preferences; clearing the disk paused flag is
only cosmetic, not the safety fence. A stale active marker on a new process warns of an unclean previous run;
it does not authorize automatic resume. Runtime error history, OS timestamps,
precise overruns, export UI, headset selection and hardware acceptance are later
work. SharedPreferences may be stale after abrupt termination; per-file reviewed
metadata and staged bytes remain authoritative. App-private storage is not an
encryption claim. Automatic backup is disabled in the manifest.

## Android persistence adapter

Directory sync uses public android.system.Os open/fstat/fsync/close APIs. The
SDK does not expose O_DIRECTORY, so an opened no-follow descriptor is checked
with fstat/S_ISDIR before forcing it. Atomic publication uses the reviewed
private-directory NIO adapter. Every unsupported/failed operation blocks ready
publication. SDK compilation does not prove any real filesystem's power-loss
behavior, directory forcing, locking or atomic-rename implementation.

## Offline Linux build, intentionally unsigned

The standard app/src/main tree, AndroidManifest.xml, resources and Gradle module
are present. No Gradle wrapper, plugin or dependency was downloaded. Optional
Gradle use requires an explicitly supplied installed API-37-compatible AGP
version via -PagpVersion; that path is unverified and may require downloads if
the caller has not provisioned it. Do not run it under a no-download instruction.

The verified local-build path uses the installed official SDK directly:

    python3 scripts/build-android-unsigned.py --sdk /path/to/android-sdk-minimal --deadline-utc <explicit-UTC-deadline>

Run under the authorized CPU/nice limits. The script guards 256 MiB sampled
process-tree RSS, 24 MiB generated disk, 2 GiB host available memory and the main
project's 5 GiB free-disk floor plus 1.125 GiB reservation. It also enforces its
explicit pause deadline and a 90-second per-command bound. No signing key,
keystore, password, network access, device connection or APK installation occurs.

Pipeline: aapt2 resource compile/link; javac with the SDK android.jar explicitly
as bootclasspath and Java-8 classfile target; installed D8 with that Android
library and minimum API 29; APK assembly; zipalign and packaged-manifest dumps.
Production Android code uses anonymous callbacks because the SDK bootclasspath
does not provide javac's LambdaMetafactory bootstrap. No host-JDK fallback or
fake platform stub was added to bypass that failure.

This script output is UNSIGNED and cannot be installed as-is. Signing and package
validation are separate local packaging steps. Independent source review passed;
runtime permissions/lifecycle tests and real-device checks remain gates. Core/storage JVM scripts now select only the core source
package so they do not accidentally compile Android APIs against a host JDK.

Windows intent: keep Java/native Android source and the Gradle layout portable;
a future Windows entry point must locate the equivalent official Windows SDK
tools, use proper classpath separators/quoting and implement equivalent resource
and disk guards. The current guarded Python driver is Linux-only. No Windows
build has been claimed or tested.

## Primary documentation checked 2026-10-09

- [Microphone foreground-service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone): manifest type and permission plus runtime microphone permission; microphone access is subject to while-in-use restrictions.
- [Foreground-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start): visible user flow is the baseline; arbitrary background starts are restricted.
- [Android 14 ongoing-notification dismissal](https://developer.android.com/about/versions/14/behavior-changes-all): ongoing notifications can be dismissible; ordinary dismissal is not treated as explicit Stop or microphone consent revocation.
- [Notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission): the app intentionally requires notification visibility even where Android can technically launch an FGS without that permission.
- [AudioRecord reference](https://developer.android.com/reference/android/media/AudioRecord): short-array PCM16 reads, configured client format, recording/routing callbacks and explicit handling of dead objects; the PCM16 byte-array overload is deprecated.
- [AudioRecordingConfiguration](https://developer.android.com/reference/android/media/AudioRecordingConfiguration): client format and physical device format may differ; policy silencing is exposed explicitly.

The minimal source does not implement every feature in architecture.md. Device
input support, UI accessibility/layout, OEM lifecycle, thermal behavior, power,
very large spool scaling and reliable all-day operation still need measurement.

## Original frozen candidate evidence, before review corrections

Offline SDK build completed on 2026-10-09 with the installed official API 37.0
android.jar and build-tools 37.0.0. The final unsigned artifact is
`app/build/direct-1791565691715649576/recorder-unsigned.apk` (ignored build output).
SHA-256: `5e529ac980526a08354f97e0d09a610a127518cbd0a5593a80d3e3ea6522bdde`.
The same directory contains packaged badging, manifest tree, zip alignment and
resource/R/class/dex evidence. Manifest inspection reports minimum API 29,
target API 37, a non-exported microphone service, exported launcher Activity,
exactly microphone/notification/foreground-service permissions and no network
permission. The unsigned build's sampled observer-plus-child peak was 136.14 MiB.

The unchanged core/storage regressions passed: 7,068 core assertions, 14 policy
adversarial cases, 389 storage assertions and 11 storage adversarial cases. The
targeted NIO Java-8 linkage check passed on the Android-bootclasspath classfiles.
Regression execution/compilation took 5.34 seconds, sampled total RSS 104.54 MiB.
These tests do not execute Android services, Activity lifecycle, permission UI,
AudioRecord, notifications, real audio routing or device filesystem behavior.
That candidate later underwent independent review; see the final receipt below.
The runtime acceptance tests remain required.

## Narrow review corrections

The original two app-review source traces remain in `android-app-review.md` and
`scripts/reproduce-app-review-baseline.py`, against frozen commit 345bbf6. New
production-used pure-Java helpers make process-scoped pause-token consumption and
read-versus-append accounting directly testable without fake Android platform
stubs. Run `sh scripts/test-app-safety.sh`. Those checks exercise helper contracts;
they do not simulate actual process death, AudioRecord, notification dismissal,
permissions or service lifecycle on Android.

Corrective author checks passed 58 app-run safety helper assertions and all prior
7,068 core / 14 policy adversarial / 389 storage / 11 storage adversarial checks.
The helper/regression run took 5.48 seconds with sampled observer-plus-child RSS
107.98 MiB. The preserved historical source-trace script still reproduces both
original bugs against 345bbf6, as intended; it is not a test of the corrected app.

The corrected unsigned SDK artifact is
`app/build/direct-1791567111804337549/recorder-unsigned.apk`, SHA-256
`d864989d28571ce3e8c5ea76f203c2d13b8fc1cbe45c6c34bdddde5c6624890a`.
Explicit Android-bootclasspath javac, aapt2, D8, zipalign, packaged-manifest dumps
and the targeted compiled-buffer linkage check passed. Sampled build RSS including
the observer was 129.83 MiB. No signing, installation or Android runtime test
occurred in that author build. Independent confirmation subsequently passed at
`546b317200bdef06c686aa82037aaef719210638`; see [the final review receipt](android-app-review.md).

## Reviewed dev4 source status

The reviewed production snapshot is `86d926938863a7ddcd23ea53227593dda6e4ef0b`.
The final review preserves the original failure findings and records all six JVM
suites, four source-wiring checks, targeted buffer linkage and an independent
SDK build. The dev4 preparation only updates version metadata and documentation;
production behavior remains that reviewed snapshot. Local test APK signing and
static package verification do not expand the review to Android runtime behavior.


## Dev4 test APK packaging receipt

On 2026-10-09, the reviewed production source was rebuilt with versionName
`0.1.0-dev4` / versionCode `4`, using the installed official API-37 SDK and
build-tools 37.0.0. The version/documentation preparation makes no production
logic changes relative to `86d9269`. One local development signing key was
generated outside Git; key material and passwords are excluded from source,
Git history, recovery archives and delivered files. This is not a release key.

The separate artifact `long-audio-recorder-0.1.0-dev4-debug.apk` is 49,425 bytes.
SHA-256: `c4612c331fd3d03184e56fc2dc8eb5404127ea9842b1075df9ade18529981fe2`.
Official apksigner verification succeeds for the declared API 29–37 range with
one RSA-2048 signer and a verified v3 signature. Final zip alignment passes.
All four uncompressed members match the unsigned build exactly; no extra
libraries, assets, network permission or exported service were introduced.
The package remains `io.github.headmaster218.recorder.android`, min API 29,
target API 37; backup and cleartext traffic are disabled. The test certificate
SHA-256 is `d211d45d69e6965031173483471a5c0282d758f7f61023adfcee14f5853ada19`.

The first signing attempt supplied the same password file twice; the second
read reached EOF. Retrying with the same key and its store password succeeded;
no replacement key was generated. Signature verification, rather than successful
command launch, is the acceptance evidence.

The six reviewed JVM suites passed again (7,068 core assertions, 14 policy cases,
389 storage assertions, 11 storage cases, 58 app helper assertions, seven app
adversarial cases), along with four source-wiring checks and targeted buffer
linkage. Build sampled observer-plus-child RSS was 137.18 MiB; the final
regression/package-verification run sampled 110.71 MiB. CPU 6 / nice 19 and
256 MiB RSS / 24 MiB generated-output guards remained active. These are sampled
observations, not kernel-enforced maximum guarantees.

No installation or Android runtime/device test occurred. Export/upload, verified
backend transfer, deletion/retention, durable cross-process resume, headset
selection and complete runtime event history remain unimplemented. Static
packaging success does not establish recording quality, notification/lifecycle
behavior, screen-off capture, actual disk-full/process/power-loss safety,
battery/thermal behavior or 24-hour operation.
