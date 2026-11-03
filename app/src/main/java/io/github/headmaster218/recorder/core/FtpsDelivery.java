package io.github.headmaster218.recorder.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Producer-owned V1 wire envelope. A delivery marker is not immutable server acceptance. */
public final class FtpsDelivery {
    public static final int MAGIC = 0x52465431;
    public static final int MAX_METADATA_BYTES = SegmentMetadata.MAX_BYTES + 512;
    public static final int MAX_MARKER_BYTES = 1024;
    private final byte[] metadata, marker;
    public final String metadataSha256, markerSha256;

    private FtpsDelivery(byte[] metadata, byte[] marker) {
        this.metadata = metadata; this.marker = marker;
        metadataSha256 = hash(metadata); markerSha256 = hash(marker);
    }
    public byte[] metadataBytes() { return metadata.clone(); }
    public byte[] markerBytes() { return marker.clone(); }

    public static FtpsDelivery encode(CommittedSegments.Entry entry, String revision, String attempt) throws IOException {
        if (entry == null || revision == null || !revision.matches("[A-Za-z0-9-]{1,128}"))
            throw new IllegalArgumentException("Invalid delivery source or destination revision");
        String binding = hash((entry.id + "\n" + entry.metadata.wavSha256 + "\n" + revision).getBytes(StandardCharsets.UTF_8));
        if (attempt == null || !attempt.matches("r-" + binding + "-[0-9a-f]{32}"))
            throw new IllegalArgumentException("Delivery attempt is not bound to source and revision");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeUTF(revision); out.writeUTF(entry.id);
        byte[] local = entry.metadata.encode(); out.writeInt(local.length); out.write(local); out.flush();
        if (bytes.size() > MAX_METADATA_BYTES) throw new IOException("Transport metadata exceeds bound");
        byte[] metadata = bytes.toByteArray();
        byte[] marker = ("RECORDER-DELIVERY-V1\n" + attempt + "\n" + revision + "\n" + entry.id + "\n"
            + entry.metadata.wavBytes + "\n" + entry.metadata.wavSha256 + "\n" + metadata.length + "\n"
            + hash(metadata) + "\n").getBytes(StandardCharsets.US_ASCII);
        if (marker.length > MAX_MARKER_BYTES) throw new IOException("Delivery marker exceeds bound");
        return new FtpsDelivery(metadata, marker);
    }
    private static String hash(byte[] bytes) {
        return PcmSegmentWriter.hex(PcmSegmentWriter.sha256().digest(bytes));
    }
}
