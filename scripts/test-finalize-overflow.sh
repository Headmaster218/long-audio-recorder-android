#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Execute actual SDK-compiled coordinator; journal/main thread are host fixtures.
production=$1
android=$2
out=app/build/finalize-overflow-tests
mkdir -p "$out"
find app/src/testFixtures/finalize-overflow -name '*.java' | LC_ALL=C sort > "$out/sources.txt"
java -Xmx48m -XX:MaxMetaspaceSize=48m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -cp "$production:$android" -d "$out" @"$out/sources.txt"
java -Xmx64m -XX:MaxMetaspaceSize=48m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -cp "$out:$production:$android" io.github.headmaster218.recorder.android.PublishedNotificationTest
