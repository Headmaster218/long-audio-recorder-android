# Official-source findings

Checked 2026-10-09. These are platform/protocol facts, not results from the
user's phone. The architecture is a proposed application of these sources.

## Capture lifecycle

- A microphone foreground service requires RECORD_AUDIO and the appropriate
  manifest/service permissions. It supports continued background recording.
  Background creation and BOOT_COMPLETED launches are restricted; normal
  onboarding starts from a visible activity and does not rely on exceptions.
  [Foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- While-in-use permission checks are separate from general background-start
  exemptions. Android 14+ can reject service creation immediately. User
  interaction with a notification is one documented exception; recovery must
  still check eligibility rather than assume arbitrary auto-start works.
  [Start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- The documented six-hour daily FGS limits concern dataSync/mediaProcessing,
  not microphone. This is not an all-day availability guarantee.
  [Timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
- Android 16 applies job runtime quotas even during foreground-service use.
  [FGS changes](https://developer.android.com/develop/background-work/services/fgs/changes)
- A user can stop the whole foreground-service app using Android's active-app
  controls. Do not depend on a final service callback for durable audio.
  [User-initiated stopping](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)
- POST_NOTIFICATIONS is not an Android prerequisite for launching an FGS,
  although the service must supply a notification. Product policy can be
  stricter to make recording consistently visible.
  [Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)

## Routing, interruptions, and formats

- Enumerate input devices, register AudioDeviceCallback, and request a BLE
  recording input through AudioRecord.setPreferredDevice. The user can
  override the preference; headset support is not a hardware guarantee.
  [BLE audio recording](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording)
- Read actual routing while recording. AudioRecord timestamps provide frame
  delivery anchors; frame counts reset after stop/start. ERROR_DEAD_OBJECT
  requires rebuilding the record object. Buffer configuration and route
  support need runtime validation.
  [AudioRecord reference](https://developer.android.com/reference/android/media/AudioRecord)
- setCommunicationDevice is for communication routing; startBluetoothSco is
  deprecated in API 34. Use modern APIs where applicable, but validate Classic
  headset behavior separately from BLE recorder routing.
  [AudioManager reference](https://developer.android.com/reference/android/media/AudioManager)
  [Communication audio guide](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager)
- Competing ordinary recording apps may leave one with silence; calls have
  priority. Recording configuration callbacks report policy silencing, active
  device, processing, and stream changes. Register before capture begins.
  Ordinary app microphone permission does not grant privileged call capture.
  [Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input)
- Android documents PCM/WAVE and FLAC support. A usable codec/container path,
  frame counts, interruption recovery, and energy use still need validation.
  [Supported formats](https://developer.android.com/media/platform/supported-formats)

## Power, storage, and networks

- Current Android guidance recommends relying on media APIs' wake locks
  rather than acquiring an additional one by default. Device traces should
  establish any exception; an FGS is not immunity from every interruption.
  [Wake-lock use cases](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/identify-wls)
- AtomicFile is a tool for small atomic metadata replacement; design a
  single-writer journal and explicit recovery around multi-file operations.
  [AndroidX AtomicFile](https://developer.android.com/reference/androidx/core/util/AtomicFile)
- WorkManager has charging and network constraints. If a constraint becomes
  unmet while running, the worker is stopped and work can retry later.
  [Work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- Wi-Fi does not imply unmetered; capabilities/routes can change mid-session,
  VPNs may expose multiple transports, and networks can be local-only. Use
  callbacks and bind permitted connections where a strict policy requires it.
  [Network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)

## Transfers

- SFTP uses encrypted SSH transport. OpenSSH resume assumes the partial data
  matches; a mismatched prefix can corrupt the result. Upload fsync is an
  optional server extension, not universally available.
  [OpenSSH sftp manual](https://man.openbsd.org/sftp)
- SMB3 offers encryption; SMB2.0.2/2.1 do not. Select negotiated security
  deliberately. Transport integrity is distinct from verifying stored content.
  [SMB security considerations](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/14b32996-29ca-4d5a-b888-a159af29e705)
- SMB FLUSH requests persistent-store flushing; server-specific behavior and
  storage reliability remain part of the durability contract.
  [SMB FLUSH request](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/e494678b-b1fc-44a0-b86e-8195acf74ad7)
  [SMB FLUSH handling](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/026984f6-38af-4408-8200-50557eb0a286)
- FTP extensions define SIZE and REST stream restart. File length alone is
  not an integrity digest. Probe capability and use binary transfers.
  [RFC 3659](https://www.rfc-editor.org/info/rfc3659/)
- FTPS must protect the data channel as well as control. RFC 4217 PROT P uses
  TLS for data, while PROT C leaves data clear. Certificate checks remain
  mandatory; plain FTP is unsuitable as the default for personal audio.
  [RFC 4217](https://www.rfc-editor.org/info/rfc4217/)

## Limits of this pass

No Xiaomi-only API was selected or claimed superior. No actual phone OS,
headset, NAS, codec path, remote hashing facility, or durable-delete behavior
was tested. No credentials, remote repositories, or accounts were accessed.
