package io.github.headmaster218.recorder.core;

/** Per-engine evidence, not permission to start/retry recording. No I/O, timers or audio copies. */
public final class CaptureEvidence {
    public enum Failure {
        INITIALIZATION, POLICY_SILENCED, ROUTE_UNAVAILABLE, DEAD_RECORDER,
        PERMISSION_OR_VISIBILITY, CLIENT_FORMAT, READ_FAILED, NO_DATA,
        BUFFER_PRESSURE, OBSERVATION, STORAGE, PCM_ACCOUNTING,
        STOP_FAILED, CALLBACK_CLEANUP, RELEASE_FAILED, WORKER_INTERRUPTED,
        EVIDENCE_CALLBACK, UNKNOWN
    }
    /** NO_HANDLE means no handle was returned to this engine, not proof of native allocator state. */
    public enum Release { NO_HANDLE, UNCONFIRMED, RETURNED, THREW }
    /** CLOSED means the relevant close call returned; it is not a universal durability guarantee. */
    public enum Closure { NOT_OPENED, UNCONFIRMED, CLOSED }

    public static final class FirstPcm {
        public final String captureId, runId;
        public final long readOrdinal, readBeforeNanos, readAfterNanos, appendedAtNanos;
        public final int samples, channels;
        private FirstPcm(String captureId,String runId,long ordinal,long before,long after,
                         long appended,int samples,int channels) {
            this.captureId = captureId; this.runId = runId; readOrdinal = ordinal;
            readBeforeNanos = before; readAfterNanos = after; appendedAtNanos = appended;
            this.samples = samples; this.channels = channels;
        }
    }
    public static final class Result {
        public final String captureId, runId;
        public final Failure firstFailure;
        public final long failureMask;
        public final Release release;
        public final Closure writer, spool;
        public final boolean producerTerminated;
        public final PcmReadAccounting.Snapshot pcm;
        public final FirstPcm firstPcm;
        private Result(CaptureEvidence source,PcmReadAccounting.Snapshot pcm,boolean producerTerminated) {
            captureId = source.captureId; runId = source.runId; firstFailure = source.firstFailure;
            failureMask = source.failureMask; release = source.release; writer = source.writer;
            spool = source.spool; this.pcm = pcm; this.producerTerminated = producerTerminated;
            firstPcm = source.firstPcm;
        }
        public boolean has(Failure failure) { return (failureMask & bit(failure)) != 0; }
        /** Read/append accounting only. A successful append can still be an unpublished partial. */
        public boolean allReadSamplesConfirmed() { return producerTerminated && !pcm.hasUnconfirmed(); }
    }

    private final String captureId, runId;
    private Failure firstFailure;
    private long failureMask;
    private Release release = Release.NO_HANDLE;
    private Closure writer = Closure.NOT_OPENED, spool = Closure.NOT_OPENED;
    private FirstPcm firstPcm;
    private Result terminal;
    public CaptureEvidence(String captureId,String runId) {
        if (captureId == null || captureId.isEmpty() || runId == null || runId.isEmpty())
            throw new IllegalArgumentException("run identity required");
        this.captureId = captureId; this.runId = runId;
    }
    public synchronized void failed(Failure failure) {
        live(); long flag = bit(failure);
        if (firstFailure == null) firstFailure = failure;
        failureMask |= flag;
    }
    public synchronized void handleObtained() {
        live(); if (release != Release.NO_HANDLE) throw new IllegalStateException("one handle per engine");
        release = Release.UNCONFIRMED;
    }
    /** Call only after AudioRecord.release returned normally, never merely after worker termination. */
    public synchronized void releaseReturned() {
        live(); if (release != Release.UNCONFIRMED) throw new IllegalStateException("no pending release");
        release = Release.RETURNED;
    }
    public synchronized void releaseThrew() {
        live(); if (release != Release.UNCONFIRMED) throw new IllegalStateException("no pending release");
        release = Release.THREW; failed(Failure.RELEASE_FAILED);
    }
    public synchronized void openingSpool() {
        live(); if (spool != Closure.NOT_OPENED) throw new IllegalStateException("spool already attempted");
        spool = Closure.UNCONFIRMED;
    }
    public synchronized void spoolClosed() {
        live(); if (spool != Closure.UNCONFIRMED) throw new IllegalStateException("spool not open");
        spool = Closure.CLOSED;
    }
    public synchronized void openingWriter() {
        live(); if (writer != Closure.NOT_OPENED) throw new IllegalStateException("writer already attempted");
        writer = Closure.UNCONFIRMED;
    }
    public synchronized void writerClosed() {
        live(); if (writer != Closure.UNCONFIRMED) throw new IllegalStateException("writer not open");
        writer = Closure.CLOSED;
    }
    /**
     * Call only AFTER append and its successful accounting acknowledgement. Eligibility excludes
     * policy-silenced, unexpected/uncertain-route and incomplete-frame observations. This is an
     * observed read/append boundary, not proof of audible content or gap-free physical capture.
     */
    public synchronized FirstPcm appended(int samples,int channels,boolean eligible,long ordinal,
                                         long beforeNanos,long afterNanos,long appendedAtNanos) {
        live();
        if (samples <= 0 || channels < 1 || channels > 2 || ordinal < 0 || beforeNanos < 0
            || afterNanos < beforeNanos || appendedAtNanos < afterNanos)
            throw new IllegalArgumentException("invalid append observation");
        if (firstPcm != null || !eligible || samples % channels != 0) return null;
        firstPcm = new FirstPcm(captureId,runId,ordinal,beforeNanos,afterNanos,appendedAtNanos,samples,channels);
        return firstPcm;
    }
    /** Freeze only after producer join and storage cleanup; no later mutation is accepted. */
    public synchronized Result finish(PcmReadAccounting.Snapshot pcm,boolean producerTerminated) {
        live(); if (pcm == null) throw new IllegalArgumentException("accounting snapshot required");
        if (pcm.hasUnconfirmed()) failed(Failure.PCM_ACCOUNTING);
        terminal = new Result(this,pcm,producerTerminated);
        return terminal;
    }
    private void live() { if (terminal != null) throw new IllegalStateException("evidence already frozen"); }
    private static long bit(Failure failure) {
        if (failure == null) throw new IllegalArgumentException("failure required");
        return 1L << failure.ordinal();
    }
}
