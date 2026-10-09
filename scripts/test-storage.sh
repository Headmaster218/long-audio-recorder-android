#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=app/build/storage-tests
mkdir -p "$out"
find app/src/main/java app/src/test/java -name '*.java' | LC_ALL=C sort > "$out/sources.txt"
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -Xss256k \
    -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror \
    -d "$out" @"$out/sources.txt"
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -Xss256k \
    -cp "$out" io.github.headmaster218.recorder.core.StorageTest
