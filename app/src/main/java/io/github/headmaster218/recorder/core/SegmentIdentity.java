package io.github.headmaster218.recorder.core;

import java.io.IOException;
/** Canonical, content-independent recording ordinal identity; never verification evidence. */
public final class SegmentIdentity {
    public final String capture, source, stem;
    public final long sequence;
    private SegmentIdentity(String source,String capture,long sequence) { this.source=source;this.capture=capture;this.sequence=sequence;stem=source.substring(0,source.length()-6); }
    public static SegmentIdentity parse(String source)throws IOException {
        if(source==null || !source.matches("segment-[0-9a-f]{64}-(0|[1-9][0-9]*)\\.ready")) throw new IOException("noncanonical source identity");
        try { long value=Long.parseLong(source.substring(73,source.length()-6));if(value==Long.MAX_VALUE)throw new IOException("sequence exhausted");return new SegmentIdentity(source,source.substring(8,72),value); }
        catch(NumberFormatException e){throw new IOException("source ordinal overflow",e);}
    }
    public static String capture(String captureId) { return PcmSegmentWriter.hex(PcmSegmentWriter.sha256().digest(captureId.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
}
