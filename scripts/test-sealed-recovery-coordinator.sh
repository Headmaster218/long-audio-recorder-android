#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=app/build/sealed-recovery-coordinator-tests
mkdir -p "$out"
find app/src/main/java/io/github/headmaster218/recorder/core -name '*.java' | LC_ALL=C sort > "$out/sources.txt"
admission=app/src/main/java/io/github/headmaster218/recorder/android/SpoolAdmission.java
overlay=checkpoints/recorder-reliability-recovery-20261031/app/src/main/java/io/github/headmaster218/recorder/android/SpoolAdmission.java
if [ -f "$overlay" ]; then admission=$overlay; fi
printf '%s\n' "$admission" app/src/main/java/io/github/headmaster218/recorder/android/SealedRecoveryCoordinator.java >> "$out/sources.txt"
find app/src/testFixtures/sealed-recovery -name '*.java' | LC_ALL=C sort >> "$out/sources.txt"
java -Xmx48m -XX:MaxMetaspaceSize=40m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options,-auxiliaryclass -Werror -d "$out" @"$out/sources.txt"
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -cp "$out" io.github.headmaster218.recorder.android.SealedRecoveryCoordinatorTest
