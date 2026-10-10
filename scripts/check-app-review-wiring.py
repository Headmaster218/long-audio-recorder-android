#!/usr/bin/env python3
"""Narrow source/manifest integration checks. These are not Android runtime tests."""
import pathlib
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[1]
package = root / "app/src/main/java/io/github/headmaster218/recorder/android"
state = (package / "RecorderState.java").read_text()
service = (package / "RecordingService.java").read_text()
engine = (package / "CaptureEngine.java").read_text()
activity = (package / "MainActivity.java").read_text()

assert "private static final CleanPauseGate CLEAN_PAUSE = new CleanPauseGate()" in state
assert "RecorderState.takeCleanPause()" in service
assert 'getBoolean("paused"' not in service and "SharedPreferences" not in service
assert 'putBoolean("paused",false)' in state
assert "if (paused) CLEAN_PAUSE.cleanPause(capture,nextEpoch); else CLEAN_PAUSE.clear();" in state
print("PASS source wiring: service resume authority comes only from the consumed process-local pause gate")

read = engine.index("int count = recorder.read(")
accepted = engine.index("accounting.readAccepted(count)")
attribution = engine.index("Snapshot snapshot = snapshot(recorder)")
enqueue = engine.index("ready.offer(held)")
assert read < engine.index("if (count < 0)") < engine.index("if (count == 0)") < accepted < attribution < enqueue
assert engine.index("writer.append(block.bytes,0,block.count*2,block.observation)") < engine.index("accounting.appendConfirmed(block.count)")
assert engine.index("producer.join(1000)") < engine.index("accounting.snapshot()")
assert "result = finalAccounting.warning() + result;" in engine
assert 'ready.isEmpty() ? ""' not in engine
print("PASS source wiring: reads precede attribution, successful append acknowledgements follow writes, final warning follows producer join")

assert "setDeleteIntent" not in service and "getActiveNotifications" not in service
assert "dismiss" in activity.lower()
assert "START_NOT_STICKY" in service and "START_STICKY" not in service
assert "FLAG_IMMUTABLE" in service
assert "startForegroundService" in activity and "if (!resumed" in activity
print("PASS source policy: explicit visible start, non-sticky service and no notification-dismissal stop/repost mechanism")

android = "{http://schemas.android.com/apk/res/android}"
manifest = ET.parse(root / "app/src/main/AndroidManifest.xml").getroot()
permissions = {item.attrib[android + "name"] for item in manifest.findall("uses-permission")}
assert permissions == {
    "android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_LOCAL_NETWORK", "android.permission.RECEIVE_BOOT_COMPLETED"
}
application = manifest.find("application")
assert application is not None
assert application.attrib[android + "allowBackup"] == "false"
assert application.attrib[android + "usesCleartextTraffic"] == "false"
assert not application.findall("receiver") and not application.findall("provider")
services = application.findall("service")
assert len(services) == 2
by_name = {item.attrib[android+"name"]: item for item in services}
assert set(by_name) == {".RecordingService", ".FtpsJobService"}
assert by_name[".RecordingService"].attrib[android+"exported"] == "false"
assert by_name[".RecordingService"].attrib[android+"foregroundServiceType"] == "microphone"
assert by_name[".FtpsJobService"].attrib[android+"permission"] == "android.permission.BIND_JOB_SERVICE"
assert by_name[".FtpsJobService"].attrib[android+"exported"] == "true"
assert android+"foregroundServiceType" not in by_name[".FtpsJobService"].attrib
activities = application.findall("activity")
assert len(activities) == 1 and activities[0].attrib[android + "name"] == ".MainActivity"
assert activities[0].attrib[android + "exported"] == "true"
print("PASS current manifest: one launcher, private microphone service and system-permission-protected FTPS JobService; no provider/receiver/storage-wide permission")
print("These source checks supplement logic tests; permission/lifecycle/notification behavior still requires a device.")
