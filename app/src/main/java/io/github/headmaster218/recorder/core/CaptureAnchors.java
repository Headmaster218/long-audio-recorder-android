package io.github.headmaster218.recorder.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Bounded observations, never a mapping from hardware frames to saved PCM. */
public final class CaptureAnchors {
    public static final int SCHEMA_VERSION = 1, TIMEBASE_BOOTTIME = 1;
    public static final String MAPPING = "UNESTABLISHED";
    public static final int RUN_START = 1, ROUTE_UNCERTAIN = 2, CLOCK_REGRESSION = 4,
        TIMESTAMP_REGRESSION = 8, TIMESTAMP_REPEATED = 16, TIMESTAMP_STALE = 32,
        WALL_JUMP = 64, SUSPEND_UNCERTAIN = 128;
    private static final int KNOWN_FLAGS = 255;
    public enum TimestampStatus { NOT_POLLED, AVAILABLE, UNAVAILABLE, QUERY_EXCEPTION }
    /** Raw BOOTTIME query. framePosition is the unmodified 64-bit AudioRecord value. */
    public static final class Timestamp {
        public final TimestampStatus status;
        public final int result;
        public final long framePosition, nanoTime, queryBeforeNanos, queryAfterNanos;
        public Timestamp(TimestampStatus status,int result,long framePosition,long nanoTime,long before,long after) {
            if (status == null || before < 0 || after < 0
                || (status == TimestampStatus.AVAILABLE && result != 0)
                || (status == TimestampStatus.UNAVAILABLE && result == 0)
                || (status != TimestampStatus.AVAILABLE && (framePosition != 0 || nanoTime != 0))
                || (status == TimestampStatus.NOT_POLLED && (result != 0 || before != 0 || after != 0)))
                throw new IllegalArgumentException("invalid timestamp observation");
            this.status = status; this.result = result; this.framePosition = framePosition; this.nanoTime = nanoTime;
            this.queryBeforeNanos = before; this.queryAfterNanos = after;
        }
        public static Timestamp notPolled() { return new Timestamp(TimestampStatus.NOT_POLLED,0,0,0,0,0); }
    }
    /** Created on the producer before queueing. Samples are interleaved PCM16 values, not frames. */
    public static final class Read {
        public final String processClockDomain, runId;
        public final long ordinal, samplesBefore, samplesAfter, readBeforeNanos, readAfterNanos;
        public final long wallBeforeNanos, wallMillis, wallAfterNanos, uptimeMillis;
        public final long configurationBefore, configurationAfter;
        public final Timestamp timestamp;
        public final int flags;
        public Read(String domain,String runId,long ordinal,long samplesBefore,long samplesAfter,
                    long readBefore,long readAfter,long wallBefore,long wallMillis,long wallAfter,long uptime,
                    long configurationBefore,long configurationAfter,Timestamp timestamp,int flags) {
            if (domain == null || !UUID.fromString(domain).toString().equals(domain)
                || runId == null || runId.length() > 512 || runId.trim().isEmpty()
                || ordinal < 0 || samplesBefore < 0 || samplesAfter <= samplesBefore
                || readBefore < 0 || readAfter < 0 || wallBefore < 0 || wallAfter < 0 || uptime < 0
                || configurationBefore < 0 || configurationAfter < 0 || timestamp == null || (flags & ~KNOWN_FLAGS) != 0)
                throw new IllegalArgumentException("invalid read observation");
            this.processClockDomain = domain; this.runId = runId; this.ordinal = ordinal;
            this.samplesBefore = samplesBefore; this.samplesAfter = samplesAfter;
            this.readBeforeNanos = readBefore; this.readAfterNanos = readAfter;
            this.wallBeforeNanos = wallBefore; this.wallMillis = wallMillis; this.wallAfterNanos = wallAfter;
            this.uptimeMillis = uptime; this.configurationBefore = configurationBefore; this.configurationAfter = configurationAfter;
            this.timestamp = timestamp; this.flags = flags;
        }
    }
    public final Read firstRead, lastRead, firstQueryRead, lastQueryRead, firstAvailableRead, lastAvailableRead;
    public final long runSampleStart, runSampleEnd, readCount, queryCount, unavailableCount, anomalyReadCount;
    public final int flags;
    private CaptureAnchors(Read first,Read last,Read firstQuery,Read lastQuery,Read firstAvailable,Read lastAvailable,long start,long end,
                           long reads,long queries,long unavailable,long anomalies,int flags) {
        if (first == null || last == null || start < first.samplesBefore || start >= first.samplesAfter
            || end <= last.samplesBefore || end > last.samplesAfter || start >= end || reads <= 0
            || queries < 0 || queries > reads || unavailable < 0 || unavailable > queries || anomalies < 0 || anomalies > reads
            || last.ordinal < first.ordinal || reads != last.ordinal - first.ordinal + 1
            || (flags & ~KNOWN_FLAGS) != 0 || (flags & (first.flags | last.flags)) != (first.flags | last.flags)
            || (queries == 0) != (firstQuery == null && lastQuery == null))
            throw new IllegalArgumentException("invalid anchor summary");
        sameRun(first,last);
        if (queries > 0) {
            if (firstQuery == null || lastQuery == null) throw new IllegalArgumentException("missing query summary");
            sameRun(first,firstQuery); sameRun(first,lastQuery);
            if (firstQuery.ordinal < first.ordinal || lastQuery.ordinal > last.ordinal
                || firstQuery.ordinal > lastQuery.ordinal || firstQuery.timestamp.status == TimestampStatus.NOT_POLLED
                || lastQuery.timestamp.status == TimestampStatus.NOT_POLLED) throw new IllegalArgumentException("invalid query summary");
        }
        if (queries == unavailable) {
            if (firstAvailable != null || lastAvailable != null) throw new IllegalArgumentException("unexpected available timestamp");
        } else {
            if (firstAvailable == null || lastAvailable == null) throw new IllegalArgumentException("missing available timestamp");
            sameRun(first,firstAvailable); sameRun(first,lastAvailable);
            if (firstAvailable.ordinal < first.ordinal || lastAvailable.ordinal > last.ordinal
                || firstAvailable.ordinal > lastAvailable.ordinal
                || firstAvailable.timestamp.status != TimestampStatus.AVAILABLE || lastAvailable.timestamp.status != TimestampStatus.AVAILABLE)
                throw new IllegalArgumentException("invalid available timestamp summary");
        }
        this.firstAvailableRead = firstAvailable; this.lastAvailableRead = lastAvailable;
        this.firstRead = first; this.lastRead = last; this.firstQueryRead = firstQuery; this.lastQueryRead = lastQuery;
        this.runSampleStart = start; this.runSampleEnd = end; this.readCount = reads;
        this.queryCount = queries; this.unavailableCount = unavailable; this.anomalyReadCount = anomalies; this.flags = flags;
    }
    private static void sameRun(Read a,Read b) {
        if (!a.processClockDomain.equals(b.processClockDomain) || !a.runId.equals(b.runId))
            throw new IllegalArgumentException("mixed clock domains/runs");
    }
    void validate(CaptureTimeline.Epoch epoch,long frames) {
        if (!firstRead.runId.equals(epoch.runId) || runSampleStart % epoch.format.channels != 0
            || runSampleEnd - runSampleStart != Checks.multiply(frames,epoch.format.channels))
            throw new IllegalArgumentException("anchors do not match saved PCM");
    }
    /** Constant-space per-segment collector, retaining at most six immutable read references. */
    static final class Collector {
        private Read first, last, firstQuery, lastQuery, firstAvailable, lastAvailable;
        private long start, end, reads, queries, unavailable, anomalies;
        private int flags;
        void add(Read read,long pieceStart,long pieceEnd) {
            if (read == null || pieceStart < read.samplesBefore || pieceEnd > read.samplesAfter || pieceStart >= pieceEnd)
                throw new IllegalArgumentException("observation slice");
            if (first == null) { first = read; start = pieceStart; }
            else {
                sameRun(first,read);
                if (pieceStart != end || read.ordinal < last.ordinal || read.ordinal > last.ordinal + 1)
                    throw new IllegalArgumentException("noncontiguous observed PCM");
            }
            if (last == null || read.ordinal != last.ordinal) {
                reads = Checks.add(reads,1);
                if ((read.flags & ~RUN_START) != 0) anomalies = Checks.add(anomalies,1);
                if (read.timestamp.status != TimestampStatus.NOT_POLLED) {
                    queries = Checks.add(queries,1); if (firstQuery == null) firstQuery = read; lastQuery = read;
                    if (read.timestamp.status != TimestampStatus.AVAILABLE) unavailable = Checks.add(unavailable,1);
                    else { if (firstAvailable == null) firstAvailable = read; lastAvailable = read; }
                }
            }
            last = read; end = pieceEnd; flags |= read.flags;
        }
        CaptureAnchors freeze() { return new CaptureAnchors(first,last,firstQuery,lastQuery,firstAvailable,lastAvailable,start,end,reads,queries,unavailable,anomalies,flags); }
    }
    /** Producer-only constant-space anomaly classification. Heuristic flags do not establish a gap. */
    public static final class Observer {
        private Read previous;
        private Timestamp previousAvailable;
        public int flags(long ordinal,long readBefore,long readAfter,long wallBefore,long wallMillis,long wallAfter,
                         long uptime,long configBefore,long configAfter,Timestamp timestamp) {
            int flags = ordinal == 0 ? RUN_START : 0;
            if (readAfter < readBefore || wallAfter < wallBefore ||
                (timestamp.status != TimestampStatus.NOT_POLLED && timestamp.queryAfterNanos < timestamp.queryBeforeNanos)) flags |= CLOCK_REGRESSION;
            if (configBefore != configAfter || (previous != null && previous.configurationAfter != configBefore)) flags |= ROUTE_UNCERTAIN;
            if (previous != null) {
                if (readBefore < previous.readAfterNanos) flags |= CLOCK_REGRESSION;
                double elapsedMillis = (wallBefore - (double) previous.wallBeforeNanos) / 1000000.0;
                if (Math.abs((wallMillis - (double) previous.wallMillis) - elapsedMillis) > 2000.0) flags |= WALL_JUMP;
                if (Math.abs(elapsedMillis - (uptime - (double) previous.uptimeMillis)) > 1000.0) flags |= SUSPEND_UNCERTAIN;
            }
            if (timestamp.status == TimestampStatus.AVAILABLE) {
                if (timestamp.framePosition < 0 || timestamp.nanoTime < 0 || (previousAvailable != null &&
                    (timestamp.framePosition < previousAvailable.framePosition || timestamp.nanoTime < previousAvailable.nanoTime))) flags |= TIMESTAMP_REGRESSION;
                if (previousAvailable != null && (timestamp.framePosition == previousAvailable.framePosition || timestamp.nanoTime == previousAvailable.nanoTime)) flags |= TIMESTAMP_REPEATED;
                if (timestamp.nanoTime > timestamp.queryAfterNanos || timestamp.queryBeforeNanos - (double) timestamp.nanoTime > 2000000000.0) flags |= TIMESTAMP_STALE;
            }
            return flags;
        }
        public void accepted(Read read) {
            previous = read;
            if (read.timestamp.status == TimestampStatus.AVAILABLE) previousAvailable = read.timestamp;
        }
    }
    private static void writeRead(DataOutputStream d,Read r) throws IOException {
        d.writeUTF(r.processClockDomain); d.writeUTF(r.runId); d.writeLong(r.ordinal);
        d.writeLong(r.samplesBefore); d.writeLong(r.samplesAfter); d.writeLong(r.readBeforeNanos); d.writeLong(r.readAfterNanos);
        d.writeLong(r.wallBeforeNanos); d.writeLong(r.wallMillis); d.writeLong(r.wallAfterNanos); d.writeLong(r.uptimeMillis);
        d.writeLong(r.configurationBefore); d.writeLong(r.configurationAfter); d.writeInt(r.flags);
        Timestamp t = r.timestamp; d.writeUTF(t.status.name()); d.writeInt(t.result); d.writeLong(t.framePosition);
        d.writeLong(t.nanoTime); d.writeLong(t.queryBeforeNanos); d.writeLong(t.queryAfterNanos);
    }
    private static Read readRead(DataInputStream d) throws IOException {
        String domain = d.readUTF(), run = d.readUTF(); long ordinal = d.readLong(), before = d.readLong(), after = d.readLong();
        long rb = d.readLong(), ra = d.readLong(), wb = d.readLong(), wall = d.readLong(), wa = d.readLong(), uptime = d.readLong();
        long cb = d.readLong(), ca = d.readLong(); int flags = d.readInt();
        Timestamp t = new Timestamp(TimestampStatus.valueOf(d.readUTF()),d.readInt(),d.readLong(),d.readLong(),d.readLong(),d.readLong());
        return new Read(domain,run,ordinal,before,after,rb,ra,wb,wall,wa,uptime,cb,ca,t,flags);
    }
    void write(DataOutputStream d) throws IOException {
        d.writeInt(SCHEMA_VERSION); d.writeInt(TIMEBASE_BOOTTIME);
        d.writeUTF(MAPPING); d.writeLong(runSampleStart); d.writeLong(runSampleEnd); d.writeLong(readCount);
        d.writeLong(queryCount); d.writeLong(unavailableCount); d.writeLong(anomalyReadCount); d.writeInt(flags);
        writeRead(d,firstRead); writeRead(d,lastRead);
        if (queryCount > 0) { writeRead(d,firstQueryRead); writeRead(d,lastQueryRead); }
        if (queryCount > unavailableCount) { writeRead(d,firstAvailableRead); writeRead(d,lastAvailableRead); }
    }
    static CaptureAnchors read(DataInputStream d) throws IOException {
        if (d.readInt() != SCHEMA_VERSION || d.readInt() != TIMEBASE_BOOTTIME) throw new IOException("unknown anchor schema/timebase");
        if (!MAPPING.equals(d.readUTF())) throw new IOException("unknown anchor mapping");
        long start = d.readLong(), end = d.readLong(), reads = d.readLong(), queries = d.readLong(), unavailable = d.readLong(), anomalies = d.readLong();
        int flags = d.readInt(); Read first = readRead(d), last = readRead(d);
        Read fq = queries > 0 ? readRead(d) : null, lq = queries > 0 ? readRead(d) : null;
        Read fa = queries > unavailable ? readRead(d) : null, la = queries > unavailable ? readRead(d) : null;
        return new CaptureAnchors(first,last,fq,lq,fa,la,start,end,reads,queries,unavailable,anomalies,flags);
    }
}
