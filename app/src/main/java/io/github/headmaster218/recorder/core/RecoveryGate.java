package io.github.headmaster218.recorder.core;

/** Final evidence admission only; never creates a recorder or grants Android authority. */
public final class RecoveryGate {
    private RecoveryGate() { }
    public static boolean ownershipReleased(CaptureEvidence.Result r) {
        return r != null && r.producerTerminated
            && (r.release == CaptureEvidence.Release.RETURNED || r.release == CaptureEvidence.Release.NO_HANDLE);
    }
    public static boolean storageClosed(CaptureEvidence.Result r) {
        return r != null && r.writer != CaptureEvidence.Closure.UNCONFIRMED
            && r.spool != CaptureEvidence.Closure.UNCONFIRMED && !r.has(CaptureEvidence.Failure.STORAGE);
    }
    public static boolean cleanPauseEligible(CaptureEvidence.Result r, boolean enginePaused) {
        return enginePaused && ownershipReleased(r) && storageClosed(r) && r.failureMask == 0 && r.allReadSamplesConfirmed();
    }
    /** Finite unconfirmed samples remain uncertain; they are never reclassified as saved or replayed. */
    public static InterruptionRecovery.Reason retryReason(CaptureEvidence.Result r) {
        if (!ownershipReleased(r) || !storageClosed(r) || r.pcm.counterOverflow) return null;
        long allowed = bit(CaptureEvidence.Failure.POLICY_SILENCED)
            | bit(CaptureEvidence.Failure.ROUTE_UNAVAILABLE) | bit(CaptureEvidence.Failure.DEAD_RECORDER)
            | bit(CaptureEvidence.Failure.PCM_ACCOUNTING);
        if ((r.failureMask & ~allowed) != 0) return null;
        if (r.has(CaptureEvidence.Failure.POLICY_SILENCED)) return InterruptionRecovery.Reason.MICROPHONE_CONTENTION;
        if (r.has(CaptureEvidence.Failure.ROUTE_UNAVAILABLE)) return InterruptionRecovery.Reason.ROUTE_UNAVAILABLE;
        if (r.has(CaptureEvidence.Failure.DEAD_RECORDER)) return InterruptionRecovery.Reason.DEAD_RECORDER;
        return null;
    }
    private static long bit(CaptureEvidence.Failure f) { return 1L << f.ordinal(); }
}
