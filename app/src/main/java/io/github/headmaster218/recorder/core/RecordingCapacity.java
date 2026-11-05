package io.github.headmaster218.recorder.core;

/** Read-only, whole-segment estimate. No credit for future upload or cleanup. */
public final class RecordingCapacity {
    public final long writableBytes, wholeSegments, seconds;
    private RecordingCapacity(long bytes,long segments,long seconds) {
        writableBytes=bytes;wholeSegments=segments;this.seconds=seconds;
    }
    public static RecordingCapacity estimate(long maximum,long occupied,long free,long freeReserve,
            int objects,long segmentPcmBytes,int segmentSeconds,long bufferedBytes) {
        if(maximum<=0 || occupied<0 || free<0 || freeReserve<0 || objects<0
                || segmentPcmBytes<=0 || segmentSeconds<=0 || bufferedBytes<0)throw new IllegalArgumentException();
        long room=Math.min(subtract(maximum,occupied),subtract(free,freeReserve));
        // Current finalization plus next admission reserve; pending PCM is not free capacity.
        room=subtract(subtract(room,2*DirectorySpool.FINALIZATION_RESERVE),bufferedBytes);
        long overhead=2L*SegmentMetadata.MAX_BYTES+WavHeader.BYTES;
        long segments=segmentPcmBytes>Long.MAX_VALUE-overhead ? 0 : room/(segmentPcmBytes+overhead);
        // Keep one slot for the current/new stage. History and failed stages count too.
        segments=Math.min(segments,Math.max(0L,SpoolUsage.MAX_OBJECTS-(long)objects-1));
        long seconds=segments>Long.MAX_VALUE/segmentSeconds ? Long.MAX_VALUE : segments*segmentSeconds;
        return new RecordingCapacity(room,segments,seconds);
    }
    private static long subtract(long a,long b){return a>b?a-b:0;}
}
