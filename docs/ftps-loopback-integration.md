# FTPS loopback integration fixture

This test-only checkpoint keeps application version `0.1.0-dev13`. It executes the unchanged production `FtpsTransfer` and Android `FtpsNetwork` bytecode with small host Android API ports. TCP sockets and JVM TLS are real; Android connectivity, battery, clock and callback services are modeled.

## Reproduce on a bounded Linux worker

Use Java 21, the official Android API 37 SDK and compiled production classes from `scripts/build-android-unsigned.py`. Inspect the fixture before opting into execution. The runner requires CPU 3 and nice 19, an existing 5 GiB free-space floor, a 384 MiB process-tree ceiling, and installed Python runtime versions described by the dependency manifest.

```
python3 scripts/prepare-ftps-wire-deps.py --download
taskset -c 3 nice -n 19 python3 scripts/test-ftps-wire.py \
  --production /path/to/direct-build/classes \
  --android /path/to/android-37.0/android.jar \
  --execute-reviewed-fixture
```

Dependency preparation downloads four checksum-pinned official PyPI archives, totaling 263,282 bytes. It installs only into this candidate's `app/build/ftps-wire-dependencies`, without system upgrades or transitive downloads. Existing cryptography and build tooling are reused. Installed size must remain below 16 MiB. No downloaded code or private test keys belong in the public source checkpoint.

The fixture uses [pyftpdlib 2.2.0](https://pypi.org/project/pyftpdlib/2.2.0/) (MIT), [pyOpenSSL 26.4.0](https://pypi.org/project/pyOpenSSL/26.4.0/) (Apache-2.0), and Python 3.12 compatibility packages [pyasyncore 1.0.5](https://pypi.org/project/pyasyncore/1.0.5/) and [pyasynchat 1.0.5](https://pypi.org/project/pyasynchat/1.0.5/) (PSF-2.0). Exact URLs, sizes, hashes and reused runtime versions are in `app/src/testFixtures/ftps-wire/dependencies.json`.

## Nine scenarios

1. Protected upload and full readback, with independent remote file hashes.
2. Untrusted control certificate, before credentials are sent.
3. Trusted certificate with wrong IP SAN, before credentials are sent.
4. Trusted control channel but independently wrong data-channel certificate.
5. Equal-length corrupted remote audio.
6. Truncated remote audio.
7. Partial upload disconnect followed by unsuccessful read-only reconciliation of the same attempt.
8. Lost final completion reply followed by successful read-only reconciliation of the same complete attempt.
9. Stalled TLS handshake interrupted by actual network cancellation.

Every case checks unchanged synthetic local WAV bytes, socket closure, callback release and listener shutdown. Reconciliation must issue no write, rename or delete commands. Failed authentication sends no credentials; failed data TLS accepts no application data. All listeners and client destinations are literal `127.0.0.1`; DNS is rejected. At most two listeners may coexist. Each fresh JVM receives its own fixture-only truststore before SSL initialization, with no permissive trust manager or hostname verifier. Generated private keys are scoped to one run and removed in a final cleanup path.

Per-case and suite deadlines are 30 seconds and 10 minutes. Test source WAVs are 16,428 bytes; per-case application wire traffic is capped at 2 MiB and aggregate traffic at 8 MiB. Reports contain only synthetic identities, certificate fingerprints, sizes, hashes and bounded command transcripts; passwords are redacted.

## Observed result

On 2026-10-16 all nine scenarios passed in 18.686 seconds, with peak process-tree RSS 140.22 MiB. The stalled handshake returned 77 ms after cancellation. All synthetic originals retained their exact hashes, all owned raw sockets and listeners closed, and the run removed its ephemeral keys. The sanitized evidence is `docs/ftps-loopback-results.json`. No production code changes were needed.

## Acceptance boundary

These tests can establish production protocol and standard-JVM TLS behavior against a real server. They do not establish Android trust-provider behavior, device routing, Wi-Fi/mobile transitions, foreground-service survival, microphone routes, automatic cleanup coordinator behavior, power-loss safety, SMB support or unattended 24-hour device acceptance. No real recording or external server is involved. The fixture does not invoke local cleanup; failed verification yields no successful transfer result and preserves the synthetic original.
