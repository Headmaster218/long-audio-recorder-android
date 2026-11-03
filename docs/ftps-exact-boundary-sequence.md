# Exact-boundary sequence over real FTPS

The optional `--exact-boundary-sequence` mode in `scripts/test-ftps-wire.py`
transfers the three exact-STOP synthetic fixture entries, in ordinal order,
through production `FtpsNetwork` and `FtpsTransfer` sockets/TLS. It requires
`--host-source`, `--source-spool`, and `--execute-reviewed-fixture`.

Generate the source with `scripts/test-ftps-exact-boundary.sh` first. The client
admits only the three pinned metadata hashes and their pinned 128,044-byte WAV
hash, verifies all entries before opening its network connection, then selects
one ordinal. This is not a general uploader or a production receiver.

Example (from a freshly assembled source tree with the existing checksum-pinned
wire dependencies prepared):

```sh
taskset -c 3 nice -n 19 python3 scripts/test-ftps-wire.py \
  --host-source --source-spool /absolute/path/to/generated/spool \
  --exact-boundary-sequence --execute-reviewed-fixture
```

Each ordinal has its own private loopback server session and received directory.
There is no single long-lived receiver lifecycle in this test. The client uses
normal JDK certificate and hostname checks, with an ephemeral fixture CA trusted
only by that client process. The run retains the actual received WAV, envelope,
and marker files for downstream verification. Ephemeral private keys and the
client truststore are removed. Source files are retained unchanged; there are no
remote delete commands. Host Android API fixtures are used, not an Android
device, routing implementation, or platform trust provider.

The sequence consists of 64,000 frames per entry, one capture/epoch, ordinals
0–2, and ROTATION/ROTATION/STOP. Total PCM is 192,000 frames (12 seconds of digital
silence). This tests declared continuity and explicit terminal metadata, not
microphone or physical continuity. The existing legacy single-source modes and
negative certificate scenarios remain available.

## Recorded cloud validation, 2026-11-03

- Three production real-wire success transfers passed in 10.864 seconds.
- Sampled process-tree peak: 148,328,448 bytes.
- Application wire bytes: 1,163,886; sequence aggregate ceiling: 2 MiB.
- Every received WAV/envelope/marker matched the verified transfer receipt.
- All sockets/listeners closed, callbacks balanced, and ephemeral keys removed.
- Independent saved-artifact audit verified source-to-received bytes and all
  compiled-source/runner/server hashes.
- Legacy 11-second mode also passed all four real-wire scenarios: success,
  untrusted control certificate, wrong control hostname, and wrong data certificate.
- Local report SHA-256:
  `5b51770d7c7d8165d62e5b1c9e5ef515bfb3560f1450fbf6de77efaf8f0fa943`.

The report records each ordinal and received remote directory, hashes of compiled
source, runner and server, and an explicit compilation mode. The old hard-coded
origin commit is labeled historical fixture origin, not production provenance.
The `host_api_fixtures` flag is now always true because both SDK-class and
host-source execution use these host runtime fixtures.

Generated audio, certificates, runtime logs, and reports remain local and must
not be included in the source-only backup. Device/OEM/all-day operation and a
production network receiver remain untested.
