package io.github.headmaster218.recorder.core;

/** PCM16 little-endian. Configuration is a request, never proof of device support. */
public final class PcmFormat {
    public static final PcmFormat DEFAULT = new PcmFormat(16000, 1);
    public final int sampleRateHz;
    public final int channels;
    public PcmFormat(int sampleRateHz, int channels) {
        if (sampleRateHz <= 0 || channels <= 0 || channels > 32)
            throw new IllegalArgumentException("invalid PCM format");
        this.sampleRateHz = sampleRateHz;
        this.channels = channels;
    }
    public long bytesForFrames(long frames) { return Checks.multiply(frames, channels * 2L); }
    public long framesForSeconds(long seconds) { return Checks.multiply(seconds, sampleRateHz); }
    @Override public boolean equals(Object other) {
        if (!(other instanceof PcmFormat)) return false;
        PcmFormat f = (PcmFormat) other;
        return sampleRateHz == f.sampleRateHz && channels == f.channels;
    }
    @Override public int hashCode() { return sampleRateHz * 31 + channels; }
}
