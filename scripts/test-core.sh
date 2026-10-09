#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=app/build/core-tests
mkdir -p "$out"
find app/src/main/java app/src/test/java -name '*.java' | LC_ALL=C sort > "$out/sources.txt"
# This environment provides the compiler module, but no javac launcher or Java 8 boot classpath.
# Java 8 syntax/classfile target; actual execution and API availability are checked only on JVM 21.
java -Xmx64m -XX:MaxMetaspaceSize=48m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -Xss256k \
    -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror \
    -d "$out" @"$out/sources.txt"
java -Xmx64m -XX:MaxMetaspaceSize=48m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -Xss256k \
    -cp "$out" io.github.headmaster218.recorder.core.CoreTest
