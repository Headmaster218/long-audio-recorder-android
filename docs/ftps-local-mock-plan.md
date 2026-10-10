# Proposed bounded local FTPS mock test (not authorized or executed)

This is the next validation proposal after independent source review. Executing
it requires a separate explicit authorization for local TLS/network mocks and
synthetic test-only keys. No dev6 APK/signing/device stage is included.

## Proposed scope and resource bounds

- One temporary fixture server and one JVM production-core client on loopback
  only (127.0.0.1 or ::1), maximum two listening sockets and four connected
  sockets. Bind ephemeral ports, never 0.0.0.0 or a LAN address. No DNS or remote
  server, no user audio or external account. No live Android services/permissions.
- Use only already installed Python/Java cryptography/runtime tools. First inspect
  availability; if adequate TLS fixture support is absent, stop. No package,
  dependency, binary, SDK, emulator or certificate downloads/installations.
- Generate ephemeral fixture-only CA/server keys only after authorization, in a
  dedicated app/build test directory; never read/reuse any APK signing material
  or system/user account key. Certificates have localhost SANs and short validity.
  Supply the fixture CA only to the test Connector's in-memory client trust store.
  Never alter the OS/app production trust store or production TLS validation.
- Use a declared literal fake username/password, never a real credential. Test
  artifacts may contain only this clearly synthetic value; redact PASS commands
  in logs. Tiny synthesized WAVs (at most 128 KiB each, <=2 MiB total payload
  across tests), protocol bytes <=8 MiB total, <=100 control sessions.
- CPU 6, nice 19, sampled total process-tree RSS <=256 MiB. Test subdirectory
  <=4 MiB, combined existing feature tree + test work <=12 MiB, free disk >=5 GiB.
  One run at a time, <=10 minutes total, <=10 seconds per operation, explicit
  early stop if primary audio work needs resources. Do not weaken bounds to pass.

## What must be tested

Use production FtpsTransfer with a real JVM Socket/SSLSocket Connector that follows
its interface contracts. Keep any test-only certificate handling outside Android
production sources. The test Connector is an adapter; it cannot validate the
Android Network/JobScheduler/Keystore implementation.

1. Real AUTH TLS upgrade; credentials appear only after a successful hostname-
   checked control handshake; PROT P required and independent TLS on every data
   socket. Matching fixture certificate passes. Untrusted, wrong-host and expired
   control or data certificates fail before secret/audio transmission on that
   channel. Never bypass a TLS failure to proceed.
2. EPSV stays on the exact control peer. Malformed ports/replies, PASV/active-mode
   fallback attempts, PROT C, delayed/oversized/multiline replies and stalled TLS
   remain bounded and fail safely. Verify exact command order with redacted logs.
3. Complete upload and full WAV/metadata/marker readback succeed. Corrupt equal-
   size bytes, short/extra readback, false SIZE/hash claims, and unreadable files
   do not succeed. Do not add SIZE/hash shortcuts to make fixtures pass.
4. Disconnect at every upload/publication phase, lose the final success reply,
   abort data close, cancel during TLS/read/write and expire the deadline. Sources
   remain byte-identical. Reopening with the recorded same attempt is read-only:
   no MKD/STOR/REST/DELE. Complete old objects can verify; incomplete ones block.
5. Destination collision, source mutation during stream, metadata/marker
   substitution and a second actor changing the remote file show why the result
   is verified-at-time only. No deletion receipt or cleanup command is issued.
6. Independent reference hashes and byte counts validate the positive fixture
   result; source file manifest before/after and server-side command history
   validate retention and write-free recovery. Preserve failed tests honestly.

Use subprocess termination/restart only for synthetic test processes if that is
included in the eventual approval. A filesystem reopen/kill test does not prove
power-loss durability. Runtime fixtures must not clean remote user directories,
modify spool recordings or grant permanent permissions.

## Report and stopping point

Report exact tested commit, tooling, source/certificate fixture identities, cases,
wire-byte/resource totals and before/after source hashes. Store only small evidence
and dispose of the authorized ephemeral test fixtures by the agreed test cleanup
policy. No private fixture key in a recovery bundle or deliverable.

Stop after the bounded loopback results. A separate device plan is still needed
for Android trusted TLS/Network adapters, charging and capability revocation,
API-37 permission denial/revocation, JobService start/stop races, Activity
recreation/repeated dialogs, SQLite failure/reboot/force-stop, session-only versus
Remember opt-in/locked or invalidated keys, recording+transfer concurrency and
visible cache-full stop. It requires an authorized device/install/test profile;
local JVM mocks cannot establish those outcomes or justify a release.
