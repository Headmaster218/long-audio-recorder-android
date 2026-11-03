# Terminal-aware exact-boundary Stop

Apply this checkpoint after the six earlier checkpoints, using the committed
`source-overlays.json` and `scripts/assemble-source.py`. It updates CaptureEngine
to select `PcmSegmentWriter.terminalAware`; the core implementation and tests are
root files in this commit. The previous checkpoint sources remain unchanged.

The last full segment is held staged until more PCM or explicit close. Explicit
Stop now records STOP even on an exact rotation boundary, without rewriting a
published segment. See `docs/terminal-boundary-stop.md` for compatibility, delayed
publication, failure/recovery behavior and finite test evidence. No APK signing,
installation, Android runtime, OEM or long-duration acceptance is implied.
