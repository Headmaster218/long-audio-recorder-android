# Synthetic Android-producer input for backend integration

Run `sh scripts/test-ftps-silence-fixture.sh` from integrated source, or supply a
fresh nonexistent output directory as its sole argument. Existing output is
rejected, not replaced. No microphone, socket, account, secret or user recording
is used. Generated audio remains local and must not be committed.

`FtpsSilenceFixtureTest` passes eleven 32,000-byte zero blocks through the actual
production `DirectorySpool` and `PcmSegmentWriter`, then encodes their committed
entry with the same `FtpsDelivery` used by production FTPS upload. It does not
hand-construct a transport envelope or synthesize a replacement WAV header.

The resulting sole delivery is canonical 16 kHz mono PCM16 digital silence,
176,000 frames / 11 seconds / 352,044 WAV bytes. A 12-second target ensures an
explicit STOP tail, not ROTATION. Sequence, epoch and frameStart are zero;
boundary is START; trustedPreviousSeam is false. Manifest v2 carries eleven
synthetic NOT_POLLED read observations. Their clocks are deliberately artificial,
not device timestamp evidence or hardware-to-PCM mapping.

The fixed fixture identity and attempt are for reproducible isolated tests only.
Do not reuse its identity for other content or combine it with unrelated fixture
scenarios as one capture. The source spool is preserved alongside the delivery.

The 37 assertions check a single eligible catalog entry with no rejected entries,
production catalog verification, identity/epoch/format/frame geometry, START/STOP,
anchor counts and extents, canonical WAV header and all-zero PCM, PCM/WAV hashes,
RFT1 framing and embedded manifest round-trip, envelope/marker digests, saved-file
readback and original preservation. This generates enough audio for backend chunk
publication exercises but does not itself run the backend or qualify recording,
recognition quality, transfer durability, Android lifecycle, OEM or all-day use.

Fresh 2026-11-03 generation passed with WAV SHA-256
`1a092b23d9dfe64d53943d18262c0a49eaf32c5ab5f2b016cd8edd16ec9440dd`.
Only generator source and documentation are backed up, never generated WAV,
metadata, delivery objects, databases or runtime output.
