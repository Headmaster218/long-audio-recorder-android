package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import io.github.headmaster218.recorder.core.CaptureTimeline.Close;
import io.github.headmaster218.recorder.core.CaptureTimeline.Epoch;
import io.github.headmaster218.recorder.core.CaptureTimeline.Gap;
import io.github.headmaster218.recorder.core.SegmentMetadata.Intent;

/** Bounded single-writer PCM stream: no recording lifecycle or threads are owned here. */
public final class PcmSegmentWriter {
    public interface Listener { void published(SegmentStore.Published segment) throws IOException; }
    private final SegmentStore store;
    private final Listener listener;
    private final CaptureTimeline timeline;
    private final long targetFrames;
    private Epoch epoch;
    private long sequence, epochFrames, segmentBytes;
    private SegmentStore.Staging staging;
    private MessageDigest pcmHash = sha256();
    private boolean terminal;
    public PcmSegmentWriter(SegmentStore store, Epoch epoch, long targetFrames, long nextSequence, Listener listener) {
        if (store == null || listener == null) throw new IllegalArgumentException();
        new Intent(epoch, nextSequence, 0, targetFrames);
        this.store = store; this.listener = listener; this.epoch = epoch;
        this.targetFrames = targetFrames; this.sequence = nextSequence;
        this.timeline = new CaptureTimeline(epoch, targetFrames, nextSequence);
    }
    public void append(byte[] bytes, int offset, int length) throws IOException {
        live();
        if (bytes == null || offset < 0 || length < 0 || offset > bytes.length - length)
            throw new IllegalArgumentException("input range");
        try {
            while (length > 0) {
                if (staging == null) {
                    if (sequence == Long.MAX_VALUE) throw new IOException("sequence exhausted");
                    staging = store.open(new Intent(epoch, sequence, epochFrames, targetFrames));
                }
                int frameBytes = epoch.format.channels * 2;
                long targetBytes = epoch.format.bytesForFrames(targetFrames);
                int count = (int) Math.min(Math.min(length, 8192), targetBytes - segmentBytes);
                long completedFrames = (segmentBytes % frameBytes + count) / frameBytes;
                if (epochFrames > Long.MAX_VALUE - completedFrames) throw new IOException("frame counter exhausted");
                staging.append(bytes, offset, count);
                pcmHash.update(bytes, offset, count);
                if (completedFrames > 0) timeline.appendFrames(completedFrames);
                segmentBytes += count; epochFrames += completedFrames; offset += count; length -= count;
                if (segmentBytes == targetBytes) finish(Close.ROTATION);
            }
        } catch (IOException | RuntimeException e) { fail(e); throw e; }
    }
    /** A route/restart boundary never borrows continuity from the previous epoch. */
    public void beginEpoch(Epoch next) throws IOException {
        live();
        new Intent(next, sequence, 0, targetFrames);
        if (!next.captureId.equals(epoch.captureId) || next.number <= epoch.number || next.boundary == Gap.START
            || (next.boundary == Gap.RESTART && next.runId.equals(epoch.runId))
            || (next.boundary != Gap.RESTART && !next.runId.equals(epoch.runId)))
            throw new IllegalArgumentException("invalid epoch transition");
        try {
            if (staging != null) finish(Close.INTERRUPTION);
            timeline.beginEpoch(next); epoch = next; epochFrames = 0;
        } catch (IOException | RuntimeException e) { fail(e); throw e; }
    }
    public void stop() throws IOException { close(Close.STOP); }
    public void interrupt() throws IOException { close(Close.INTERRUPTION); }
    private void close(Close reason) throws IOException {
        live();
        try { if (staging != null) finish(reason); terminal = true; }
        catch (IOException | RuntimeException e) { fail(e); throw e; }
    }
    private void finish(Close reason) throws IOException {
        if (segmentBytes % (epoch.format.channels * 2) != 0)
            throw new IOException("incomplete frame preserved in staging; recovery required");
        SegmentStore.Published result = staging.finish(timeline.seal(hex(pcmHash.digest()), reason));
        staging = null; segmentBytes = 0; sequence++; pcmHash = sha256();
        listener.published(result);
    }
    private void fail(Throwable failure) {
        terminal = true;
        if (staging != null) {
            try { staging.preserve(); } catch (IOException e) { failure.addSuppressed(e); }
        }
    }
    private void live() { if (terminal) throw new IllegalStateException("writer stopped/failed; new run required"); }
    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static String hex(byte[] digest) {
        char[] out = new char[digest.length * 2]; char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < digest.length; i++) { out[i*2] = digits[(digest[i] & 255) >>> 4]; out[i*2+1] = digits[digest[i] & 15]; }
        return new String(out);
    }
}
