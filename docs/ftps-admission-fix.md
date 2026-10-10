# F1 correction: local-only and non-default NAS Wi-Fi admission

This is a separate narrow correction to dev6 candidate `61b286c186eec046686b96711ca36a648b78b6ae`.
The original independent finding is preserved, unchanged, in
`android-recorder-ftps-independent-review-20261009/REVIEW.md` outside this worktree.
The original commit and `docs/ftps-host-checks.json` retain its source and author
evidence. Current candidate remains source-only, pending independent re-review.

## Cause and chosen correction

The old JobInfo UNMETERED shorthand requires INTERNET and VALIDATED as well as
NOT_METERED. A local-only NAS Wi-Fi cannot satisfy that requirement. A custom
Wi-Fi request removes the Internet requirement, but older and later AOSP
controllers evaluate the UID's default network, so default metered cellular can
still strand an otherwise eligible secondary Wi-Fi. See
[AOSP JobInfo implementation](https://android.googlesource.com/platform/frameworks/base/+/998a2bbbb59ebc86d1b5ff3d3543cec87f87961c/apex/jobscheduler/framework/java/android/app/job/JobInfo.java#1406),
[earlier connectivity controller](https://android.googlesource.com/platform/frameworks/base/+/dde06fe41d59/services/core/java/com/android/server/job/controllers/ConnectivityController.java#373) and
[later connectivity controller](https://android.googlesource.com/platform/frameworks/base/+/8c9805df96aadbadc2167d2ed4fab5745d1e91a9/apex/jobscheduler/service/java/com/android/server/job/controllers/ConnectivityController.java).

`FtpsJobSchedule.build` now schedules a charging-only wake with NETWORK_TYPE_NONE.
It deliberately does not claim a custom NetworkRequest solves secondary-network
admission. The [public Builder API](https://developer.android.com/reference/android/app/job/JobInfo.Builder#setRequiredNetworkType(int))
defines NONE as no scheduler connectivity constraint, available since API 21.
The production factory uses only APIs available before the minimum API 29 and
has no version-specific scheduling branch through API 37. Existing latency,
persistence and backoff are preserved. System scheduling, app restrictions and
OEM behavior can still defer or block execution; no exact start time or network
privilege is promised, and no restriction bypass is requested.

A scheduler wake is not permission to transmit. Before profile-secret retrieval,
the unchanged production `FtpsNetwork.allowed` checks charging, local-network
permission and the exact unmetered/non-roaming Wi-Fi selection. The later
connection guard checks again before bound DNS/TCP and throughout I/O. Cellular
can remain the default without ever being chosen for this transfer. VPN, mixed
or ambiguous routes remain blocked. No process-default binding, default socket,
plain FTP or TLS change is introduced. No JobParameters default-network assumption
is used; the socket/DNS binding remains explicit.

The route gate was moved ahead of `password(profile)` and even the selected
profile lookup. Missing/unsafe Wi-Fi therefore cannot trigger a Keystore unlock
or a session-password copy. Source-wiring checks assert this production order.
The UI can still display profile labels/ciphertext-presence state; the assertion
is about actual transfer-secret retrieval, not claiming encrypted database bytes
are never loaded for UI state.

## Preventing a retry storm

The first failed safety gate durably updates all runnable QUEUED/RECONCILE rows
with `next_at = MAX(next_at, now + 300000)`. One atomic SQLite statement covers
the entire bounded queue; it does not schedule a one-second job for each of up
to 500 files. Longer per-row retry times remain intact. Terminal rows, attempt
identities and source files are unchanged. Upload now sets a trigger but does
not clear this floor; process recovery does not reset QUEUED/RECONCILE delays.
No eligible route means another check no sooner than five minutes in the same
wall-clock epoch, and Android may defer it further. Wall-clock changes can alter
when a persisted deadline is reached, as with the existing retry timestamps.
Explicitly adding new work can cause another gate check, not unauthorized I/O.

There is no new persistent callback or one-second polling loop while offline.
This favors a small source change over a new background connectivity service.
The runtime Network callbacks and 180-second transfer deadline are unchanged.
Their real-device cancellation/false-cancellation races remain a separate test.

## Tests and their exact limits

The final host admission suite passes 533 nonredundant assertions and executes the real compiled `FtpsJobSchedule.build`,
`FtpsNetwork.allowed` and `FtpsNetwork.route` methods. Narrow Android API fixtures
supply network capabilities, power, permissions and JobInfo-builder semantics.
The fixture explicitly reproduces UNMETERED adding INTERNET/VALIDATED and the
UID-default-only matching limitation, so it rejects both the original shorthand
and a merely custom Wi-Fi request when cellular is default. Tests cover:

- local-only Wi-Fi with INTERNET/VALIDATED absent, including no default network;
- the same Wi-Fi alongside default metered cellular, with threshold and Upload now;
- metered/roaming/suspended/mixed Wi-Fi, two eligible Wi-Fi routes, VPN, no Wi-Fi,
  charger loss, API-37 permission denial, threshold not reached and disconnect;
- exact selected Wi-Fi returned rather than the default cellular network;
- persisted/charging job properties, no scheduler connectivity requirement and
  preservation of a five-minute scheduling delay.

A secret-read spy follows admission in the test harness; blocked cases must not
reach it. The production wiring assertion independently checks the same ordering
in the coordinator. A fixture Network throws if socket creation is attempted.
No real Android credential API or socket/DNS operation executes. The test is
not proof of Android Binder/service/callback behavior. API flags 29–37 exercise
our production branches and the common public contract, not actual OS images.

The host SQLite test now has 27 assertions. Added cases fill a bounded 500-row
fixture, execute the exact production deferral SQL, preserve later/terminal
states, reopen the database, repeat recovery/Upload now, and verify that no
runnable row is immediately due. These are real host SQLite statements, not Java
ContentValues or Android storage-durability tests.

All main sources compile against the existing official API-37 SDK; the same two
pre-existing deprecation warnings remain. The final fix receipt gives exact test
counts, source digests, logs and resource measurements. Existing FTP/TLS protocol,
source retention, cache-full and DeletionGate paths are unchanged. No live FTPS,
actual account/key, permission grant, APK, signing, installation or publication
was performed.

Re-review must confirm the admission and queue-wide delay findings against the
fixed commit. Device acceptance must still check charging local-only Wi-Fi alone
and alongside default cellular on the supported Android range, platform network
blocking, route revocation, callback snapshots, job stop/restart and OEM limits.
The separately gated loopback TLS plan remains a future stage.
