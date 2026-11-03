# Production FTPS over real loopback TLS with the 11-second producer fixture

This explicit host-source mode reuses the existing pinned pyftpdlib TLS server,
Java socket fixture, production `FtpsNetwork` and production `FtpsTransfer`.
Only Android API surfaces are modeled. There is no replacement FTP client,
custom trust manager or disabled certificate/hostname validation. It does not
qualify Android SDK compilation, Android routing/trust provider, service lifecycle,
a public server or a physical device.

## Inputs and bounds

First generate the exact [11-second synthetic silence spool](synthetic-silence-integration-fixture.md).
The client admits only its known capture ID, frame count, WAV byte count and
SHA-256 plus exact synthetic manifest SHA-256, then runs production catalog validation before connecting. Sources are
read only and preserved. New mode permits at most 512 KiB per source; the original
SDK-based nine-scenario mode retains its 128 KiB source cap. The source-spool and
host-source switches must be supplied together and cannot be combined with
SDK/classpath overrides.

The server binds only literal 127.0.0.1 on an ephemeral port. Existing guards
remain: 2 MiB application traffic per case, 8 MiB total application traffic,
8 MiB generated output, 384 MiB sampled process-tree RSS, 30-second case and
10-minute suite deadlines. Client sockets reject DNS and non-loopback endpoints.
The only credentials are fixed synthetic fixture values. A fresh one-day CA/leaf
certificate and scoped temporary JDK truststore are created; OS/JDK global trust
is untouched, and ephemeral private key/truststore files are removed in `finally`.

## Command

With the existing exact hash-pinned fixture dependencies prepared locally:

```sh
python3 scripts/prepare-ftps-wire-deps.py --download
taskset -c 3 nice -n 19 python3 scripts/test-ftps-wire.py \
  --host-source --source-spool /ABSOLUTE/SYNTHETIC/FIXTURE/spool \
  --execute-reviewed-fixture
```

The helper compiles the actual current core and `FtpsNetwork.java` with the
existing explicit Android host API fixtures, including a minimal PackageManager
permission constant. It runs success, untrusted control certificate, wrong-host
control certificate, and wrong data certificate cases. Original SDK mode and
its nine fault cases remain separately available; this run must not be described
as rerunning those omitted cases.

Success requires full remote readback/hash validation and unchanged local source.
Control trust/hostname failures must occur before USER/PASS/STOR; wrong data
certificate must transmit zero application data. Reports check raw socket and
callback closure, server listener closure, absence of remote delete commands,
per-file server sizes/hashes and source preservation. Compiled source hashes are
recorded because the old runner's `base_commit` is historical fixture-origin
metadata, not the current tested source.

The successful server-received delivery is retained locally for the next backend
snapshot/preflight/integration test. Retention is not immutable-server acceptance.
Do not upload WAVs, sidecars, reports with local paths, credentials, keys or other
runtime artifacts to Git; only fixture source and documentation are backed up.

## Fresh 2026-11-03 result

All four cases passed in 10.690 seconds against the actual production transfer and
network source compiled with host API fixtures. Success transferred and fully
verified the 352,044-byte WAV plus v2 envelope and marker; server application
traffic totaled 1,059,900 bytes. Untrusted control, wrong-host control and wrong
data-certificate cases failed as expected with zero application data. The control
negative cases sent no USER/PASS/STOR. Sampled process-tree peak was 152,539,136
bytes. All owned sockets, callbacks and server listeners closed; scoped ephemeral
key/truststore files were removed. Local originals and successful server-received
delivery remained for the separate backend test. The 14 deterministic readiness
checks also passed. Original nine-case SDK mode was not rerun.

An initial shell invocation used an unsupported compact taskset argument and
failed before any fixture execution. Correcting it to `taskset -c 3` enabled the
single real suite above; this was not a protocol failure. This evidence remains
finite JVM loopback interoperability, not Android or remote production acceptance.
