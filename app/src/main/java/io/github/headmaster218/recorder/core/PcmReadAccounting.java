package io.github.headmaster218.recorder.core;

/** Thread-safe read/append acknowledgements, in interleaved PCM16 samples, not frames. */
public final class PcmReadAccounting {
    public static final class Snapshot {
        public final long readSamples, confirmedSamples, unconfirmedSamples;
        public final boolean counterOverflow;
        private Snapshot(long read,long confirmed,boolean overflow) {
            readSamples = read; confirmedSamples = confirmed; counterOverflow = overflow;
            unconfirmedSamples = read - confirmed;
        }
        public boolean hasUnconfirmed() { return counterOverflow || unconfirmedSamples > 0; }
        /** A failed append may have staged a prefix; never claim that this is an exact measured loss. */
        public String warning() {
            if (counterOverflow) return "Unconfirmed PCM exists; sample counters overflowed, so its size is unknown. ";
            if (unconfirmedSamples == 0) return "";
            return "Unconfirmed PCM: up to " + unconfirmedSamples
                + " read samples in held, queued or in-flight buffers. Some bytes may already be staged; do not replay automatically. ";
        }
    }
    private long read, confirmed;
    private boolean overflow;
    /** Call immediately after every positive AudioRecord read, before attribution/enqueue can fail. */
    public synchronized boolean readAccepted(long samples) {
        if (samples <= 0) throw new IllegalArgumentException("positive sample count required");
        if (read > Long.MAX_VALUE - samples) { read = Long.MAX_VALUE; overflow = true; }
        else read += samples;
        return !overflow;
    }
    /** Call only after the complete append returned successfully; failures remain conservatively unconfirmed. */
    public synchronized boolean appendConfirmed(long samples) {
        if (samples <= 0) throw new IllegalArgumentException("positive sample count required");
        if (!overflow && samples > read - confirmed) throw new IllegalStateException("cannot confirm unread samples");
        if (confirmed > Long.MAX_VALUE - samples) { confirmed = Long.MAX_VALUE; overflow = true; }
        else confirmed += samples;
        return !overflow;
    }
    /** Final reconciliation is meaningful only after the producer has stopped and all desired draining finished. */
    public synchronized Snapshot snapshot() { return new Snapshot(read,confirmed,overflow); }
}
