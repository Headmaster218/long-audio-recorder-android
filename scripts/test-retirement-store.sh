#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=app/build/retirement-store-tests
mkdir -p "$out"
# The test database port models only the reconciliation boundary. Refuse a stale cap.
grep -Eq 'MAX_RETIRING[[:space:]]*=[[:space:]]*8([,;[:space:]]|$)' \
    app/src/main/java/io/github/headmaster218/recorder/android/FtpsQueue.java
find app/src/main/java/io/github/headmaster218/recorder/core -name '*.java' | LC_ALL=C sort > "$out/sources.txt"
printf '%s\n' app/src/main/java/io/github/headmaster218/recorder/android/RetirementStore.java >> "$out/sources.txt"
find app/src/testFixtures/retirement-store -name '*.java' | LC_ALL=C sort >> "$out/sources.txt"
# Compile the actual production class and actual core, never a copied reconciliation algorithm.
java -Xmx48m -XX:MaxMetaspaceSize=40m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -d "$out" @"$out/sources.txt"
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -cp "$out" io.github.headmaster218.recorder.android.RetirementStoreTest
