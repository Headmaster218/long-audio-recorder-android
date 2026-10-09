package io.github.headmaster218.recorder.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Canonical 44-byte RIFF/WAVE header; PCM16 little-endian only, no RF64. */
public final class WavHeader {
    public static final int BYTES = 44;
    private WavHeader() { }
    public static byte[] encode(PcmFormat format, long frames) {
        if (format == null) throw new IllegalArgumentException("format");
        long dataBytes = format.bytesForFrames(frames);
        long byteRate = format.bytesForFrames(format.sampleRateHz);
        if (dataBytes > 0xffffffffL - 36 || byteRate > 0xffffffffL)
            throw new IllegalArgumentException("RIFF/PCM byte-rate limit");
        ByteBuffer b = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'R','I','F','F'}).putInt((int) (36 + dataBytes));
        b.put(new byte[] {'W','A','V','E','f','m','t',' '}).putInt(16);
        b.putShort((short) 1).putShort((short) format.channels).putInt(format.sampleRateHz);
        b.putInt((int) byteRate).putShort((short) (format.channels * 2)).putShort((short) 16);
        b.put(new byte[] {'d','a','t','a'}).putInt((int) dataBytes);
        return b.array();
    }
}
