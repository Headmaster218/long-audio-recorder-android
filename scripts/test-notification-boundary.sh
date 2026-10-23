#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p app/build
out=$(mktemp -d app/build/notification-boundary.XXXXXX)
java -Xmx64m -XX:MaxMetaspaceSize=48m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
  -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -d "$out" \
  app/src/main/java/io/github/headmaster218/recorder/core/NotificationBoundary.java \
  app/src/test/java/io/github/headmaster218/recorder/core/NotificationBoundaryTest.java
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
  -cp "$out" io.github.headmaster218.recorder.core.NotificationBoundaryTest
