#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=app/build/terminal-boundary-tests
mkdir -p "$out"
find app/src/main/java/io/github/headmaster218/recorder/core -name '*.java' | LC_ALL=C sort > "$out/sources.txt"
printf '%s\n' app/src/test/java/io/github/headmaster218/recorder/core/TerminalBoundaryTest.java >> "$out/sources.txt"
java -Xmx48m -XX:MaxMetaspaceSize=40m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -d "$out" @"$out/sources.txt"
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -cp "$out" io.github.headmaster218.recorder.core.TerminalBoundaryTest
python3 - <<'PY'
from pathlib import Path
engine=Path('app/src/main/java/io/github/headmaster218/recorder/android/CaptureEngine.java').read_text()
assert engine.count('writer = PcmSegmentWriter.terminalAware(')==1
assert 'if (failure == null && stop == Stop.STOP) writer.stop(); else writer.interrupt();' in engine
assert 'writer.beginEpoch(epoch(current,gap));' in engine
print('PASS narrow terminal-aware CaptureEngine wiring; not Android runtime execution')
PY
