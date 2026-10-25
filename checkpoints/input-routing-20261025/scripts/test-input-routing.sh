#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=app/build/input-routing-tests
mkdir -p "$out"
find app/src/main/java/io/github/headmaster218/recorder/core -name '*.java' | LC_ALL=C sort > "$out/sources.txt"
printf '%s\n' app/src/test/java/io/github/headmaster218/recorder/core/InputRoutePolicyTest.java >> "$out/sources.txt"
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
  -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -d "$out" \
  @"$out/sources.txt"
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
  -cp "$out" io.github.headmaster218.recorder.core.InputRoutePolicyTest
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
  -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -cp "$out" -d "$out" \
  app/src/testFixtures/settings/android/content/Intent.java \
  app/src/main/java/io/github/headmaster218/recorder/android/RecordingSettings.java \
  app/src/main/java/io/github/headmaster218/recorder/android/RecordingSettingsStore.java \
  app/src/testFixtures/settings/io/github/headmaster218/recorder/android/RecordingSettingsTest.java \
  app/src/testFixtures/settings/io/github/headmaster218/recorder/android/InputSettingsTest.java
for test in RecordingSettingsTest InputSettingsTest; do
  java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
    -cp "$out" "io.github.headmaster218.recorder.android.$test"
done
