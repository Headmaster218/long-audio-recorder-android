#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=app/build/offline-day-plan-tests
mkdir -p "$out"
find app/src/main/java/io/github/headmaster218/recorder/core -name '*.java' | LC_ALL=C sort > "$out/sources.txt"
printf '%s\n' app/src/test/java/io/github/headmaster218/recorder/core/OfflineDayPlanTest.java >> "$out/sources.txt"
java -Xmx64m -XX:MaxMetaspaceSize=48m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -d "$out" @"$out/sources.txt"
java -Xmx64m -XX:MaxMetaspaceSize=48m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k -cp "$out" io.github.headmaster218.recorder.core.OfflineDayPlanTest
