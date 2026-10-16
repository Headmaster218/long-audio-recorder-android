package io.github.headmaster218.recorder.core;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
public final class AutomaticEnrollmentTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void yes(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void fails(Action action) throws Exception { checks++; try { action.run(); } catch (IOException e) { return; } throw new AssertionError("expected failure"); }
    public static void main(String[] args) throws Exception {
        Path parent = Files.createTempDirectory(Paths.get("app/build"),"auto-synthetic-");
        Path root = parent.resolve("spool"); List<SegmentStore.Published> published = new ArrayList<>();
        CachePolicy cache = new CachePolicy(1024*1024,0);
        try (DirectorySpool spool = new DirectorySpool(root,cache,new DirectorySpool.NioProtocol(),(op,path) -> {})) {
            CaptureTimeline.Epoch epoch = new CaptureTimeline.Epoch("synthetic-auto","run",0,new PcmFormat(16000,1),"mic","mic",CaptureTimeline.Gap.START);
            PcmSegmentWriter writer = new PcmSegmentWriter(spool,epoch,4,0,published::add);
            writer.append(new byte[16],0,16); writer.stop();
            yes(published.size() == 2);
            for (SegmentStore.Published p : published) {
                yes(Arrays.equals(spool.confirmReady(p.localObjectId).metadata.encode(),p.metadata.encode()));
                fails(() -> spool.confirmReady(p.localObjectId,() -> true));
                // Cancellation during content hashing retains the ready WAV and metadata.
                final int[] calls = {0}; fails(() -> spool.confirmReady(p.localObjectId,() -> ++calls[0] >= 3));
                yes(Files.exists(root.resolve(p.localObjectId).resolve("audio.wav")));
            }
            // A second owner cannot steal the spool while capture owns it.
            fails(() -> { try (DirectorySpool other = new DirectorySpool(root,cache,new DirectorySpool.NioProtocol(),(op,path) -> {})) { other.nextSequence("synthetic"); } });
        }
        ArrayDeque<String> discovered = FinalizedDiscovery.candidates(root,id -> false,20,10);
        yes(discovered.size() == 2);
        Set<String> durableRows = new HashSet<>(discovered);
        yes(FinalizedDiscovery.candidates(root,durableRows::contains,20,10).isEmpty());
        fails(() -> FinalizedDiscovery.candidates(root,id -> false,1,10));
        fails(() -> FinalizedDiscovery.candidates(root,id -> false,20,1));
        Files.createDirectory(root.resolve("arbitrary.part")); Files.createDirectory(root.resolve("wrong.ready"));
        yes(FinalizedDiscovery.candidates(root,id -> false,20,10).size() == 2);
        Path alias = parent.resolve("alias"); Files.createSymbolicLink(alias,root);
        fails(() -> FinalizedDiscovery.candidates(alias,id -> false,20,10));
        String id = published.get(0).localObjectId; Path wav = root.resolve(id).resolve("audio.wav");
        byte[] original = Files.readAllBytes(wav), corrupt = original.clone(); corrupt[corrupt.length-1] ^= 1;
        Files.write(wav,corrupt);
        try (DirectorySpool reopened = new DirectorySpool(root,cache,new DirectorySpool.NioProtocol(),(op,path) -> {})) {
            fails(() -> reopened.confirmReady(id));
            fails(() -> reopened.confirmReady("arbitrary.part"));
            yes(Arrays.equals(Files.readAllBytes(wav),corrupt));
            Files.write(wav,original);
            yes(reopened.confirmReady(id).localObjectId.equals(id));
            yes(reopened.confirmReady(id).localObjectId.equals(id));
        }
        yes(FinalizedDiscovery.candidates(parent.resolve("absent"),id2 -> false,20,10).isEmpty());
        System.out.println("PASS: " + checks + " automatic discovery/confirmation assertions; synthetic host filesystem only");
    }
}
