# Recording-to-upload usability checks

## Remembered-password recovery

The Android Keystore key remains configured with `setUnlockedDeviceRequired(true)`.
A transfer encountering a temporarily locked key enters NEEDS_CREDENTIALS (or
WAIT_CREDENTIALS for a historical cleanup readback). Previously those states could
only be restored by entering and saving the password again: unlocking the device
and tapping Upload queued now did not retry an already remembered credential.

Upload queued now now probes existing encrypted credentials for the exact blocked
revisions, once per distinct profile, bounded by the existing 32-profile cap. A
successful decrypt permits the existing queue recovery transition; failures remain
blocked. The probe does not create a key, change key protection, retain plaintext,
or cache a new session password. Probe characters are wiped before the journal
transition. Journal failures are not swallowed as credential failures.

Existing attempts remain readback-only, retry deadlines/counts remain unchanged,
and the budget and enrollment/cleanup consent are not modified. The action still
resumes a paused queue and explicitly overrides its byte trigger, as before. It
never bypasses power, Wi-Fi, local-network permission, TLS or cleanup safety gates.
If credentials are missing, invalid or permanently unavailable, the user must
still enter and save the password for that destination. There is no new automatic
unlock monitor or repeated background decryption.

The actual compiled controller plus real host SQLite reproduced the old failure
and passed 48 regression assertions after the fix. The Android Keystore boundary
is a scripted lock/decrypt fixture, not a real device Keystore test. Cases include
multiple blocked rows sharing one profile, unrelated profiles, session-only and
invalid credentials, preserved old attempt/backoff/budget/consent, cleanup waits,
plaintext wiping and a deliberately rejected SQLite update.

## Connected capture-to-automatic-upload path

A separate finite host integration executes unchanged SDK-compiled CaptureEngine,
its real published/captureClosed callbacks, the actual coordinator and persistent
queue, and real loopback FTPS upload/full readback. Scripted AudioRecord supplies
32 PCM16 mono samples; explicit Stop produces a valid 108-byte finalized WAV and
STOP manifest. The automatic-enrollment callback, not direct queue insertion,
admits it. An explicitly configured 108-byte threshold makes it eligible. The
fixture never invokes Upload now. The actual runOne transfer produces three
remote objects and a verified receipt, independently checked against server hashes
and after SQLite reopen. It preserves the local original and closes resources.

This validates the connection between capture and transfer components, rather than
merely testing them separately. It does not exercise actual microphone hardware,
Activity/service startup or Android scheduling. AudioRecord, permissions, power,
network selection and Handler boundaries are modeled; transport itself uses real
loopback sockets and TLS. No user server or real credential is involved.

## Deliberately unchanged behavior and limits

The default threshold remains 9,600,000 WAV bytes and is configurable. Stop does
not flush a below-threshold tail automatically. For example, a short recording
may remain safely queued until more authorized data arrives or the user taps
Upload queued now. Existing UI explains this; no unrequested policy change was
introduced during this audit.

Official SDK compilation and unsigned APK packaging pass. All-day recording,
locked-screen behavior, Android lifecycle/scheduling, OEM battery management,
real Keystore and the user's eventual local server still require device testing.
Cloud host results must not be presented as those qualifications.

Reproduction entrypoints:
- `scripts/test-credential-retry.sh CLASSES ANDROID_JAR`
- `scripts/test-capture-auto-wire.py --help` (prepared pinned FTPS dependencies;
  no downloads; verified assembled source required)
