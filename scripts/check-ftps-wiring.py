#!/usr/bin/env python3
"""Source contracts only. Does not execute Android, SQLite, credential APIs, TLS or sockets."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
p=root/'app/src/main/java/io/github/headmaster218/recorder'
core=(p/'core/FtpsTransfer.java').read_text()
queue=(p/'android/FtpsQueue.java').read_text()
coordinator=(p/'android/FtpsCoordinator.java').read_text()
network=(p/'android/FtpsNetwork.java').read_text()
credentials=(p/'android/FtpsCredentials.java').read_text()
ui=(p/'android/FtpsControls.java').read_text()
job=(p/'android/FtpsJobService.java').read_text()
schedule=(p/'android/FtpsJobSchedule.java').read_text()
assert core.index('s.command("AUTH TLS")') < core.index('control.secure(profile.host)') < core.index('s.command("USER "')
assert 's.command("PROT P")' in core and 's.command("TYPE I")' in core
assert 'command("EPSV")' in core and 'data.secure(host)' in core
assert 'if (!reconcileOnly)' in core and 's.readback("audio.wav"' in core
assert not any('command("'+v in core for v in ['DELE','RMD','REST','PASV','PORT','EPRT','CCC','PROT C'])
assert not any(v in core+coordinator+queue for v in ['Files.delete','deleteIfExists','DeletionGate.Receipt','LOCAL_DELETE_ELIGIBLE','localDeletionAcknowledged'])
assert 'PRAGMA synchronous=FULL' in queue and 'getNoBackupFilesDir()' in queue
assert 'UNIQUE(source,revision)' in queue and "WHERE state='RUNNING'" in queue
assert 'db.beginTransaction()' in queue and 'db.setTransactionSuccessful()' in queue
assert coordinator.index('queue.begin(item,attempt)') < coordinator.index('new FtpsNetwork(context,selectedNetwork)') < coordinator.index('FtpsTransfer.run(')
assert 'attempt,item.attempt != null,guard' in coordinator
assert 'MAX_RECONCILIATIONS' in coordinator and 'MAX_RECONCILIATIONS = 3' in queue
assert 'Executors.newSingleThreadExecutor' in coordinator and 'Arrays.fill(secret' in coordinator
assert 'catalog.load(item.source)' in coordinator and 'entry.metadata.wavSha256.equals(item.wavHash)' in coordinator
assert 'displayed != page' in coordinator and '!current.revision.equals(shownRevision)' in coordinator
assert 'setRequiresCharging(true)' in schedule and 'setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)' in schedule
assert 'FtpsJobSchedule.build(' in coordinator and 'setRequiredNetworkType' not in coordinator
assert coordinator.index('FtpsNetwork.allowed(context') < coordinator.index('secret = password(profile)') < coordinator.index('queue.begin(item,attempt)')
assert 'queue.deferForSafety(now + 300000)' in coordinator and 'next_at=MAX(next_at,?)' in queue
assert 'job.cancel()' in job and 'queue.verified(item,result)' in coordinator
assert 'TLSv1.2' in network and 'TLSv1.3' in network and 'setEndpointIdentificationAlgorithm("HTTPS")' in network
assert 'SSLSocketFactory.getDefault()' in network and 'connectPeer(host,peer,port)' in network
assert 'network.getSocketFactory().createSocket()' in network and 'DnsResolver.getInstance().query(network,host' in network
assert 'getAllNetworks()' in network and 'selectWifi' in network and 'NET_CAPABILITY_NOT_METERED' in network
assert 'getTransportTypes' not in network and 'eligibleWifi(caps)' in network
assert 'e.addSuppressed(closing)' in network and 'SSLHandshakeException' in coordinator
assert 'serial != scheduleSerial || activeJob != null' in coordinator and 'reschedulePending.compareAndSet' in coordinator
assert 'coordinator.reschedule()' in ui
assert 'TRANSPORT_VPN' in network and 'channel.close()' in network and 'raw.close()' in network
assert 'ATTEMPT_MILLIS = 180000' in network and 'sockets.size() >= 2' in network
assert 'if (!explicitConsent)' in credentials and 'AndroidKeyStore' in credentials and 'AES/GCM/NoPadding' in credentials
assert 'setUnlockedDeviceRequired(true)' in credentials and 'cipher.updateAAD(revision' in credentials
assert 'remember.setChecked(false)' in ui and 'password.setSaveEnabled(false)' in ui
assert 'selectedPage != s.page' in ui and 'Queue this recording' in ui and 'LAN_REQUEST = 6201' in ui
assert 'requestPermissions(new String[]{FtpsNetwork.LAN_PERMISSION}' in ui
# Service, stop-on-full policy and deletion gate remain byte-identical to the dev6 baseline.
# CaptureEngine changes are covered separately by check-capture-anchor-wiring.py.
for rel in ['android/RecordingService.java','core/CachePolicy.java','core/DeletionGate.java']:
    tracked='app/src/main/java/io/github/headmaster218/recorder/'+rel
    baseline=subprocess.check_output(['git','-C',str(root),'show','60767916fd4cdd735c5cec2cef643aa3e37523a3:'+tracked])
    assert (p/rel).read_bytes()==baseline, rel
subprocess.check_call(['python3',str(root/'scripts/check-capture-anchor-wiring.py')])
assert (root/'VERSION').read_text().strip()=='0.1.0-dev7'
a='{http://schemas.android.com/apk/res/android}'
m=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
assert m.attrib[a+'versionCode']=='7' and m.attrib[a+'versionName']=='0.1.0-dev7'
print('PASS FTPS source wiring: authenticated TLS flow, bound route, durable-before-I/O intent, exact source/profile admission, read-only reconciliation, explicit credential consent and no deletion')
print('No Android/SQLite/credential/TLS/socket behavior was executed; device/runtime acceptance remains pending.')
