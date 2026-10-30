#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=app/build/transfer-budget/classes
mkdir -p "$out"
java -Xmx48m -XX:MaxMetaspaceSize=40m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
  -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -d "$out" \
  app/src/main/java/io/github/headmaster218/recorder/core/TransferPolicy.java \
  app/src/main/java/io/github/headmaster218/recorder/core/TransferBudget.java \
  app/src/test/java/io/github/headmaster218/recorder/core/TransferBudgetTest.java
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
  -cp "$out" io.github.headmaster218.recorder.core.TransferBudgetTest
