package io.github.headmaster218.recorder.core;

import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Bounded 11-second digital silence through production writer/spool/codec; no device or network. */
public final class FtpsSilenceFixtureTest {
    private static int checks;
    private static void yes(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static String hash(byte[] bytes) { return PcmSegmentWriter.hex(PcmSegmentWriter.sha256().digest(bytes)); }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("One fresh output directory is required");
        Path root = Files.createDirectory(Paths.get(args[0]));
        Path spool = Files.createDirectory(root.resolve("spool"));
        String capture = "synthetic-silence-11s-v2", run = "synthetic-run";
        CaptureTimeline.Epoch epoch = new CaptureTimeline.Epoch(capture,run,0,PcmFormat.DEFAULT,
            "synthetic-digital-silence","synthetic-digital-silence",CaptureTimeline.Gap.START);
        byte[] block = new byte[32000]; // One second, 16 kHz mono PCM16. Never capture real audio.
        try (DirectorySpool store = new DirectorySpool(spool,new CachePolicy(2000000,0),new DirectorySpool.NioProtocol(),(op,p)->{})) {
            // Target exceeds supplied frames so explicit Stop, not rotation, closes the sole segment.
            PcmSegmentWriter writer = new PcmSegmentWriter(store,epoch,192000,0,p->{});
            for (int second=0;second<11;second++) {
                long start = second * 16000L, ns = second * 1000000000L + 1;
                CaptureAnchors.Read observation = new CaptureAnchors.Read(
                    "00000000-0000-0000-0000-000000000002",run,second,start,start+16000,
                    ns,ns+1,ns+2,1700000000000L+second*1000L,ns+3,second*1000L,0,0,
                    CaptureAnchors.Timestamp.notPolled(),second==0 ? CaptureAnchors.RUN_START : 0);
                writer.append(block,0,block.length,observation);
            }
            writer.stop();
        }
        CommittedSegments catalog = new CommittedSegments(spool);
        CommittedSegments.Page page = catalog.page(null);
        yes(page.rejected==0); yes(page.entries.size()==1);
        CommittedSegments.Entry entry = page.entries.get(0);
        catalog.verify(entry,()->false);
        SegmentMetadata m = entry.metadata;
        yes(m.schemaVersion==2); yes(m.intent.sequence==0); yes(m.intent.frameStart==0);
        yes(m.intent.epoch.number==0); yes(m.intent.epoch.captureId.equals(capture)); yes(m.intent.epoch.runId.equals(run));
        yes(m.intent.epoch.boundary==CaptureTimeline.Gap.START); yes(m.closeReason==CaptureTimeline.Close.STOP);
        yes(!m.trustedPreviousSeam); yes(m.frameCount==176000); yes(m.pcmBytes==352000); yes(m.wavBytes==352044);
        yes(m.intent.epoch.format.sampleRateHz==16000); yes(m.intent.epoch.format.channels==1);
        yes(m.captureAnchors.readCount==11); yes(m.captureAnchors.queryCount==0);
        yes(m.captureAnchors.runSampleStart==0); yes(m.captureAnchors.runSampleEnd==176000);
        byte[] wav = Files.readAllBytes(spool.resolve(entry.id).resolve("audio.wav"));
        yes(wav.length==352044); yes(Arrays.equals(Arrays.copyOf(wav,44),WavHeader.encode(PcmFormat.DEFAULT,176000)));
        int nonzero = 0; for (int i=44;i<wav.length;i++) nonzero |= wav[i]; yes(nonzero==0);
        yes(hash(wav).equals(m.wavSha256)); yes(hash(Arrays.copyOfRange(wav,44,wav.length)).equals(m.pcmSha256));
        String revision = "synthetic-silence-profile";
        String binding = hash((entry.id+"\n"+m.wavSha256+"\n"+revision).getBytes(StandardCharsets.UTF_8));
        String attempt = "r-"+binding+"-00000000000000000000000000000002";
        FtpsDelivery delivery = FtpsDelivery.encode(entry,revision,attempt);
        byte[] metadata = delivery.metadataBytes(), marker = delivery.markerBytes();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(metadata));
        yes(in.readInt()==FtpsDelivery.MAGIC); yes(in.readUTF().equals(revision)); yes(in.readUTF().equals(entry.id));
        byte[] local = new byte[in.readInt()]; in.readFully(local); yes(in.read()==-1);
        yes(Arrays.equals(local,m.encode())); yes(SegmentMetadata.decode(local).frameCount==176000);
        yes(hash(metadata).equals(delivery.metadataSha256)); yes(hash(marker).equals(delivery.markerSha256));
        Path destination = Files.createDirectory(root.resolve(attempt));
        Files.write(destination.resolve("audio.wav"),wav);
        Files.write(destination.resolve("segment.v1.bin"),metadata);
        Files.write(destination.resolve("delivery.v1.txt"),marker);
        yes(Arrays.equals(Files.readAllBytes(destination.resolve("audio.wav")),wav));
        yes(Arrays.equals(Files.readAllBytes(destination.resolve("segment.v1.bin")),metadata));
        yes(Arrays.equals(Files.readAllBytes(destination.resolve("delivery.v1.txt")),marker));
        yes(Files.exists(spool.resolve(entry.id).resolve("audio.wav")));
        System.out.println("PASS silence producer: "+checks+" assertions; 176000 frames; 352044 WAV bytes; START to STOP; synthetic clock observations only");
        System.out.println("DELIVERY "+destination.toAbsolutePath());
        System.out.println("WAV_SHA256 "+m.wavSha256);
    }
}
