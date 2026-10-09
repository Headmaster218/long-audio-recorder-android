package io.github.headmaster218.recorder.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import io.github.headmaster218.recorder.core.CaptureTimeline.Close;
import io.github.headmaster218.recorder.core.CaptureTimeline.Epoch;
import io.github.headmaster218.recorder.core.CaptureTimeline.Gap;
import io.github.headmaster218.recorder.core.CaptureTimeline.SegmentManifest;

/** Bounded, versioned local binary journal/manifest; not the future transport schema. */
public final class SegmentMetadata {
    public static final int MAX_BYTES = 8192;
    private static final int MAGIC = 0x50434d31, VERSION = 1;
    public static final class Intent {
        public final Epoch epoch;
        public final long sequence, frameStart, targetFrames;
        public Intent(Epoch epoch, long sequence, long frameStart, long targetFrames) {
            if (epoch == null || sequence < 0 || frameStart < 0 || targetFrames <= 0)
                throw new IllegalArgumentException("invalid segment intent");
            bounded(epoch.captureId); bounded(epoch.runId); bounded(epoch.requestedInput); bounded(epoch.actualInput);
            WavHeader.encode(epoch.format, targetFrames);
            this.epoch = epoch; this.sequence = sequence; this.frameStart = frameStart; this.targetFrames = targetFrames;
        }
    }
    public final Intent intent;
    public final long frameCount, pcmBytes, wavBytes;
    public final String pcmSha256, wavSha256;
    public final Close closeReason;
    public final boolean trustedPreviousSeam;
    public SegmentMetadata(Intent intent, SegmentManifest manifest, String wavSha256) {
        this(intent, manifest.frameCount, manifest.payloadSha256, wavSha256, manifest.closeReason,
             manifest.trustedPreviousSeam);
        if (manifest.epoch != intent.epoch || manifest.sequence != intent.sequence
            || manifest.frameStart != intent.frameStart || manifest.payloadBytes != pcmBytes)
            throw new IllegalArgumentException("manifest does not match staged object");
    }
    private SegmentMetadata(Intent intent, long frames, String pcmHash, String wavHash, Close close, boolean seam) {
        if (intent == null || frames <= 0 || frames > intent.targetFrames || close == null
            || (close == Close.ROTATION && frames != intent.targetFrames)
            || (close == Close.RECOVERED_TAIL && seam)) throw new IllegalArgumentException("invalid manifest");
        Checks.add(intent.frameStart, frames);
        this.intent = intent; this.frameCount = frames;
        this.pcmBytes = intent.epoch.format.bytesForFrames(frames); this.wavBytes = Checks.add(pcmBytes, WavHeader.BYTES);
        this.pcmSha256 = Checks.hash(pcmHash); this.wavSha256 = Checks.hash(wavHash);
        this.closeReason = close; this.trustedPreviousSeam = seam;
    }
    private static void bounded(String value) {
        Checks.text(value, "metadata text");
        if (value.length() > 512) throw new IllegalArgumentException("metadata text exceeds 512 UTF-16 units");
    }
    private static void writeIntent(DataOutputStream d, Intent i) throws IOException {
        d.writeInt(MAGIC); d.writeInt(VERSION);
        d.writeUTF(i.epoch.captureId); d.writeUTF(i.epoch.runId); d.writeLong(i.epoch.number);
        d.writeInt(i.epoch.format.sampleRateHz); d.writeInt(i.epoch.format.channels);
        d.writeUTF(i.epoch.requestedInput); d.writeUTF(i.epoch.actualInput); d.writeUTF(i.epoch.boundary.name());
        d.writeLong(i.sequence); d.writeLong(i.frameStart); d.writeLong(i.targetFrames);
    }
    private static Intent readIntent(DataInputStream d) throws IOException {
        if (d.readInt() != MAGIC || d.readInt() != VERSION) throw new IOException("unknown metadata schema");
        String capture = d.readUTF(), run = d.readUTF(); long epoch = d.readLong();
        PcmFormat format = new PcmFormat(d.readInt(), d.readInt());
        Epoch e = new Epoch(capture, run, epoch, format, d.readUTF(), d.readUTF(), Gap.valueOf(d.readUTF()));
        return new Intent(e, d.readLong(), d.readLong(), d.readLong());
    }
    private static DataInputStream input(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 32 || bytes.length > MAX_BYTES) throw new IOException("invalid metadata size");
        int payload = bytes.length - 32;
        java.security.MessageDigest digest = PcmSegmentWriter.sha256(); digest.update(bytes, 0, payload);
        if (!java.security.MessageDigest.isEqual(digest.digest(), java.util.Arrays.copyOfRange(bytes, payload, bytes.length)))
            throw new IOException("metadata checksum mismatch");
        return new DataInputStream(new ByteArrayInputStream(bytes, 0, payload));
    }
    private static byte[] seal(byte[] payload) throws IOException {
        if (payload.length > MAX_BYTES - 32) throw new IOException("oversized metadata");
        byte[] result = java.util.Arrays.copyOf(payload, payload.length + 32);
        System.arraycopy(PcmSegmentWriter.sha256().digest(payload), 0, result, payload.length, 32); return result;
    }
    private static void end(DataInputStream d) throws IOException { if (d.read() != -1) throw new IOException("trailing metadata"); }
    public static byte[] encodeIntent(Intent i) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(b);
        writeIntent(d, i); d.flush(); return seal(b.toByteArray());
    }
    public static Intent decodeIntent(byte[] bytes) throws IOException {
        try { DataInputStream d = input(bytes); Intent i = readIntent(d); end(d); return i; }
        catch (IllegalArgumentException e) { throw new IOException("invalid intent", e); }
    }
    public byte[] encode() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(b);
        writeIntent(d, intent); d.writeLong(frameCount); d.writeUTF(pcmSha256); d.writeUTF(wavSha256);
        d.writeUTF(closeReason.name()); d.writeBoolean(trustedPreviousSeam); d.flush();
        byte[] result = seal(b.toByteArray());
        return result;
    }
    public static SegmentMetadata decode(byte[] bytes) throws IOException {
        try {
            DataInputStream d = input(bytes); Intent i = readIntent(d);
            SegmentMetadata m = new SegmentMetadata(i, d.readLong(), d.readUTF(), d.readUTF(),
                Close.valueOf(d.readUTF()), d.readBoolean()); end(d); return m;
        } catch (IllegalArgumentException e) { throw new IOException("invalid manifest", e); }
    }
    static boolean sameIntent(Intent a, Intent b) throws IOException {
        return java.util.Arrays.equals(encodeIntent(a), encodeIntent(b));
    }
}
