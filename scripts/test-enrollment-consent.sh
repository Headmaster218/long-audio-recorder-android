#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Existing official SDK + compiled production classes, never downloads.
production=$1
android=$2
out=app/build/enrollment-consent-tests
mkdir -p "$out"
java -Xmx48m -XX:MaxMetaspaceSize=40m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
 -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -cp "$production:$android" -d "$out" \
 app/src/testFixtures/continuous-enrollment/io/github/headmaster218/recorder/android/EnrollmentConsentTest.java
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
 -cp "$out:$production:$android" io.github.headmaster218.recorder.android.EnrollmentConsentTest
