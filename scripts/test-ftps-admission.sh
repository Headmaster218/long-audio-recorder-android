#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Inputs are existing official SDK jar and already compiled production classes; never download.
android=$1
production=app/build/ftps-checks/android-classes
out=app/build/ftps-admission
mkdir -p "$out"
find app/src/testFixtures/ftps-admission -name '*.java' | LC_ALL=C sort > "$out/sources.txt"
java -Xmx48m -XX:MaxMetaspaceSize=40m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
 -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -cp "$production:$android" -d "$out" @"$out/sources.txt"
java -Xmx32m -XX:MaxMetaspaceSize=32m -XX:ReservedCodeCacheSize=8m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss256k \
 -cp "$out:$production:$android" io.github.headmaster218.recorder.android.FtpsAdmissionTest
