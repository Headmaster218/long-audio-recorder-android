package io.github.headmaster218.recorder.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Synthetic producer fixtures, never microphone input or a network operation. */
public final class FtpsDeliveryTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void yes(boolean result) { checks++; if (!result) throw new AssertionError("check " + checks); }
    private static void fails(Action action) throws Exception {
        checks++; try { action.run(); } catch (IllegalArgumentException | IOException expected) { return; }
        throw new AssertionError("expected rejection " + checks);
    }
    private static String hash(byte[] bytes) { return PcmSegmentWriter.hex(PcmSegmentWriter.sha256().digest(bytes)); }
    private static void fixture(Path root, int version, boolean unicode, int channels) throws Exception {
        Path spool = Files.createDirectory(root.resolve("spool"));
        String capture = unicode ? "synthetic-\u4e2d\u6587-\ud83c\udfb5" : "synthetic-delivery-v" + version;
        CaptureTimeline.Epoch epoch = new CaptureTimeline.Epoch(capture,"run",0,
            new PcmFormat(channels == 1 ? 16000 : 48000,channels),"phone","phone",CaptureTimeline.Gap.START);
        try (DirectorySpool store = new DirectorySpool(spool,new CachePolicy(1000000,0),new DirectorySpool.NioProtocol(),(op,p)->{})) {
            PcmSegmentWriter writer = new PcmSegmentWriter(store,epoch,4,0,p->{});
            // Three frames yield a short STOP tail, not a fabricated full segment.
            byte[] pcm = new byte[3 * channels * 2]; for (int i=0;i<pcm.length;i++) pcm[i]=(byte)(i*19);
            CaptureAnchors.Read observation = version == 1 ? null : new CaptureAnchors.Read(
                "00000000-0000-0000-0000-000000000001","run",0,0,3*channels,1,2,3,4,5,6,0,0,
                new CaptureAnchors.Timestamp(CaptureAnchors.TimestampStatus.AVAILABLE,0,4294967301L,1,1,2),CaptureAnchors.RUN_START);
            writer.append(pcm,0,pcm.length,observation); writer.stop();
        }
        CommittedSegments.Entry entry = new CommittedSegments(spool).page(null).entries.get(0);
        String revision = "synthetic-profile-1";
        String binding = hash((entry.id+"\n"+entry.metadata.wavSha256+"\n"+revision).getBytes(StandardCharsets.UTF_8));
        String attempt = "r-"+binding+"-00000000000000000000000000000001";
        FtpsDelivery delivery = FtpsDelivery.encode(entry,revision,attempt);
        // Independent legacy encoder: byte compatibility with the original inline FTPS format.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        byte[] local = entry.metadata.encode();
        out.writeInt(0x52465431); out.writeUTF(revision); out.writeUTF(entry.id); out.writeInt(local.length); out.write(local); out.flush();
        byte[] legacy = bytes.toByteArray();
        yes(Arrays.equals(legacy,delivery.metadataBytes()));
        byte[] marker = ("RECORDER-DELIVERY-V1\n"+attempt+"\n"+revision+"\n"+entry.id+"\n"+entry.metadata.wavBytes+"\n"
            +entry.metadata.wavSha256+"\n"+legacy.length+"\n"+hash(legacy)+"\n").getBytes(StandardCharsets.US_ASCII);
        yes(Arrays.equals(marker,delivery.markerBytes()));
        yes(hash(legacy).equals(delivery.metadataSha256)); yes(hash(marker).equals(delivery.markerSha256));
        yes(entry.metadata.schemaVersion == version); yes(entry.metadata.frameCount == 3);
        yes(entry.metadata.closeReason == CaptureTimeline.Close.STOP); yes(!entry.metadata.trustedPreviousSeam);
        byte[] copy = delivery.metadataBytes(); copy[0] ^= 1; yes(Arrays.equals(legacy,delivery.metadataBytes()));
        copy = delivery.markerBytes(); copy[0] ^= 1; yes(Arrays.equals(marker,delivery.markerBytes()));
        fails(()->FtpsDelivery.encode(entry,"bad\nrevision",attempt));
        fails(()->FtpsDelivery.encode(entry,revision,attempt+"0"));
        fails(()->FtpsDelivery.encode(entry,"other-revision",attempt));
        fails(()->FtpsDelivery.encode(null,revision,attempt));
        fails(()->FtpsDelivery.encode(entry,null,attempt));
        fails(()->FtpsDelivery.encode(entry,revision,null));
        char[] revisionChars = new char[128]; Arrays.fill(revisionChars,'r');
        String maxRevision = new String(revisionChars);
        String maxAttempt = FtpsTransfer.newAttemptName(entry.id,entry.metadata.wavSha256,maxRevision);
        yes(FtpsDelivery.encode(entry,maxRevision,maxAttempt).markerBytes().length <= FtpsDelivery.MAX_MARKER_BYTES);
        fails(()->FtpsDelivery.encode(entry,maxRevision+"r",maxAttempt));
        Path dest = Files.createDirectory(root.resolve(attempt));
        Files.copy(spool.resolve(entry.id).resolve("audio.wav"),dest.resolve("audio.wav"));
        Files.write(dest.resolve("segment.v1.bin"),delivery.metadataBytes());
        Files.write(dest.resolve("delivery.v1.txt"),delivery.markerBytes());
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("One fresh output directory is required");
        Path root = Files.createDirectory(Paths.get(args[0]));
        fixture(Files.createDirectory(root.resolve("v1-mono")),1,false,1);
        fixture(Files.createDirectory(root.resolve("v2-mono")),2,false,1);
        fixture(Files.createDirectory(root.resolve("v2-unicode-stereo")),2,true,2);
        System.out.println("PASS delivery codec: "+checks+" assertions; 3 synthetic producer fixtures; no device/network claims");
    }
}
