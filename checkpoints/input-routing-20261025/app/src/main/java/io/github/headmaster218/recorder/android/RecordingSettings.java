package io.github.headmaster218.recorder.android;

import android.content.Intent;
import io.github.headmaster218.recorder.core.InputRoutePolicy;
import io.github.headmaster218.recorder.core.PcmFormat;
import io.github.headmaster218.recorder.core.WavHeader;
import io.github.headmaster218.recorder.core.DirectorySpool;

final class RecordingSettings {
    static final long MIB = 1024L * 1024L;
    static final long FREE_RESERVE = 32 * MIB;
    final int rate, channels, seconds;
    final long cacheBytes, targetFrames;
    final PcmFormat format;
    final InputRoutePolicy.Request input;
    static RecordingSettings defaults() { return new RecordingSettings(16000,1,300,512); }
    RecordingSettings(int rate, int channels, int seconds, long cacheMiB) {
        this(rate,channels,seconds,cacheMiB,InputRoutePolicy.Request.phone());
    }
    RecordingSettings(int rate,int channels,int seconds,long cacheMiB,InputRoutePolicy.Request input) {
        if (input == null) throw new IllegalArgumentException("Input request required");
        this.input=input;
        if (rate < 8000 || rate > 96000 || (channels != 1 && channels != 2)
            || seconds < 1 || seconds > 3600 || cacheMiB < 16 || cacheMiB > 262144)
            throw new IllegalArgumentException("Use 8000–96000 Hz, 1–2 channels, 1–3600 seconds and 16–262144 MiB.");
        this.rate = rate; this.channels = channels; this.seconds = seconds; this.cacheBytes = cacheMiB * MIB;
        format = new PcmFormat(rate, channels); targetFrames = format.framesForSeconds(seconds);
        WavHeader.encode(format, targetFrames);
        if (format.bytesForFrames(targetFrames) + WavHeader.BYTES + DirectorySpool.FINALIZATION_RESERVE + 8192 > cacheBytes)
            throw new IllegalArgumentException("Cache maximum must fit a complete segment plus metadata reserve.");
    }
    void put(Intent intent) { intent.putExtra("rate", rate).putExtra("channels", channels).putExtra("seconds", seconds).putExtra("cache_mib", cacheBytes / MIB).putExtra("input_kind",input.kind).putExtra("input_id",input.id).putExtra("input_phone_fallback",input.phoneFallback ? 1 : 0); }
    static RecordingSettings from(Intent i) { return new RecordingSettings(i.getIntExtra("rate",16000),i.getIntExtra("channels",1),i.getIntExtra("seconds",300),i.getLongExtra("cache_mib",512),new InputRoutePolicy.Request(i.getIntExtra("input_kind",InputRoutePolicy.PHONE),i.getIntExtra("input_id",0),i.getIntExtra("input_phone_fallback",0) == 1)); }
    String requestedInput() { return input.description() + ";source=MIC;requested=" + rate + "Hz/" + channels + "ch/PCM16LE"; }

    /** Process-local permission authority; never persisted or restored with UI state. */
    static final class PendingStart {
        private static int nextRequest = 16384; // Separate from FTPS LAN request 6201.
        private int request;
        private RecordingSettings snapshot;
        private static synchronized int allocate() {
            if (nextRequest > 65535) throw new IllegalStateException("Permission request limit reached. Restart the app before starting.");
            return nextRequest++;
        }
        boolean waiting() { return snapshot != null; }
        int begin(RecordingSettings settings) {
            if (waiting()) throw new IllegalStateException("A microphone permission request is already pending.");
            request = allocate(); snapshot = settings; return request;
        }
        boolean matches(int resultRequest) { return waiting() && request == resultRequest; }
        RecordingSettings consume(int resultRequest) {
            if (!matches(resultRequest)) return null;
            RecordingSettings result = snapshot; cancel(); return result;
        }
        void cancel() { snapshot = null; request = 0; }
    }
}
