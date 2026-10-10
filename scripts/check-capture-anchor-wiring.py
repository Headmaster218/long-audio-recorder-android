#!/usr/bin/env python3
"""Narrow source checks only; no Android execution. Exact projection rejects changed observation code."""
from pathlib import Path
import subprocess

# Literal frozen dev7 addition, intentionally not derived from the source under test.
# Any addition, removal, or alteration requires explicit review of this allowlist.
EXPECTED_OBSERVATION_BLOCK = """                long wallBefore = SystemClock.elapsedRealtimeNanos();
                long wallMillis = System.currentTimeMillis();
                long wallAfter = SystemClock.elapsedRealtimeNanos();
                long uptime = SystemClock.uptimeMillis();
                CaptureAnchors.Timestamp timestamp = CaptureAnchors.Timestamp.notPolled();
                if (lastTimestampQuery < 0 || (readAfter >= lastTimestampQuery && readAfter - lastTimestampQuery >= 1000000000L)) {
                    long queryBefore = SystemClock.elapsedRealtimeNanos();
                    lastTimestampQuery = queryBefore;
                    AudioTimestamp raw = new AudioTimestamp();
                    int result = 0; CaptureAnchors.TimestampStatus status;
                    try {
                        result = recorder.getTimestamp(raw,AudioTimestamp.TIMEBASE_BOOTTIME);
                        status = result == AudioRecord.SUCCESS ? CaptureAnchors.TimestampStatus.AVAILABLE : CaptureAnchors.TimestampStatus.UNAVAILABLE;
                    } catch (RuntimeException unavailable) {
                        status = CaptureAnchors.TimestampStatus.QUERY_EXCEPTION;
                    }
                    long queryAfter = SystemClock.elapsedRealtimeNanos();
                    boolean available = status == CaptureAnchors.TimestampStatus.AVAILABLE;
                    timestamp = new CaptureAnchors.Timestamp(status,result,available ? raw.framePosition : 0,
                        available ? raw.nanoTime : 0,queryBefore,queryAfter);
                }
                int flags = observer.flags(positiveReadOrdinal,readBefore,readAfter,wallBefore,wallMillis,wallAfter,uptime,before,after,timestamp);
                if (held.uncertain || (previousObservedRoute != null && !previousObservedRoute.equals(snapshot.route))) flags |= CaptureAnchors.ROUTE_UNCERTAIN;
                previousObservedRoute = snapshot.route;
                if (runSamples > Long.MAX_VALUE - count || positiveReadOrdinal == Long.MAX_VALUE)
                    throw new IOException("Capture observation counters exhausted; PCM accounting retained");
                held.observation = new CaptureAnchors.Read(PROCESS_CLOCK_DOMAIN,runId,positiveReadOrdinal,runSamples,runSamples+count,
                    readBefore,readAfter,wallBefore,wallMillis,wallAfter,uptime,before,after,timestamp,flags);
                observer.accepted(held.observation); runSamples += count; positiveReadOrdinal++;
"""

REMOVED_LINES = [
    'import android.media.AudioTimestamp;\n',
    'import io.github.headmaster218.recorder.core.CaptureAnchors;\n',
    '    // Identifies only this process clock domain, never hardware or a persistent boot identity.\n',
    '    private static final String PROCESS_CLOCK_DOMAIN = UUID.randomUUID().toString();\n',
    '        long positiveReadOrdinal = 0, runSamples = 0, lastTimestampQuery = -1;\n',
    '        CaptureAnchors.Observer observer = new CaptureAnchors.Observer();\n',
    '        String previousObservedRoute = null;\n',
    '                long readBefore = SystemClock.elapsedRealtimeNanos();\n',
    '                long readAfter = SystemClock.elapsedRealtimeNanos();\n',
]
REPLACED_ADDITIONS = [
    ('boolean uncertain; CaptureAnchors.Read observation;', 'boolean uncertain;'),
    ('writer.append(block.bytes,0,block.count*2,block.observation);', 'writer.append(block.bytes,0,block.count*2);'),
    ('block.snapshot = null; block.observation = null; free.offer(block);', 'block.snapshot = null; free.offer(block);'),
]


def replace_once(source, expected, replacement):
    assert source.count(expected) == 1, 'Missing, changed or duplicated approved addition: ' + expected
    return source.replace(expected, replacement, 1)


def validate_engine(source, baseline):
    assert source.index('long readBefore = SystemClock.elapsedRealtimeNanos()') < source.index('int count = recorder.read(') < source.index('long readAfter = SystemClock.elapsedRealtimeNanos()')
    assert source.index('long wallBefore = SystemClock.elapsedRealtimeNanos()') < source.index('long wallMillis = System.currentTimeMillis()') < source.index('long wallAfter = SystemClock.elapsedRealtimeNanos()')
    assert source.index('held.observation = new CaptureAnchors.Read(') < source.index('ready.offer(held)')
    assert '0xffffffff' not in source
    projected = source
    for line in REMOVED_LINES:
        projected = replace_once(projected, line, '')
    for expected, replacement in REPLACED_ADDITIONS:
        projected = replace_once(projected, expected, replacement)
    projected = replace_once(projected, EXPECTED_OBSERVATION_BLOCK, '')
    assert projected == baseline, 'Pre-existing CaptureEngine logic changed outside exact approved observation additions'


def test_mutation_rejections(source, baseline):
    # Exercise the same validation function used for production. No source file is changed.
    mutations = [
        ('reviewer silent-stop reproduction', '                previousVersion = after;', '                stop = Stop.STOP; // independent mutation\n                previousVersion = after;'),
        ('injection inside observation block', '                long wallMillis = System.currentTimeMillis();', '                stop = Stop.STOP;\n                long wallMillis = System.currentTimeMillis();'),
        ('injection at observation block start', '                long wallBefore = SystemClock.elapsedRealtimeNanos();', '                stop = Stop.STOP;\n                long wallBefore = SystemClock.elapsedRealtimeNanos();'),
        ('timestamp polling budget', 'readAfter - lastTimestampQuery >= 1000000000L', 'readAfter - lastTimestampQuery >= 1L'),
        ('wrong timestamp clock domain', 'AudioTimestamp.TIMEBASE_BOOTTIME', 'AudioTimestamp.TIMEBASE_MONOTONIC'),
        ('silently discard queued PCM', '                observer.accepted(held.observation);', '                ready.clear();\n                observer.accepted(held.observation);'),
        ('swallow query error via early return', 'status = CaptureAnchors.TimestampStatus.QUERY_EXCEPTION;', 'status = CaptureAnchors.TimestampStatus.QUERY_EXCEPTION; return;'),
        ('alter producer cumulative samples', 'runSamples += count;', 'runSamples += 0;'),
        ('remove prior stop-on-full behavior', 'if (held == null) throw new IOException("Bounded storage queue is full; stopped with an unknown capture gap");', 'if (held == null) continue;'),
        ('alter prior PCM serialization', 'block.bytes[i*2] = (byte) value;', 'block.bytes[i*2] = 0;'),
        ('alter prior route policy', 'if (snapshot.unexpectedRoute)', 'if (false)'),
        ('duplicate approved clock read', '                long readBefore = SystemClock.elapsedRealtimeNanos();\n', '                long readBefore = SystemClock.elapsedRealtimeNanos();\n                long readBefore = SystemClock.elapsedRealtimeNanos();\n'),
        ('delete observation attachment', '                observer.accepted(held.observation); runSamples += count; positiveReadOrdinal++;\n', ''),
    ]
    for name, old, new in mutations:
        assert source.count(old) == 1, 'Mutation target is ambiguous or absent: ' + name
        changed = source.replace(old, new, 1)
        assert changed != source
        try:
            validate_engine(changed, baseline)
        except (AssertionError, ValueError):
            continue
        raise AssertionError('Unsafe mutation accepted: ' + name)
    print('PASS exact source gate: ' + str(len(mutations)) + ' in-memory mutation rejection cases, including reviewer silent-stop reproduction')


root = Path(__file__).resolve().parents[1]
p = root / 'app/src/main/java/io/github/headmaster218/recorder'
source = (p / 'android/CaptureEngine.java').read_text()
baseline = subprocess.check_output(['git', '-C', str(root), 'show', '60767916fd4cdd735c5cec2cef643aa3e37523a3:app/src/main/java/io/github/headmaster218/recorder/android/CaptureEngine.java']).decode()
validate_engine(source, baseline)
for name in ['DirectorySpool.java', 'CommittedSegments.java']:
    assert 'anchors.bin' not in (p / 'core' / name).read_text()
print('PASS capture-anchor source wiring; no Android execution or resource validation')
print('PASS exact dev6 CaptureEngine byte comparison after exact observation-only projection; prior capture/stop/queue/failure logic preserved')
test_mutation_rejections(source, baseline)
