#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ ! -f ASSEMBLED_SOURCE.json ]; then echo 'Run in assembled source.' >&2; exit 1; fi
mkdir -p app/build/queue-host-tests
out=$(mktemp -d app/build/queue-host-tests/run-XXXXXX)
fixture=app/src/testFixtures/queue-host
find app/src/main/java/io/github/headmaster218/recorder/core "$fixture/adapter" "$fixture/tests" -name '*.java' | LC_ALL=C sort > "$out/sources.txt"
printf '%s\n' app/src/main/java/io/github/headmaster218/recorder/android/FtpsQueue.java >> "$out/sources.txt"
java -Xmx64m -XX:MaxMetaspaceSize=64m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -d "$out/classes" @"$out/sources.txt"
java -Xmx64m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Dqueue.host.bridge="$PWD/$fixture/sqlite_bridge.py" -cp "$out/classes" io.github.headmaster218.recorder.android.QueueHostTest seed "$out/evidence"
java -Xmx64m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Dqueue.host.bridge="$PWD/$fixture/sqlite_bridge.py" -cp "$out/classes" io.github.headmaster218.recorder.android.QueueHostTest reopen "$out/evidence"
python3 - "$out/evidence/ftps-queue.db" <<'PY'
import sqlite3,sys
with sqlite3.connect(sys.argv[1]) as c:
 assert c.execute('pragma integrity_check').fetchone()==('ok',)
 assert c.execute('pragma foreign_key_check').fetchall()==[]
print('PASS real SQLite integrity and foreign-key checks')
PY

# Explicit historical selection tests use synthetic receipts, not a remote-deletion proof.
java -Xmx64m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Dqueue.host.bridge="$PWD/$fixture/sqlite_bridge.py" -cp "$out/classes" io.github.headmaster218.recorder.android.HistoricalCleanupRequestTest "$out/historical-evidence"
