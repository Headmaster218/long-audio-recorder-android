#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p app/build
out=$(mktemp -d app/build/recovery-session.XXXXXX)
find app/src/main/java/io/github/headmaster218/recorder/core -name '*.java' | LC_ALL=C sort > "$out/sources.txt"
printf '%s\n' app/src/test/java/io/github/headmaster218/recorder/core/RecoverySessionTest.java >> "$out/sources.txt"
java -Xmx64m -XX:MaxMetaspaceSize=48m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
  -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -d "$out" @"$out/sources.txt"
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
  -cp "$out" io.github.headmaster218.recorder.core.RecoverySessionTest
