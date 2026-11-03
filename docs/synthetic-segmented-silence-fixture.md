# Finite three-segment producer fixture

Run `sh scripts/test-ftps-segmented-silence.sh` from integrated source, optionally
passing one fresh nonexistent output directory. This uses the actual production
`PcmSegmentWriter`, `DirectorySpool`, catalog verification and `FtpsDelivery`
encoder. Existing single-segment 11-second fixtures are unchanged.

Eleven one-second zero blocks at 16 kHz mono PCM16 are written with a four-second
segment target. The resulting three deliveries contain 64,000 + 64,000 + 48,000
frames (4 + 4 + 3 seconds), with sequence 0, 1, 2 and frameStart 0, 64,000, 128,000.
All share one synthetic capture/run/epoch and START boundary. Close reasons are
ROTATION, ROTATION, STOP; trustedPreviousSeam is false, true, true. Manifest v2
has synthetic NOT_POLLED read anchors with consecutive read ordinals and sample
extents. These are producer-declared sample relationships, not physical microphone
continuity, clock mapping, real elapsed time or device acceptance evidence.

The output contains one preserved spool and three separate delivery directories.
The generator prints sequence-to-directory mappings; do not infer sequence from
hashed directory sort order. Each delivery uses one fixed synthetic destination
revision, and its own correctly source/hash-bound attempt. Fixture identity is
separate from prior single-segment and short matrix fixtures. Use isolated backend
state and keep scenarios separate rather than assuming unrelated captures join.

The 126 assertions check exact entry count/no rejections, all sequence/frame/format
and boundary fields, per-segment anchor count/extents/ordinal adjacency, canonical
WAV headers and zero PCM, production catalog verification, PCM/WAV and transport
hashes, local metadata round-trip, saved bytes and original preservation. Combined
PCM is exactly 352,000 zero bytes / 176,000 frames with SHA-256
`31f87d48f9595970b868951d4da55e4a77eb56948cb2f9cbd5537677c76ef54d`.

No microphone, socket, credential, user audio or new transport implementation is
used. Generated data remain local; only generator source/documentation are
backed up. This fixture alone does not prove backend ingestion, FTPS delivery,
recognition quality, Android lifecycle or long-duration behavior.
