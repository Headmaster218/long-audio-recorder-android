#!/bin/sh
# Source-only draft: NOT EXECUTED. Run only after a separately approved host-execution gate.
set -eu
cd "$(dirname "$0")/.."
mkdir -p app/build
out=$(mktemp -d app/build/interruption-recovery.XXXXXX)
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -d "$out" \
    app/src/main/java/io/github/headmaster218/recorder/core/InterruptionRecovery.java \
    app/src/test/java/io/github/headmaster218/recorder/core/InterruptionRecoveryTest.java
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -cp "$out" io.github.headmaster218.recorder.core.InterruptionRecoveryTest
