package io.github.headmaster218.recorder.core;

/** Single-writer frame bookkeeping. Rotation never creates a run or epoch. No I/O. */
public final class CaptureTimeline {
    public enum Gap { START, RESTART, ROUTE_CHANGE_UNCERTAIN, FORMAT_CHANGE,
        POLICY_SILENCE, DROPPED_FRAMES, UNKNOWN }
    public enum Close { ROTATION, STOP, INTERRUPTION, RECOVERED_TAIL }
    public static final class Epoch {
        public final String captureId, runId, requestedInput, actualInput;
        public final long number;
        public final PcmFormat format;
        public final Gap boundary;
        public Epoch(String captureId, String runId, long number, PcmFormat format,
                     String requestedInput, String actualInput, Gap boundary) {
            this.captureId = Checks.text(captureId, "captureId");
            this.runId = Checks.text(runId, "runId");
            if (number < 0 || format == null || boundary == null) throw new IllegalArgumentException();
            this.number = number; this.format = format; this.boundary = boundary;
            this.requestedInput = Checks.text(requestedInput, "requestedInput");
            this.actualInput = Checks.text(actualInput, "actualInput");
        }
    }
    public static final class SegmentManifest {
        public static final int SCHEMA_VERSION = 1;
        public final Epoch epoch;
        public final long sequence, frameStart, frameCount, payloadBytes;
        public final String payloadSha256;
        public final Close closeReason;
        public final boolean trustedPreviousSeam;
        private SegmentManifest(Epoch epoch, long sequence, long frameStart, long frameCount,
                                String hash, Close closeReason, boolean trustedPreviousSeam) {
            this.epoch = epoch; this.sequence = sequence; this.frameStart = frameStart;
            this.frameCount = frameCount; this.payloadBytes = epoch.format.bytesForFrames(frameCount);
            this.payloadSha256 = hash; this.closeReason = closeReason;
            this.trustedPreviousSeam = trustedPreviousSeam;
        }
    }
    private Epoch epoch;
    private final long targetFrames;
    private long sequence, epochFrames, openFrames;
    private SegmentManifest previous;
    private boolean epochClosed;
    public CaptureTimeline(Epoch epoch, long targetFrames, long nextSequence) {
        if (epoch == null || targetFrames <= 0 || nextSequence < 0) throw new IllegalArgumentException();
        epoch.format.bytesForFrames(targetFrames);
        this.epoch = epoch; this.targetFrames = targetFrames; this.sequence = nextSequence;
    }
    public long framesUntilBoundary() { return targetFrames - openFrames; }
    /** Caller splits an input buffer at this boundary, then seals and continues the same stream. */
    public void appendFrames(long frames) {
        if (epochClosed) throw new IllegalStateException("start a new epoch after interruption or stop");
        if (frames <= 0 || frames > framesUntilBoundary()) throw new IllegalArgumentException("split at boundary");
        long nextEpochFrames = Checks.add(epochFrames, frames);
        openFrames += frames; epochFrames = nextEpochFrames;
    }
    public SegmentManifest seal(String payloadSha256, Close reason) {
        Checks.hash(payloadSha256);
        if (reason == null || openFrames == 0) throw new IllegalStateException("empty or invalid close");
        if (reason == Close.ROTATION && openFrames != targetFrames)
            throw new IllegalStateException("rotation requires exact target frame count");
        long nextSequence = Checks.add(sequence, 1);
        long start = epochFrames - openFrames;
        boolean seam = previous != null && previous.epoch == epoch
            && previous.closeReason == Close.ROTATION && reason != Close.RECOVERED_TAIL
            && Checks.add(previous.frameStart, previous.frameCount) == start;
        SegmentManifest result = new SegmentManifest(epoch, sequence, start, openFrames,
                                                     payloadSha256, reason, seam);
        sequence = nextSequence; openFrames = 0; previous = result;
        epochClosed = reason != Close.ROTATION;
        return result;
    }
    /** Must close the old tail before acknowledging a gap; never silently discard pending frames. */
    public void beginEpoch(Epoch next) {
        if (openFrames != 0) throw new IllegalStateException("seal old tail first");
        if (next == null || !next.captureId.equals(epoch.captureId)
            || next.number <= epoch.number || next.boundary == Gap.START)
            throw new IllegalArgumentException("invalid epoch transition");
        if (next.boundary == Gap.RESTART && next.runId.equals(epoch.runId))
            throw new IllegalArgumentException("restart requires new run identity");
        if (!next.runId.equals(epoch.runId) && next.boundary != Gap.RESTART)
            throw new IllegalArgumentException("new run must record restart");
        next.format.bytesForFrames(targetFrames);
        epoch = next; epochFrames = 0; epochClosed = false;
    }
}
