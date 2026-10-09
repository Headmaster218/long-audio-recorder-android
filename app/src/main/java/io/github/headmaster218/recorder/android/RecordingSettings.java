package io.github.headmaster218.recorder.android;

import android.content.Intent;
import io.github.headmaster218.recorder.core.PcmFormat;
import io.github.headmaster218.recorder.core.WavHeader;
import io.github.headmaster218.recorder.core.DirectorySpool;

final class RecordingSettings {
    static final long MIB = 1024L * 1024L;
    static final long FREE_RESERVE = 32 * MIB;
    final int rate, channels, seconds;
    final long cacheBytes, targetFrames;
    final PcmFormat format;
    RecordingSettings(int rate, int channels, int seconds, long cacheMiB) {
        if (rate < 8000 || rate > 96000 || (channels != 1 && channels != 2)
            || seconds < 1 || seconds > 3600 || cacheMiB < 16 || cacheMiB > 262144)
            throw new IllegalArgumentException("Use 8000–96000 Hz, 1–2 channels, 1–3600 seconds and 16–262144 MiB.");
        this.rate = rate; this.channels = channels; this.seconds = seconds; this.cacheBytes = cacheMiB * MIB;
        format = new PcmFormat(rate, channels); targetFrames = format.framesForSeconds(seconds);
        WavHeader.encode(format, targetFrames);
        if (format.bytesForFrames(targetFrames) + WavHeader.BYTES + DirectorySpool.FINALIZATION_RESERVE + 8192 > cacheBytes)
            throw new IllegalArgumentException("Cache maximum must fit a complete segment plus metadata reserve.");
    }
    void put(Intent intent) { intent.putExtra("rate", rate).putExtra("channels", channels).putExtra("seconds", seconds).putExtra("cache_mib", cacheBytes / MIB); }
    static RecordingSettings from(Intent i) { return new RecordingSettings(i.getIntExtra("rate",16000),i.getIntExtra("channels",1),i.getIntExtra("seconds",300),i.getLongExtra("cache_mib",512)); }
    String requestedInput() { return "builtin-microphone;source=MIC;requested=" + rate + "Hz/" + channels + "ch/PCM16LE"; }
}
