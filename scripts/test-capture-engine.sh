#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Actual integrated CaptureEngine, scripted Android APIs, real core file storage.
# No SDK/network/device needed; no copied production source is used.
if [ ! -f ASSEMBLED_SOURCE.json ]; then
    echo 'Run this fixture from an assembled source directory.' >&2
    exit 1
fi
mkdir -p app/build/capture-engine-tests
out=$(mktemp -d app/build/capture-engine-tests/run-XXXXXX)
find app/src/main/java/io/github/headmaster218/recorder/core -name '*.java' | LC_ALL=C sort > "$out/sources.txt"
for name in CaptureEngine InputDevices RecordingSettings SpoolAdmission; do
    printf '%s\n' "app/src/main/java/io/github/headmaster218/recorder/android/$name.java" >> "$out/sources.txt"
done
find app/src/testFixtures/capture-engine -name '*.java' | LC_ALL=C sort >> "$out/sources.txt"
java -Xmx64m -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options,-auxiliaryclass -Werror -d "$out/classes" @"$out/sources.txt"
java -Xmx64m -XX:MaxMetaspaceSize=48m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -cp "$out/classes" io.github.headmaster218.recorder.android.CaptureEngineHostTest "$out/evidence"
