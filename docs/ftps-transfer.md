# Explicit FTPS source candidate (dev6)

Status: source candidate on the reviewed dev5 documentation baseline
`e497f32fd27a28cd28a54c646d71baef61629637`, 2026-10-09. No dev6 binary, installation,
permission grant, real server, actual credential operation or device acceptance.
Source implementation is present; independent dev6 review remains pending.

## One supported route and the actual user flow

Only explicit FTPS is implemented: ordinary FTP greeting, mandatory AUTH TLS,
trusted control TLS before USER/PASS, PBSZ 0, PROT P, binary TYPE I, EPSV and
independently trusted data TLS. TLS 1.2/1.3, platform trust and hostname/IP
certificate matching are mandatory on both channels. Failure never falls back
to cleartext, active FTP, PASV host addressing, SMB or another network. The initial
greeting/AUTH exchange is inherently plaintext; credentials, audio and technical
metadata are sent only inside TLS. `usesCleartextTraffic=false` is not relied on
to enforce this raw-socket protocol.

1. The user supplies an existing hostname/IP, explicit-FTPS port (default 21),
   username, existing absolute remote directory and password in native controls.
   Initial profile/path/password syntax is bounded printable ASCII. There is no
   remote-account creation, server configuration, trust override or test-login
   button. Saving a profile does not enroll recordings.
2. Completed WAVs are paginated using the same committed-segment allowlist as
   SAF export. The user selects one and confirms the shown saved destination.
   The exact displayed Page object, index and destination revision must still
   match the coordinator when accepted. Newer lists/destinations invalidate old
   dialogs. Unsaved field edits do not redirect existing jobs. Queue identity is
   unique `(source id, destination revision)`, including terminal history.
3. Only explicitly queued recordings transfer. Automatic enrollment is OFF.
   Upload triggers when runnable pending WAV bytes reach the existing policy's
   9,600,000-byte threshold, or when the user requests Upload queued now. That
   action also resumes a paused queue. Age and recording-stop triggers are not
   wired here; threshold/settings UI, cellular quotas and bulk enrollment remain
   future work. A small tail below threshold can wait until Upload now is tapped.
4. Charging and an unmetered, non-roaming, non-suspended, unambiguous Wi-Fi route
   remain mandatory for initial upload, recovery and Upload now. Cellular and VPN
   routes are blocked. A local NAS Wi-Fi route need not be the default network or
   have Internet validation. Exactly one eligible Wi-Fi is required. DNS and all
   TCP sockets are bound to that Android Network, without process-wide routing.
   EPSV data sockets use the same resolved control peer, never a server-supplied
   alternate host. Known non-Wi-Fi public transports are rejected; platforms newer
   than API 37 fail closed pending review.
5. On API 37 the GUI requests local-network permission only after a Queue/Upload
   action. The response starts nothing by itself; the user is asked to review and
   tap again. No permission was actually requested or granted during development.
   Pause cancels owned sockets immediately and then persists the pause on the
   queue owner. An interrupted attempt remains visible and preserves originals.

Destination revision is SHA-256 over bounded host/port/username/base-directory
identity. Credentials may be updated for the same identity without redirecting
queued work. Old revisions remain pinned to their original destination. WAV plus
technical metadata is disclosed in the queue confirmation: capture/run identity,
format, input route, timing/sequence and hashes. Audio is never automatically
selected from arbitrary paths or uncommitted `.part`/temporary objects.

## Durable intent and conservative recovery

`FtpsQueue` owns a no-backup app-private SQLite database with foreign keys,
`synchronous=FULL`, transactions and bounded history (500 queue rows, 32 profiles).
`FtpsCoordinator` has one background worker in the default process, with a single
owned active JobService invocation. UI commands, database access and queue
transitions use that owner. Source id/size/WAV hash/metadata hash and exact
profile revision are persisted. App-private `.ready` source identity and content
are checked again before network work and after transfer. This is durable-intent
code, not proof against every Android storage failure.

Before DNS or a socket is created, one transaction records RUNNING and a fresh
attempt name bound to source/hash/destination plus a random UUID suffix. New
attempts use MKD and require success before CWD/STOR; an existing-directory error
blocks writing. Three fixed names are used inside that attempt: `audio.wav`,
`segment.v1.bin` and `delivery.v1.txt`. The transport metadata wraps bounded local
segment metadata; the marker names exact attempt/source/profile and expected
sizes/hashes. No source path is interpreted as a remote command/path.

After any started attempt, success uncertainty permits only readback of the same
attempt. RUNNING recovers to RECONCILE. Recovery never issues MKD, STOR, REST,
rename or delete. Maximum three read-only reconciliations use 1/2/4-minute
backoff; Android may defer them further. Missing/incomplete/mismatched remote
content, permanent protocol rejection or TLS trust failure blocks the row.
Transient failures can consume the remaining readback attempts. No automatic
replacement directory, blind overwrite or in-place upload resume is provided.
This deliberately favors preserving data over recovering every interrupted
upload. Even failure before a useful upload can leave a terminal row requiring
future explicit repair support.

States visible in receipts are QUEUED, RUNNING, RECONCILE, NEEDS_CREDENTIALS,
BLOCKED and VERIFIED_AT_TIME. A row retains its attempt, destination, source
hashes, result metadata/marker hashes and verification timestamp. Supplying
credentials wakes only NEEDS_CREDENTIALS rows for that exact profile. Already
BLOCKED auth/TLS/partial rows do not reset automatically. There is no row-removal,
replacement-attempt or remote-cleanup UI in this version. Limits fail clearly;
they never prune recordings or receipts.

Native JobScheduler now provides a charging-only wake. Its UNMETERED shorthand
requires validated Internet; even a custom request can be limited to the UID's
default network. Both can strand local-only NAS Wi-Fi. The existing runtime gate
still requires and selects eligible unmetered Wi-Fi before password retrieval,
DNS or TCP; every socket remains bound to that exact Network. Unsafe/unavailable
routes durably defer every runnable row for five minutes, preserving longer
retry times. Upload now and reopening do not reset that delay. This can wake a
job without usable Wi-Fi, but it performs no secret unlock or network I/O then.
Persisted scheduling uses RECEIVE_BOOT_COMPLETED; there
is no app boot receiver and it never restarts the microphone. Process/screen
reopening reschedules retained authorized work, covering a crash between queue
commit and scheduler submission. Main-thread schedule serials and active-job
checks prevent an old scheduling request from replacing a newer active job.
Stop/completion callbacks are invocation-owned. These source guards still need
real device lifecycle tests, including force-stop and OEM scheduling restrictions.

A job has a 180-second deadline, 10-second socket/DNS bounds, at most two owned
raw sockets and one watchdog. Explicit stop/unsafe callbacks close raw sockets
rather than waiting for TLS shutdown. Capabilities supplied in callbacks are
checked immediately, even before manager snapshots catch up. Periodic checks
recheck charging, permission and route; normal TLS close sends close_notify while
the watchdog can still force-close TCP. Races with in-flight kernel I/O cannot be
made a zero-byte guarantee at the instant a charger/network state changes.

## What verification establishes

Success requires complete authenticated byte-count/SHA-256 readback, not SIZE,
LIST, final FTP status or a server-reported hash alone. WAV, metadata and marker
are each read back; all three are read back again after marker upload. Source
immutability/content is rechecked. Bounded 32 KiB buffers stream WAV bytes; no
whole recording is loaded into memory. Control input/commands are also bounded.
Truncated, extra, same-size-corrupt, unreadable or interrupted data cannot produce
a success receipt.

VERIFIED_AT_TIME means those named objects returned the expected bytes during
these checks. Separate FTP operations are not an atomic snapshot. A server can
change objects afterward or between checks; generic FTPS supplies no immutable
version, compare-and-swap/fencing, reliable fsync or future-integrity proof. A
successful MKD reply also relies on honest server semantics and is not distributed
ownership against a malicious server or another actor modifying its namespace.
The marker is descriptive evidence, not a server durability certificate.

No `DeletionGate.Receipt` is produced, no deletion eligibility is inferred, and no
local/remote delete API is added. All originals remain for every outcome. Existing
DeletionGate and CachePolicy are unchanged. CaptureEngine has only a status-text
edit removing its obsolete claim that no independent upload occurred;
RecordingService is byte-identical to the baseline. Existing cache-full denial
still stops/fails recording visibly instead of evicting old audio. Uploaded files
continue consuming the spool. At default 16 kHz mono PCM16, 512 MiB is roughly
4.66 hours before headers/metadata/reserves, not a full day of retained audio.
The user must provision enough space; this slice does not solve retention.

## Credential design (source only)

Default passwords are process-session-only and are not in preferences, queue
rows, logs, saved instance state or autofill. A separate unchecked Remember box
explicitly asks to create/use an app Android Keystore AES-256-GCM key and save
ciphertext in the no-backup database. The destination revision is authenticated
as AAD, IVs are randomized, and use requires an unlocked device. Missing/invalid/
locked keys fail closed; no plaintext fallback. Saving again without Remember
replaces that profile's saved ciphertext with a session secret. Secret array
copies are cleared where owned; Java/UI/crypto internals and transient PASS
strings do not offer a perfect memory-erasure guarantee.

No key is generated by static initialization, construction or source checks.
Actual user consent and runtime Android behavior are still required. No external
account credentials were read, generated, stored or tested. The APK signing key is
unrelated and is not referenced or reused for transport TLS. No custom CA/trust
manager, certificate bypass, trust-all mode or key export is implemented.

## Source locations and evidence

- `core/FtpsTransfer.java`: bounded protocol and verification engine; injectable
  connection/guard interfaces let host tests run without sockets.
- `core/CommittedSegments.java`: new exact-id load reuses the existing strict
  committed-entry inspection; no expanded path acceptance.
- `android/FtpsQueue.java`, `FtpsCoordinator.java`, `FtpsJobService.java`: journal,
  single worker, policy gates, schedule ownership and retry state.
- `android/FtpsNetwork.java`: network-bound DNS/TCP, independent control/data TLS,
  callbacks and deadline cancellation.
- `android/FtpsCredentials.java`, `FtpsControls.java`: opt-in secure-storage design,
  exact user selection, destination confirmation and honest status/receipts.

Author checks at this source candidate:
- 868 synthetic JVM FTPS assertions using scripted connections and tiny synthetic
  WAVs. Includes successful full readback/reconciliation, duplicate remote names,
  missing/partial recovery, bounded buffers, malformed/oversized replies, AUTH/
  control/data TLS failures, login/PROT/EPSV failure, forbidden fallback, partial
  write/close/final-reply loss, short/extra/same-size-corrupt/zero/unreadable data,
  metadata/marker/later mutation, source mutation with unchanged mtime, traversal,
  attempt binding and cancellation. Counts include repeated checks.
- 27 host SQLite assertions execute production schema, restart, credentials-ready
  and upload-trigger SQL. Check unique/foreign-key constraints, transaction
  rollback, file reopen, idempotent recovery, profile separation, retained attempt,
  terminal states, pause/receipt retention, queue-wide safety deferral and
  integrity_check. Fixture updates
  model Java ContentValues; Java queue methods and Android SQLite are NOT executed.
- Existing export 301, core 7,068 + 14 adversarial cases, storage 389 + 11 cases,
  app helpers 58 + 7 cases, plus app/export/FTPS source-wiring and Java-8 buffer
  linkage checks pass. Wiring checks are textual contracts, not behavior tests.
- All main Java sources compile against the existing official API-37 android.jar,
  Java-8 source/classfile targets. Two deprecation warnings remain for
  ConnectivityManager.getAllNetworks and DnsResolver.getInstance. An initial
  compile rejected a non-public getTransportTypes call; it was removed and the
  corrected source compile passed. Initial failure evidence is preserved.

`docs/ftps-host-checks.json` preserves the original `61b286c` author evidence.
The separately committed [F1 scheduler correction](ftps-admission-fix.md) and
`docs/ftps-admission-checks.json` record the fixed-source tests/digest and original
finding reference. Host API fixtures additionally execute the production job
factory and route admission across modeled API flags 29–37. They are not runs on
nine Android images. Detailed test bounds are in the correction report.
Detailed ignored logs/receipts are in `app/build/ftps-checks/`. The guarded runner
uses CPU 6/nice 19, samples process-tree RSS under 256 MiB, enforces a 12 MiB
feature-worktree bound and 5 GiB free-space floor. It downloads nothing, signs
nothing and runs no credential/network/permission APIs. An optional `--checks`
list supports focused reruns; without it the aggregate runs all host/source checks.
SDK compilation additionally needs the existing `--sdk` and `--resource-java`.

Not established: actual TLS/certificate/data-session reuse interoperability,
server-specific FTP semantics, Android Network/permission/JobService races,
Keystore behavior, SQLite/process/power-loss durability, reboot/force-stop,
background/screen-off/OEM operation, UI usability, real microphone/24-hour
operation, or API-29–37 device compatibility. Source compilation is not those
acceptance tests. See the separate [bounded local mock proposal](ftps-local-mock-plan.md).
