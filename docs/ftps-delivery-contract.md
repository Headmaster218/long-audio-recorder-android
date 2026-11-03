# Producer-owned FTPS delivery contract

This source candidate extracts the existing wire encoder into `FtpsDelivery` and
uses that same encoder in `FtpsTransfer`. It adds no endpoint, upload policy,
recording permission, cleanup eligibility or new recording mode. Bytes remain
compatible with the earlier inline encoder for manifest versions 1 and 2.

## Files and framing

An owned attempt directory is named `r-<binding>-<nonce>`. Binding is lowercase
SHA-256 of UTF-8 `sourceId + "\n" + wavSha256 + "\n" + profileRevision`; nonce is
32 lowercase hexadecimal characters. This digest is a content binding, not an
authentication token. The source identity is an ordinal `.ready` name, not a path
that a consumer should follow.

- `audio.wav`: canonical 44-byte RIFF/WAVE PCM16 header plus interleaved PCM bytes.
- `segment.v1.bin`: big-endian int `0x52465431`, Java `DataOutputStream.writeUTF`
  destination revision, `writeUTF` source identity, big-endian int local metadata
  length, then exactly that many `SegmentMetadata.encode()` bytes. Envelope bound
  is 8,704 bytes. Java modified UTF-8 is not ordinary UTF-8; inner metadata may
  contain Unicode, including surrogate pairs. The envelope has no trailing bytes.
- `delivery.v1.txt`: ASCII, exactly eight LF-terminated lines, including a final
  newline: `RECORDER-DELIVERY-V1`, attempt name, destination revision, source ID,
  decimal WAV byte length, lowercase WAV SHA-256, decimal envelope byte length,
  lowercase envelope SHA-256. Encoder bound is 1,024 bytes.

The embedded local metadata is bounded to 8,192 bytes, starts with big-endian
`PCM1` magic and version 1 or 2, and ends with SHA-256 of all preceding local
metadata bytes. See `SegmentMetadata` and `CaptureAnchors` for its authoritative
field encoding and validation. Version 2 observations explicitly leave hardware
frame mapping UNESTABLISHED. A hash match alone does not validate those semantics
or prove continuous recording, clock alignment, or backend import eligibility.

## Publication and trust boundary

The producer uploads WAV, verifies its readback, uploads metadata and verifies it,
then uploads the marker and verifies it. It re-reads all three files afterward.
Marker STOR is **not atomic server publication**: another reader may see partial
bytes, and server content may later change. These observations do not constitute
an immutable handoff or permission to delete local originals. Backend preflight
must fail closed on missing/partial/inconsistent files, read a bounded stable
local snapshot, and explicitly distinguish transport integrity from semantic
validation and strict import readiness. A revision/source ID in untrusted files
cannot authorize data ingestion or choose a destination/account.

## Reproduce the producer fixtures

After applying the six existing source overlays in their documented order, run:

```sh
sh scripts/test-ftps-delivery.sh
# Optional: supply a fresh, nonexistent directory for cross-language tests.
sh scripts/test-ftps-delivery.sh /tmp/recorder-delivery-fixtures-new
sh scripts/test-ftps.sh
sh scripts/test-capture-anchors.sh
```

The fixture command compiles the production core with Java 8 source/classfile
targets and runs on host JVM 21. It creates nine tiny deterministic synthetic
PCM fixtures through the production spool/writer and the exact production wire
encoder: v1 16kHz mono, v2 16kHz mono, and v2 48kHz stereo with a Chinese/emoji
capture ID. The first three are three-frame STOP tails; additional cases cover NOT_POLLED,
UNAVAILABLE and QUERY_EXCEPTION timestamp status, plus one read split across
4+4+2-frame segments with ROTATION/STOP and trusted-previous-seam flags. AVAILABLE
observations include a frame position above 32 bits. No microphone, socket, account, SDK download,
key, or raw user recording is used. Generated audio stays in ignored build output
or the explicit local output; only generator source is committed. Existing output
directories are rejected, not overwritten.

The codec suite checks exact legacy-byte equivalence, digest consistency,
defensive copies and rejection of changed destination/attempt bindings. The
capture-anchor suite now tests the public producer encoder rather than reflecting
into a removed private method. Scripted FTPS tests still exercise actual transfer
ordering, readback, cancellation and failure handling.

## 2026-11-03 evidence and limits

Fresh focused checks passed: delivery codec 184 assertions, scripted FTPS 868,
capture anchors 91, policy core 7,068, recovery-session 1,389 and admission 30.
All 26 non-SDK shell suites passed on the assembled source, plus six host SQL
suites (321 combined assertions) and 14 fixture-readiness assertions. Independent
review found no blocking codec/refactor issues. These are host/model checks, not
Android runtime execution. The backend's independent Python transport preflight initially accepted all three
original Java-produced fixtures. Its subsequent opt-in semantic stage accepted all
nine expanded deliveries, including Unicode, timestamp states and rotation seams
(backend checkpoint `1ada8107db83d890eb053e824569a9895526a51f`). Default transport-only
mode remains distinct from metadata semantic validation. Neither establishes
immutable remote publication, sequence-wide continuity or strict import readiness.

The historical `scripts/check-ftps-wiring.py` is tied to dev7 source ordering and
version checks; on the unmodified six-overlay baseline it already fails because
an earlier reconciliation call precedes the queue-begin call. It is not counted
as a passing current aggregate. This extraction does not repair or weaken that
historical gate. No fresh Android SDK compilation, APK, device lifecycle, real
FTPS server, physical microphone, OEM or all-day qualification is claimed here.
