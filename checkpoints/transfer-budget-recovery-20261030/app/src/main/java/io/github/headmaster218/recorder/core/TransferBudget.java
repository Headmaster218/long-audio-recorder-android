package io.github.headmaster218.recorder.core;

/** Optional app file-data reservation. Not carrier traffic or a network safety policy. */
public final class TransferBudget {
    public static final int MAX_LOCAL_METADATA_BYTES = 8192;
    public static final long TRANSPORT_ENVELOPE_BYTES = 512L;
    public static final long MARKER_ALLOWANCE_BYTES = 1024L;
    public static final long READ_OVERRUN_ALLOWANCE_BYTES = 6L * 32768L;

    /** Persist all four fields together; do not expose uncommitted values to a network attempt. */
    public static final class State {
        public final boolean enabled;
        public final long limitBytes, chargedBytes, revision;
        public State(boolean enabled, long limitBytes, long chargedBytes, long revision) {
            if (limitBytes < 0 || chargedBytes < 0 || revision < 0 || (enabled && limitBytes == 0))
                throw new IllegalArgumentException("Invalid transfer-budget state");
            this.enabled = enabled; this.limitBytes = limitBytes;
            this.chargedBytes = chargedBytes; this.revision = revision;
        }
    }
    public static final State DEFAULT_STATE = new State(false, 0, 0, 0);

    /** Remaining configured budget; charged may exceed a subsequently lowered limit. */
    public static long available(State state) {
        requireState(state);
        return state.chargedBytes >= state.limitBytes ? 0 : state.limitBytes - state.chargedBytes;
    }

    /**
     * Whole-attempt estimate for the current explicit-FTPS protocol: one upload and
     * two readbacks of each of the WAV, transport metadata, and delivery marker.
     * Reserve six extra read buffers for bounded size-mismatch observations. Reconcile
     * attempts may conservatively use the same estimate. Control, TLS, DNS, socket
     * buffering and carrier overhead are excluded. No bytes are refunded after failure.
     */
    public static long estimate(long wavBytes, long localMetadataBytes) {
        if (wavBytes <= 0 || localMetadataBytes <= 0 || localMetadataBytes > MAX_LOCAL_METADATA_BYTES)
            throw new IllegalArgumentException("Invalid file-data budget estimate input");
        long files = add(wavBytes, localMetadataBytes);
        files = add(files, TRANSPORT_ENVELOPE_BYTES);
        files = add(files, MARKER_ALLOWANCE_BYTES);
        if (files > (Long.MAX_VALUE - READ_OVERRUN_ALLOWANCE_BYTES) / 3)
            throw new IllegalArgumentException("File-data budget estimate overflow");
        return files * 3 + READ_OVERRUN_ALLOWANCE_BYTES;
    }

    /**
     * Return null when an enabled budget is insufficient; otherwise return the next
     * state to persist BEFORE DNS/socket construction. Disabled budgets are unchanged
     * and do not track new attempts. Every retry/recheck must reserve independently.
     * There is deliberately no release/refund operation: crashes retain the full charge.
     */
    public static State reserve(State state, long charge, long expectedRevision) {
        expected(state, expectedRevision);
        if (charge <= 0) throw new IllegalArgumentException("Reservation must be positive");
        if (!state.enabled) return state;
        if (charge > available(state)) return null;
        return new State(true, state.limitBytes, add(state.chargedBytes, charge), next(state));
    }

    /** Saving configuration never resets old charges, even when disabling enforcement. */
    public static State configure(State state, boolean enabled, long limitBytes,
                                  long expectedRevision, boolean inFlight) {
        expected(state, expectedRevision);
        if (inFlight) throw new IllegalStateException("Transfer in flight; budget settings unchanged");
        return new State(enabled, limitBytes, state.chargedBytes, next(state));
    }

    /** Manual new cycle only. Caller serializes this with all attempt admission/I/O. */
    public static State reset(State state, long expectedRevision, boolean inFlight) {
        expected(state, expectedRevision);
        if (inFlight) throw new IllegalStateException("Transfer in flight; budget not reset");
        return new State(state.enabled, state.limitBytes, 0, next(state));
    }

    private static void requireState(State state) {
        if (state == null) throw new IllegalArgumentException("Transfer-budget state unavailable");
    }
    private static void expected(State state, long revision) {
        requireState(state);
        if (revision < 0 || revision != state.revision)
            throw new IllegalStateException("Transfer-budget settings changed; review current state");
    }
    private static long next(State state) {
        if (state.revision == Long.MAX_VALUE) throw new IllegalStateException("Transfer-budget revision exhausted");
        return state.revision + 1;
    }
    private static long add(long a, long b) {
        if (a < 0 || b < 0 || a > Long.MAX_VALUE - b)
            throw new IllegalArgumentException("File-data budget arithmetic overflow");
        return a + b;
    }
    private TransferBudget() { }
}
