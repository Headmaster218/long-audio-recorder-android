#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Actual SDK-compiled coordinator/queue and host SQLite; Keystore is a fixture gate.
# No real credential, network transfer, key creation or local deletion occurs.
production=$1
android=$2
out=app/build/credential-retry-tests
mkdir -p "$out"
find app/src/testFixtures/ftps-wire/java/android app/src/testFixtures/queue-host/adapter/android -name '*.java' ! -name Context.java | LC_ALL=C sort > "$out/sources.txt"
find app/src/testFixtures/credential-retry/java -name '*.java' | LC_ALL=C sort >> "$out/sources.txt"
printf '%s\n' app/src/testFixtures/catchup-wire/java/android/content/Context.java app/src/testFixtures/finalize-overflow/android/os/Handler.java app/src/testFixtures/catchup-queue/java/io/github/headmaster218/recorder/android/RealCoordinatorHarness.java >> "$out/sources.txt"
java -Xmx64m -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -cp "$production:$android" -d "$out" @"$out/sources.txt"
java -Xmx64m -XX:MaxMetaspaceSize=48m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -Dqueue.host.bridge="$PWD/app/src/testFixtures/queue-host/sqlite_bridge.py" -cp "$out:$production:$android" io.github.headmaster218.recorder.android.CredentialResumeTest
