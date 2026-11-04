#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
production=$1
android=$2
out=app/build/catchup-queue-tests
mkdir -p "$out"
find app/src/testFixtures/queue-host/adapter app/src/testFixtures/catchup-queue/java -name '*.java' | LC_ALL=C sort > "$out/sources.txt"
printf '%s\n' app/src/testFixtures/finalize-overflow/android/os/Handler.java app/src/testFixtures/ftps-wire/java/android/os/SystemClock.java >> "$out/sources.txt"
java -Xmx64m -XX:MaxMetaspaceSize=64m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -cp "$production:$android" -d "$out" @"$out/sources.txt"
java -Xmx64m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Dqueue.host.bridge="$PWD/app/src/testFixtures/queue-host/sqlite_bridge.py" -cp "$out:$production:$android" io.github.headmaster218.recorder.android.RealQueueCatchupTest
