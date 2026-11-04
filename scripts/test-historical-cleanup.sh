#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Actual SDK-compiled coordinator/queue, host SQLite adapters, synthetic prior receipts.
# No network transfer or local cleanup deletion is performed by these focused tests.
production=$1
android=$2
out=app/build/historical-cleanup-tests
mkdir -p "$out"
find app/src/testFixtures/queue-host/adapter/android app/src/testFixtures/historical-cleanup/java -name '*.java' | LC_ALL=C sort > "$out/sources.txt"
printf '%s\n' app/src/testFixtures/catchup-queue/java/io/github/headmaster218/recorder/android/RealCoordinatorHarness.java app/src/testFixtures/finalize-overflow/android/os/Handler.java app/src/testFixtures/ftps-wire/java/android/os/SystemClock.java >> "$out/sources.txt"
java -Xmx64m -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -cp "$production:$android" -d "$out" @"$out/sources.txt"
for suite in CleanupRevocationRaceTest HistoricalCleanupControllerTest; do
    java -Xmx64m -XX:MaxMetaspaceSize=48m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
        -Dqueue.host.bridge="$PWD/app/src/testFixtures/queue-host/sqlite_bridge.py" -cp "$out:$production:$android" "io.github.headmaster218.recorder.android.$suite"
done
