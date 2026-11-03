package io.github.headmaster218.recorder.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Same-epoch 4+4+3 second synthetic producer delivery; no device, network or physical continuity claim. */
public final class FtpsSegmentedSilenceFixtureTest {
    private static int checks;
    private static void yes(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static String hash(byte[] bytes) { return PcmSegmentWriter.hex(PcmSegmentWriter.sha256().digest(bytes)); }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("One fresh output directory is required");
        Path root = Files.createDirectory(Paths.get(args[0]));
        Path spool = Files.createDirectory(root.resolve("spool"));
        String capture = "synthetic-silence-11s-segmented-v2", run = "synthetic-run";
        CaptureTimeline.Epoch epoch = new CaptureTimeline.Epoch(capture,run,0,PcmFormat.DEFAULT,
            "synthetic-digital-silence","synthetic-digital-silence",CaptureTimeline.Gap.START);
        byte[] block = new byte[32000];
        try (DirectorySpool store = new DirectorySpool(spool,new CachePolicy(2000000,0),new DirectorySpool.NioProtocol(),(op,p)->{})) {
            PcmSegmentWriter writer = new PcmSegmentWriter(store,epoch,64000,0,p->{});
            for (int second=0;second<11;second++) {
                long start = second * 16000L, ns = second * 1000000000L + 1;
                CaptureAnchors.Read observation = new CaptureAnchors.Read(
                    "00000000-0000-0000-0000-000000000003",run,second,start,start+16000,
                    ns,ns+1,ns+2,1700000000000L+second*1000L,ns+3,second*1000L,0,0,
                    CaptureAnchors.Timestamp.notPolled(),second==0 ? CaptureAnchors.RUN_START : 0);
                writer.append(block,0,block.length,observation);
            }
            writer.stop();
        }
        CommittedSegments catalog = new CommittedSegments(spool);
        CommittedSegments.Page page = catalog.page(null);
        yes(page.rejected==0); yes(page.entries.size()==3); yes(!page.hasMore);
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        long totalFrames = 0;
        for (int sequence=0;sequence<3;sequence++) {
            CommittedSegments.Entry entry = page.entries.get(sequence);
            catalog.verify(entry,()->false);
            SegmentMetadata m = entry.metadata;
            long expectedFrames = sequence==2 ? 48000 : 64000;
            long start = sequence * 64000L;
            yes(m.schemaVersion==2); yes(m.intent.sequence==sequence); yes(m.intent.frameStart==start);
            yes(m.intent.targetFrames==64000); yes(m.intent.epoch.number==0);
            yes(m.intent.epoch.captureId.equals(capture)); yes(m.intent.epoch.runId.equals(run));
            yes(m.intent.epoch.boundary==CaptureTimeline.Gap.START);
            yes(m.closeReason==(sequence==2 ? CaptureTimeline.Close.STOP : CaptureTimeline.Close.ROTATION));
            yes(m.trustedPreviousSeam==(sequence>0)); yes(m.frameCount==expectedFrames);
            yes(m.pcmBytes==expectedFrames*2); yes(m.wavBytes==expectedFrames*2+44);
            yes(m.intent.epoch.format.sampleRateHz==16000); yes(m.intent.epoch.format.channels==1);
            yes(m.captureAnchors.readCount==expectedFrames/16000); yes(m.captureAnchors.queryCount==0);
            yes(m.captureAnchors.runSampleStart==start); yes(m.captureAnchors.runSampleEnd==start+expectedFrames);
            yes(m.captureAnchors.firstRead.ordinal==start/16000);
            yes(m.captureAnchors.lastRead.ordinal==(start+expectedFrames)/16000-1);
            if (sequence>0) {
                SegmentMetadata previous = page.entries.get(sequence-1).metadata;
                yes(previous.intent.frameStart+previous.frameCount==m.intent.frameStart);
                yes(previous.captureAnchors.runSampleEnd==m.captureAnchors.runSampleStart);
                yes(previous.captureAnchors.lastRead.ordinal+1==m.captureAnchors.firstRead.ordinal);
            }
            byte[] wav = Files.readAllBytes(spool.resolve(entry.id).resolve("audio.wav"));
            yes(wav.length==expectedFrames*2+44);
            yes(Arrays.equals(Arrays.copyOf(wav,44),WavHeader.encode(PcmFormat.DEFAULT,expectedFrames)));
            int nonzero = 0; for (int i=44;i<wav.length;i++) nonzero |= wav[i]; yes(nonzero==0);
            yes(hash(wav).equals(m.wavSha256)); yes(hash(Arrays.copyOfRange(wav,44,wav.length)).equals(m.pcmSha256));
            combined.write(wav,44,wav.length-44); totalFrames+=m.frameCount;
            String revision = "synthetic-segmented-silence-profile";
            String binding = hash((entry.id+"\n"+m.wavSha256+"\n"+revision).getBytes(StandardCharsets.UTF_8));
            String attempt = "r-"+binding+"-00000000000000000000000000000003";
            FtpsDelivery delivery = FtpsDelivery.encode(entry,revision,attempt);
            byte[] metadata = delivery.metadataBytes(), marker = delivery.markerBytes();
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(metadata));
            yes(in.readInt()==FtpsDelivery.MAGIC); yes(in.readUTF().equals(revision)); yes(in.readUTF().equals(entry.id));
            byte[] local = new byte[in.readInt()]; in.readFully(local); yes(in.read()==-1);
            yes(Arrays.equals(local,m.encode())); yes(SegmentMetadata.decode(local).frameCount==expectedFrames);
            yes(hash(metadata).equals(delivery.metadataSha256)); yes(hash(marker).equals(delivery.markerSha256));
            Path destination = Files.createDirectory(root.resolve(attempt));
            Files.write(destination.resolve("audio.wav"),wav);
            Files.write(destination.resolve("segment.v1.bin"),metadata);
            Files.write(destination.resolve("delivery.v1.txt"),marker);
            yes(Arrays.equals(Files.readAllBytes(destination.resolve("audio.wav")),wav));
            yes(Arrays.equals(Files.readAllBytes(destination.resolve("segment.v1.bin")),metadata));
            yes(Arrays.equals(Files.readAllBytes(destination.resolve("delivery.v1.txt")),marker));
            yes(Files.exists(spool.resolve(entry.id).resolve("audio.wav")));
            System.out.println("DELIVERY "+sequence+" "+destination.toAbsolutePath());
        }
        yes(totalFrames==176000); yes(combined.size()==352000);
        yes(Arrays.equals(combined.toByteArray(),new byte[352000]));
        System.out.println("PASS segmented silence producer: "+checks+" assertions; 3 deliveries; 64000+64000+48000 frames; synthetic declared adjacency only");
        System.out.println("COMBINED_PCM_SHA256 "+hash(combined.toByteArray()));
    }
}
