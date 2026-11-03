package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Abrupt child-JVM exits on the host filesystem, not Android or power-loss evidence. */
public final class StagedProcessDeathTest {
    private static final int HALTED = 73;
    private static int checks;
    private static String currentCase;
    private interface Action { void run() throws Exception; }
    private static void yes(boolean value) { checks++; if (!value) throw new AssertionError(currentCase + " check " + checks); }
    private static void rejects(Action action) throws Exception {
        checks++;
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("expected rejection " + checks);
    }
    private static CaptureTimeline.Epoch epoch() {
        return new CaptureTimeline.Epoch("process-death", "child-run", 0, PcmFormat.DEFAULT,
            "synthetic", "synthetic", CaptureTimeline.Gap.START);
    }
    private static byte[] pcm(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 31 + 7);
        return bytes;
    }
    private static DirectorySpool open(Path root, DirectorySpool.Faults faults) throws IOException {
        return new DirectorySpool(root, new CachePolicy(2000000, 0), new DirectorySpool.NioProtocol(), faults);
    }
    private static void child(Path root, String point, int bytes) throws Exception {
        final boolean[] closing = { false };
        // Deliberately no try-with-resources: halt must bypass preserve(), close() and finally.
        DirectorySpool store = open(root, (op, path) -> {
            if (closing[0] && op.name().equals(point)) Runtime.getRuntime().halt(HALTED);
        });
        PcmSegmentWriter writer = PcmSegmentWriter.terminalAware(store, epoch(), 4, 0,
            published -> { throw new AssertionError("unexpected listener before halt"); });
        writer.append(pcm(bytes), 0, bytes);
        if (point.equals("AFTER_APPEND")) Runtime.getRuntime().halt(HALTED);
        closing[0] = true;
        writer.stop();
        throw new AssertionError("halt point not reached");
    }
    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> result = new TreeMap<String, byte[]>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator)
                result.put(root.relativize(p).toString(), Files.readAllBytes(p));
        }
        return result;
    }
    private static void same(Map<String, byte[]> before, Map<String, byte[]> after) {
        yes(before.keySet().equals(after.keySet()));
        for (String key : before.keySet()) yes(Arrays.equals(before.get(key), after.get(key)));
    }
    private static void scenario(Path base, String point, int bytes) throws Exception {
        currentCase = point + " bytes=" + bytes;
        Path root = base.resolve(point + "-" + bytes);
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-Xmx32m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
            "-cp", System.getProperty("java.class.path"), StagedProcessDeathTest.class.getName(),
            "child", root.toString(), point, Integer.toString(bytes)).inheritIO().start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly(); process.waitFor(); throw new AssertionError("child timeout: " + point);
        }
        yes(process.exitValue() == HALTED);
        Map<String, byte[]> before = snapshot(root);
        try (DirectorySpool store = open(root, (op, path) -> {})) {
            List<DirectorySpool.Recovery> reports = new ArrayList<DirectorySpool.Recovery>();
            store.scan(reports::add);
            yes(reports.size() == 1);
            DirectorySpool.Recovery report = reports.get(0);
            boolean ready = point.equals("SYNC_ROOT") || point.equals("VALIDATE");
            yes(report.state == (ready ? DirectorySpool.RecoveryState.FINALIZED_UNCONFIRMED
                : DirectorySpool.RecoveryState.PRESERVED_PARTIAL));
            yes(report.observedWholeFrames == bytes / 2);
            yes(report.trailingBytes == bytes % 2);
            yes(store.nextSequence("process-death") == 1);
            rejects(() -> store.open(new SegmentMetadata.Intent(epoch(), 0, 0, 4)));
            byte[] wav = Files.readAllBytes(root.resolve(report.localObjectId).resolve("audio.wav"));
            yes(wav.length == WavHeader.BYTES + bytes);
            yes(Arrays.equals(Arrays.copyOfRange(wav, WavHeader.BYTES, wav.length), pcm(bytes)));
            boolean placeholder = point.equals("AFTER_APPEND") || point.equals("PATCH_HEADER");
            yes(Arrays.equals(Arrays.copyOf(wav, WavHeader.BYTES),
                WavHeader.encode(PcmFormat.DEFAULT, placeholder ? 0 : bytes / 2)));
            boolean hasManifest = point.equals("SYNC_MANIFEST") || point.equals("SYNC_STAGE")
                || point.equals("PUBLISH") || ready;
            yes(Files.exists(root.resolve(report.localObjectId).resolve("manifest.bin")) == hasManifest);
            CommittedSegments.Page page = new CommittedSegments(root).page(null);
            yes(page.entries.size() == (ready ? 1 : 0));
            if (ready) {
                SegmentStore.Published recovered = store.confirmReady(report.localObjectId);
                yes(recovered.metadata.closeReason == CaptureTimeline.Close.STOP);
                yes(recovered.metadata.frameCount == bytes / 2);
            } else {
                rejects(() -> store.confirmReady(report.localObjectId));
                rejects(() -> store.maintenance(report.localObjectId).close());
                rejects(() -> store.enrollment(report.localObjectId).close());
            }
        }
        same(before, snapshot(root));
        System.out.println("PASS abrupt process exit: " + point + " bytes=" + bytes);
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            child(Paths.get(args[1]), args[2], Integer.parseInt(args[3])); return;
        }
        Path base = Files.createDirectories(Paths.get("app/build/staged-process-death-tests/run-" + System.nanoTime()));
        for (int bytes : new int[] { 1, 6, 8 }) scenario(base, "AFTER_APPEND", bytes);
        for (int bytes : new int[] { 6, 8 })
            for (String point : new String[] { "PATCH_HEADER", "SYNC_FINAL_WAV", "HASH_WAV", "WRITE_MANIFEST",
                    "SYNC_MANIFEST", "SYNC_STAGE", "PUBLISH", "SYNC_ROOT", "VALIDATE" }) scenario(base, point, bytes);
        System.out.println("PASS staged process death: " + checks + " assertions, 21 child exits; no power-loss/device claims");
    }
}
