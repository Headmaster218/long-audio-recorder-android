#!/usr/bin/env python3
"""Historical source-trace reproductions, NOT Android runtime or device tests."""
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET

BASELINE = "345bbf675388c210237f3cd91e9763d846dd23dc"
ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKAGE = "app/src/main/java/io/github/headmaster218/recorder/android/"


def source(path):
    return subprocess.check_output(["git", "show", BASELINE + ":" + path], cwd=ROOT, text=True)


def body(text, declaration):
    start = text.index("{", text.index(declaration))
    depth = 0
    for index in range(start, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:index]
    raise AssertionError("unterminated method")


state = source(PACKAGE + "RecorderState.java")
service = source(PACKAGE + "RecordingService.java")
engine = source(PACKAGE + "CaptureEngine.java")

# Derive constant preference writes from the actual frozen starting() body.
starting = body(state, "static void starting(")
preferences = {"active": False, "paused": True, "capture": "same-capture", "epoch": 1}
for key, value in re.findall(r'putBoolean\("([^"]+)",\s*(true|false)\)', starting):
    preferences[key] = value == "true"
start_requested = body(service, "private void startRequested(")
assert 'p.getBoolean("paused",false)' in start_requested
assert 'p.getBoolean("active"' not in start_requested
assert preferences["active"] and preferences["paused"]
print("REPRODUCED: starting a resumed run retains paused=true; a new process accepts the stale capture/epoch.")

# Trace the empty-queue storage-failure branch, including its already-dequeued block.
assert 'Block block = ready.poll(' in engine
assert 'writer.append(block.bytes,0,block.count*2);' in engine
assert 'finally { block.snapshot = null; free.offer(block); }' in engine
assert '(ready.isEmpty() ? "" : "; queued RAM audio may be uncommitted.")' in engine
queued_remaining, in_flight_samples = 0, 2048
warning = "" if queued_remaining == 0 else "; queued RAM audio may be uncommitted."
assert in_flight_samples > 0 and warning == ""
print("REPRODUCED: a failed dequeued block can lose its RAM-loss warning when no other block remains queued.")

# Independent structural manifest checks on the same frozen source.
manifest = ET.fromstring(source("app/src/main/AndroidManifest.xml"))
android = "{http://schemas.android.com/apk/res/android}"
permissions = {p.attrib[android + "name"] for p in manifest.findall("uses-permission")}
assert permissions == {
    "android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MICROPHONE"
}
application = manifest.find("application")
assert application is not None
assert application.attrib[android + "allowBackup"] == "false"
assert application.attrib[android + "usesCleartextTraffic"] == "false"
assert not application.findall("receiver") and not application.findall("provider")
services = application.findall("service")
assert len(services) == 1 and services[0].attrib[android + "exported"] == "false"
assert services[0].attrib[android + "foregroundServiceType"] == "microphone"
print("PASS historical manifest structure: private microphone service, four explicit permissions, no receiver/provider/network/storage permission.")
print("Source evidence only. This script does not execute Activity, Service, AudioRecord, permissions or notifications.")
