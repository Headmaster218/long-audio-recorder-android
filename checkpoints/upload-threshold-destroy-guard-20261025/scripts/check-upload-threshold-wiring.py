#!/usr/bin/env python3
"""Narrow source contracts, not Android execution or proof of race freedom."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
android = root / 'app/src/main/java/io/github/headmaster218/recorder/android'
queue = (android / 'FtpsQueue.java').read_text()
coordinator = (android / 'FtpsCoordinator.java').read_text()
network = (android / 'FtpsNetwork.java').read_text()
ui = (android / 'FtpsControls.java').read_text()
save = queue[queue.index('    void uploadThresholdBytes('):queue.index('    void uploadNow()')]
command = coordinator[coordinator.index('    boolean saveUploadThreshold('):coordinator.index('    void uploadNow()')]
checks = [
    'null, 5);' in queue,
    'oldVersion > 4 || newVersion != 5' in queue,
    'if (oldVersion < 4)' in queue,
    'upload_threshold_bytes INTEGER NOT NULL DEFAULT 9600000' in queue,
    'c.getType(0) != Cursor.FIELD_TYPE_INTEGER' in queue,
    'return UploadThreshold.validate(c.getLong(0));' in queue,
    'value.put("upload_threshold_bytes",bytes)' in save,
    '"id=1 AND upload_threshold_bytes=?"' in save,
    all(token not in save for token in ['auto_revision', 'current_revision', 'cleanup_revision', 'credential', 'paused', 'next_at']),
    'return command(new Command()' in command,
    'queue.uploadThresholdBytes(bytes,expected)' in command,
    all(token not in command for token in ['queue.automatic(', 'queue.pause(', 'postCatchup(', 'pauseRequested.set']),
    'UploadThreshold.addPending(bytes,item.state,item.bytes)' in coordinator,
    'UploadThreshold.policy(queue.uploadThresholdBytes()).evaluate(total,' in coordinator,
    'item.uploadNow || item.attempt != null,UploadThreshold.policy(queue.uploadThresholdBytes()))' in coordinator,
    'if (activeJob != null || !commandPending.compareAndSet(false,true)) return false;' in coordinator,
    'item.nextAt <= now && triggered(item,total,now)' in coordinator,
    'FtpsQueue.Profile profile = queue.profile(item.revision);' in coordinator,
    'if (!permissionReady(c))' in network and 'policy.evaluate(bytes,age,false,uploadNow,charging(c),route(cm,n))' in network,
    'TransferPolicy.DEFAULT.evaluate(1,0,false,true,powered,route(connectivity,network))' in network,
    'final long bytes = UploadThreshold.parse(threshold.getText().toString());' in ui,
    '"",32,InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS' in ui,
    '.setNegativeButton("Cancel",null).setPositiveButton("Save threshold"' in ui,
    'coordinator.saveUploadThreshold(bytes,expected)' in ui,
    's.uploadThresholdBytes > 0' in ui,
    'coordinator.automatic(true,revision)' in ui,
    'Transfer threshold: " + s.uploadThresholdBytes + " pending WAV bytes' in ui,
    'snapshot.uploadThresholdBytes = old.uploadThresholdBytes;' in coordinator,
]
assert all(checks), [index for index, passed in enumerate(checks) if not passed]
print('PASS: %d upload threshold source contracts; not Android/runtime execution' % len(checks))
