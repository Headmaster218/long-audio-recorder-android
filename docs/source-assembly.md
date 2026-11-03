# Reproducible integrated source assembly

The development branch preserves an older root baseline and seven ordered source
checkpoints. Building only the root does not include all later Android fixes.
Use the offline assembly helper from the Git checkout to create a fresh directory:

```sh
python3 scripts/assemble-source.py --ref HEAD --output ../recorder-integrated-new
cd ../recorder-integrated-new
sh scripts/test-recovery-session.sh
sh scripts/test-ftps-delivery.sh
```

Use a full published commit SHA instead of HEAD when pinning a reproducible
checkpoint. The script resolves that ref once and reads Git objects from that
exact commit, not the working copy. **Uncommitted changes are excluded**, even
when the checkout is dirty. Commit intended source changes before assembling.
No network access, git checkout/reset, deletion, key, installation or upload is
performed. An existing output directory, file or symlink is rejected; choose a
fresh name with an existing parent directory. Failure may leave partial output
for inspection, without a completed `ASSEMBLED_SOURCE.json` receipt. Never use a
partial output as a verified assembly.

Only tracked regular files are copied. Checkpoint folders themselves are excluded;
their `app`, `scripts` and `docs` content is applied in this exact order:

1. upload-threshold-destroy-guard-20261025
2. input-routing-20261025
3. transfer-budget-recovery-20261030
4. recorder-reliability-recovery-20261031
5. repeated-start-lifecycle-audit-20261101
6. sdk-wire-readiness-20261101
7. terminal-boundary-stop-20261103

The current committed `source-overlays.json` selects all seven. For historical
commits without this manifest, the helper retains the original six-overlay order.

The helper refuses missing overlays, symlinks/submodules and unsafe paths rather
than silently making an incomplete tree. Each source blob's Git SHA-1 is checked;
every written file is read back and compared. The final receipt records resolved
source commit, overlay order, source path, destination path, Git blob ID, SHA-256,
byte length and file mode. It is provenance for these exact source bytes, not a
signature or Android acceptance result. Ignored/untracked files, generated builds
and local credentials are not copied. The script does not scan tracked files for
secrets; repository hygiene is still necessary before publication.

An assembly intentionally has no `.git` history. Run additional assemblies from
the original checkout. Historical source-contract scripts that depend on `git
show` or old dev7 source/version text are not valid aggregate gates for this
assembled source. Existing supported host test scripts operate normally, and
`scripts/build-android-unsigned.py` accepts an already installed official SDK.
The helper does not download SDK components or sign/install an APK.

## Validation

`python3 scripts/test-source-assembly.py` runs six offline synthetic Git tests:
exact committed bytes despite dirty/untracked inputs, overlay precedence and
receipt hashes, non-overwrite, tracked symlink rejection, missing overlay
rejection, and unsafe path rejection (the first test covers several behaviors), plus historical
commits without the new overlay manifest.
Generated fixtures remain local. Device lifecycle, actual recording, OEM survival,
server durability and long-duration recording are not exercised by assembly.

Fresh 2026-11-03 validation assembled 223 source files and independently compared
every file against the manually integrated working tree and its receipt hash.
All 26 non-SDK shell suites then passed from that fresh assembly, along with six
host SQL suites (321 assertions), 14 readiness checks and the five assembly tests.
The delivery suite checks all nine generated deliveries with 184 assertions.
Independent review found no blocking assembly defects. The current cloud snapshot
has no restored Android SDK, so no fresh Android source compilation, D8/APK build,
signing or installation is claimed. Passing host Java compilation/tests is not a
substitute for those build stages or physical-device acceptance.
