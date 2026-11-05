package io.github.headmaster218.recorder.android;

import io.github.headmaster218.recorder.core.RecordingCapacity;

/** Process-local telemetry, never persisted as a promise about a future session. */
final class RecordingCapacityDisplay {
    private static volatile String value;
    private static volatile long sampledAt;
    private RecordingCapacityDisplay() { }
    static void clear(){value=null;sampledAt=0;}
    static void update(RecordingCapacity capacity) {
        long minutes=capacity.seconds/60;
        value="Storage estimate: "+capacity.wholeSegments+" whole-segment equivalents of recording time (about "+(minutes/60)+" h "+(minutes%60)
            +" min; rounded down). Remaining byte headroom after reserves: "+capacity.writableBytes+" bytes."
            +"\nUses this run's settings, current spool, free space and object limit; no future upload/cleanup credit. Partial-segment time is omitted. Input changes, other disk use or errors can stop recording sooner. Zero means less than one full segment, not necessarily no space.";
        sampledAt=System.nanoTime();
    }
    static String display(boolean active) {
        if(!active)return "Remaining recording time is estimated only during an active run. Saved/displayed settings do not change an active run.";
        String current=value;
        if(current==null || System.nanoTime()-sampledAt>5000000000L)return "Storage estimate unavailable or stale; recording status above is authoritative.";
        return current;
    }
}
