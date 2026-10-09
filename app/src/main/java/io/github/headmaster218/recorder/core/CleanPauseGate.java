package io.github.headmaster218.recorder.core;

/** Process-local clean-pause capability. Never reconstructed from persisted preferences. */
public final class CleanPauseGate {
    public static final class Resume {
        public final String captureId;
        public final long nextEpoch;
        private Resume(String captureId,long nextEpoch) { this.captureId = captureId; this.nextEpoch = nextEpoch; }
    }
    private Resume pending;
    public synchronized void cleanPause(String captureId,long nextEpoch) {
        Checks.text(captureId,"captureId");
        if (nextEpoch < 0) throw new IllegalArgumentException("nextEpoch");
        pending = new Resume(captureId,nextEpoch);
    }
    /** One explicit start consumes the capability even if that start later fails. */
    public synchronized Resume consume() { Resume result = pending; pending = null; return result; }
    public synchronized void clear() { pending = null; }
    public synchronized boolean hasCleanPause() { return pending != null; }
}
