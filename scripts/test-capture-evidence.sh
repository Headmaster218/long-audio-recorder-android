#!/bin/sh
# UNEXECUTED source fixture. Run only after a separately approved bounded execution gate.
set -eu
cd "$(dirname "$0")/.."
mkdir -p app/build
out=$(mktemp -d app/build/capture-evidence.XXXXXX)
java -Xmx64m -XX:MaxMetaspaceSize=48m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -Xss256k \
  -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror -d "$out" \
  app/src/main/java/io/github/headmaster218/recorder/core/PcmReadAccounting.java \
  app/src/main/java/io/github/headmaster218/recorder/core/CaptureEvidence.java \
  app/src/test/java/io/github/headmaster218/recorder/core/CaptureEvidenceTest.java
java -Xmx64m -XX:MaxMetaspaceSize=48m -XX:ReservedCodeCacheSize=16m -XX:+UseSerialGC -Xss256k \
  -cp "$out" io.github.headmaster218.recorder.core.CaptureEvidenceTest
# These source-order checks are deliberately narrow and cannot validate Android lifecycle behavior.
python3 - <<'PY'
from pathlib import Path
s = Path('app/src/main/java/io/github/headmaster218/recorder/android/CaptureEngine.java').read_text()
assert s.index('writer.append(block.bytes') < s.index('accounting.appendConfirmed(block.count)') < s.index('firstAppended(block,current)')
assert s.index('producer.join(1000)') < s.index('spool.close()') < s.index('PcmReadAccounting.Snapshot finalAccounting') < s.index('evidence.finish(finalAccounting,') < s.index('completion.finished(this,outcome,')
assert 'producer.start(); producerStarted = true;' in s
assert 'if (producerStarted)' in s and 'else if (record != null) releaseRecord(record)' in s
assert 'evidence.handleObtained();' in s and 'releaseRecord(r); throw e;' in s
assert s.count('recorder.release();') == 1
release = s[s.index('private void releaseRecord('):s.index('@Override public void run()')]
assert release.index('recorder.release();') < release.index('catch (RuntimeException e)') < release.index('evidence.releaseThrew();') < release.index('return;') < release.index('evidence.releaseReturned();')
assert 'evidence.failed(kind);' in s
assert 'if (failure == null) failure = RecorderState.bounded(message);' in s
assert 'if (finalAccounting.hasUnconfirmed()) fail(Failure.PCM_ACCOUNTING,' in s
assert '!current.silenced && !current.unexpectedRoute && !block.uncertain' in s
assert 'CaptureAnchors.ROUTE_UNCERTAIN | CaptureAnchors.CLOCK_REGRESSION' in s
assert s.index('firstPcmEmitted = true;') < s.index('completion.firstPcm(this,first)')
assert 'finished(engine,result,paused,nextEpoch);' in s
service = Path('app/src/main/java/io/github/headmaster218/recorder/android/RecordingService.java').read_text()
assert 'RecoveryGate.ownershipReleased(result)' in service and 'RecoveryGate.storageClosed(result)' in service
assert 's.policy.acknowledgeGap(event)' in service and 'journal.persist(s.id,ordinal,record)' in service
assert 's.policy.takeRetry(retry,authority(),now())' in service
assert 's.policy.pause(now())' in service and 's.policy.stop(now())' in service
assert 'START_NOT_STICKY' in service
print('Capture evidence narrow source-order fixtures passed; no Android execution')
PY
