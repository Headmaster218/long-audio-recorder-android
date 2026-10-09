package io.github.headmaster218.recorder.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import io.github.headmaster218.recorder.core.CaptureTimeline.Epoch;
import io.github.headmaster218.recorder.core.CaptureTimeline.Gap;
import io.github.headmaster218.recorder.core.DirectorySpool.Operation;
import io.github.headmaster218.recorder.core.DirectorySpool.Recovery;
import io.github.headmaster218.recorder.core.DirectorySpool.RecoveryState;
import io.github.headmaster218.recorder.core.SegmentStore.Published;

/** Tiny generated fixtures, no downloaded/real recordings. Each run preserves its test files. */
public final class StorageTest {
    private static int checks, caseNumber;
    private static final Path BASE = Paths.get("app/build/storage-tests/fixtures/run-" + System.nanoTime());
    private interface Action { void run() throws Exception; }
    private static void yes(boolean b) { checks++; if (!b) throw new AssertionError("check " + checks); }
    private static void eq(long a, long b) { checks++; if (a != b) throw new AssertionError(a + " != " + b + " at " + checks); }
    private static void fails(Action a) throws Exception { checks++; try { a.run(); } catch (IOException | IllegalArgumentException | IllegalStateException e) { return; } throw new AssertionError("expected rejection " + checks); }
    private static Path root() throws IOException { return Files.createDirectories(BASE.resolve("case-" + caseNumber++)); }
    private static Epoch epoch(int channels, long number, String actual) { return new Epoch("capture", "run", number, new PcmFormat(16000, channels), "phone", actual, number == 0 ? Gap.START : Gap.ROUTE_CHANGE_UNCERTAIN); }
    private static DirectorySpool open(Path root) throws IOException { return open(root, 1000000, (op, path) -> { }); }
    private static DirectorySpool open(Path root, long maximum, DirectorySpool.Faults faults) throws IOException {
        return new DirectorySpool(root, new CachePolicy(maximum, 0), new DirectorySpool.NioProtocol(), faults);
    }
    private static List<Recovery> scan(DirectorySpool s) throws IOException { List<Recovery> r = new ArrayList<Recovery>(); s.scan(r::add); return r; }
    private static void headers() throws Exception {
        byte[] h = WavHeader.encode(PcmFormat.DEFAULT, 16000);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        eq(44, h.length); eq(32036, b.getInt(4)); eq(1, b.getShort(20)); eq(1, b.getShort(22));
        eq(16000, b.getInt(24)); eq(32000, b.getInt(28)); eq(2, b.getShort(32)); eq(16, b.getShort(34)); eq(32000, b.getInt(40));
        eq(36, ByteBuffer.wrap(WavHeader.encode(PcmFormat.DEFAULT, 0)).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
        fails(() -> WavHeader.encode(PcmFormat.DEFAULT, -1)); fails(() -> WavHeader.encode(PcmFormat.DEFAULT, Long.MAX_VALUE));
        fails(() -> WavHeader.encode(new PcmFormat(Integer.MAX_VALUE, 32), 1));
        long maxFrames = (0xffffffffL - 36) / 2;
        eq(44, WavHeader.encode(PcmFormat.DEFAULT, maxFrames).length);
        fails(() -> WavHeader.encode(PcmFormat.DEFAULT, maxFrames + 1));
    }
    private static void arbitraryBuffers() throws Exception {
        Path root = root(); List<Published> out = new ArrayList<Published>();
        byte[] input = new byte[404]; for (int i = 0; i < input.length; i++) input[i] = (byte) (i * 37);
        try (DirectorySpool spool = open(root)) {
            PcmSegmentWriter writer = new PcmSegmentWriter(spool, epoch(2, 0, "phone"), 7, 0, out::add);
            writer.append(input, 0, 0); eq(0, scan(spool).size());
            fails(() -> writer.append(input, Integer.MAX_VALUE, Integer.MAX_VALUE));
            fails(() -> new PcmSegmentWriter(spool, epoch(2, 0, "phone"), 0, 0, out::add));
            fails(() -> writer.append(input, -1, 1)); fails(() -> writer.append(input, 0, -1));
            int offset = 0; int[] sizes = {1, 2, 17, 3, 59, 4, 41}; int i = 0;
            while (offset < input.length) { int n = Math.min(sizes[i++ % sizes.length], input.length - offset); writer.append(input, offset, n); offset += n; }
            writer.stop(); eq(15, out.size()); fails(() -> writer.append(input, 0, 1));
            ByteArrayOutputStream assembled = new ByteArrayOutputStream(); long frame = 0;
            for (int index = 0; index < out.size(); index++) {
                Published published = out.get(index); SegmentMetadata m = published.metadata;
                eq(index, m.intent.sequence); eq(frame, m.intent.frameStart); yes(m.trustedPreviousSeam == (index != 0));
                eq(index == 14 ? 3 : 7, m.frameCount); frame += m.frameCount;
                byte[] wav = Files.readAllBytes(root.resolve(published.localObjectId).resolve("audio.wav"));
                eq(wav.length, m.wavBytes); eq(m.pcmBytes + 44, m.wavBytes);
                ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
                eq(wav.length - 8, header.getInt(4)); eq(wav.length - 44, header.getInt(40)); eq(2, header.getShort(22));
                assembled.write(wav, 44, wav.length - 44);
                yes(PcmSegmentWriter.hex(PcmSegmentWriter.sha256().digest(wav)).equals(m.wavSha256));
                Published confirmed = spool.confirmReady(published.localObjectId); yes(confirmed.metadata.wavSha256.equals(m.wavSha256));
            }
            eq(101, frame); yes(Arrays.equals(input, assembled.toByteArray()));
            for (Recovery r : scan(spool)) yes(r.state == RecoveryState.FINALIZED_UNCONFIRMED);
            fails(() -> new PcmSegmentWriter(spool, epoch(2, 0, "phone"), 7, 0, out::add).append(input, 0, 4));
            eq(15, out.size()); eq(15, spool.nextSequence("capture"));
        }
    }
    private static void epochTransitions() throws Exception {
        Path root = root(); List<Published> out = new ArrayList<Published>();
        try (DirectorySpool spool = open(root)) {
            PcmSegmentWriter w = new PcmSegmentWriter(spool, epoch(1, 0, "phone"), 10, 12, out::add);
            w.append(new byte[12], 0, 12); fails(() -> w.beginEpoch(epoch(1, 0, "headset")));
            w.beginEpoch(epoch(1, 1, "headset")); w.append(new byte[24], 0, 24); w.stop();
            eq(3, out.size()); eq(6, out.get(0).metadata.frameCount); eq(12, out.get(0).metadata.intent.sequence);
            eq(0, out.get(1).metadata.intent.frameStart); yes(!out.get(1).metadata.trustedPreviousSeam);
            eq(10, out.get(2).metadata.intent.frameStart); yes(out.get(2).metadata.trustedPreviousSeam);
            yes(out.get(1).metadata.intent.epoch.actualInput.equals("headset"));
        }
    }
    private static void partialRecovery() throws Exception {
        Path root = root(); List<Published> out = new ArrayList<Published>(); String id;
        try (DirectorySpool spool = open(root)) {
            PcmSegmentWriter w = new PcmSegmentWriter(spool, epoch(2, 0, "phone"), 10, 0, out::add);
            w.append(new byte[] {1, 2, 3, 4, 5, 6, 7}, 0, 7); fails(w::stop); eq(0, out.size());
            List<Recovery> reports = scan(spool); eq(1, reports.size()); Recovery r = reports.get(0); id = r.localObjectId;
            yes(r.state == RecoveryState.PRESERVED_PARTIAL); eq(1, r.observedWholeFrames); eq(3, r.trailingBytes);
            eq(51, Files.size(root.resolve(id).resolve("audio.wav"))); fails(() -> spool.confirmReady(r.localObjectId));
            fails(() -> w.append(new byte[1], 0, 1));
        }
        try (DirectorySpool reopened = open(root)) { yes(scan(reopened).get(0).state == RecoveryState.PRESERVED_PARTIAL); eq(1, reopened.nextSequence("capture")); }
        byte[] wave = Files.readAllBytes(root.resolve(id).resolve("audio.wav")); wave[0] = 0;
        Files.write(root.resolve(id).resolve("audio.wav"), wave, StandardOpenOption.TRUNCATE_EXISTING);
        try (DirectorySpool reopened = open(root)) { yes(scan(reopened).get(0).state == RecoveryState.CORRUPT_PRESERVED); }
        eq(51, Files.size(root.resolve(id).resolve("audio.wav")));
    }
    private static void boundedCache() throws Exception {
        Path root = root(); List<Published> out = new ArrayList<Published>();
        SegmentMetadata.Intent intent = new SegmentMetadata.Intent(epoch(1, 0, "phone"), 0, 0, 100);
        long limit = SegmentMetadata.encodeIntent(intent).length + 44 + DirectorySpool.FINALIZATION_RESERVE + 4;
        try (DirectorySpool spool = open(root, limit, (op, path) -> { })) {
            PcmSegmentWriter w = new PcmSegmentWriter(spool, intent.epoch, 100, 0, out::add);
            w.append(new byte[] {9, 8, 7, 6}, 0, 4);
            try { w.append(new byte[2], 0, 2); throw new AssertionError("expected cache stop"); }
            catch (DirectorySpool.StorageException e) { yes(e.failure == DirectorySpool.Failure.CACHE_FULL); }
            eq(0, out.size()); List<Recovery> reports = scan(spool); eq(1, reports.size());
            Path wav = root.resolve(reports.get(0).localObjectId).resolve("audio.wav");
            eq(48, Files.size(wav)); eq(9, Files.readAllBytes(wav)[44]);
        }
        try (DirectorySpool spool = open(root(), 1, (op, path) -> { })) {
            PcmSegmentWriter w = new PcmSegmentWriter(spool, epoch(1, 0, "phone"), 2, 0, out::add);
            fails(() -> w.append(new byte[2], 0, 2)); eq(0, scan(spool).size());
        }
    }
    private static void publicationFaults() throws Exception {
        for (Operation failure : Operation.values()) {
            Path root = root(); List<Published> out = new ArrayList<Published>(); List<Operation> events = new ArrayList<Operation>();
            boolean[] armed = {false}, fired = {false};
            DirectorySpool.Faults faults = (op, path) -> {
                events.add(op);
                if (op == Operation.APPEND_PCM) armed[0] = true;
                boolean publicationOnly = op == Operation.SYNC_STAGE || op == Operation.SYNC_ROOT;
                if (op == failure && !fired[0] && (!publicationOnly || armed[0])) { fired[0] = true; throw new IOException("injected " + op); }
            };
            try (DirectorySpool spool = open(root, 1000000, faults)) {
                PcmSegmentWriter w = new PcmSegmentWriter(spool, epoch(1, 0, "phone"), 2, 0, out::add);
                fails(() -> w.append(new byte[] {1, 2, 3, 4}, 0, 4)); yes(fired[0]); eq(0, out.size());
                fails(() -> w.append(new byte[2], 0, 2));
            }
            try (DirectorySpool reopened = open(root)) {
                List<Recovery> reports = scan(reopened);
                if (failure != Operation.CREATE_STAGE) yes(!reports.isEmpty());
                for (Recovery r : reports) {
                    if (r.state == RecoveryState.FINALIZED_UNCONFIRMED) yes(reopened.confirmReady(r.localObjectId).metadata.frameCount == 2);
                    else fails(() -> reopened.confirmReady(r.localObjectId));
                }
            }
            if (events.contains(Operation.PUBLISH)) {
                yes(events.indexOf(Operation.PATCH_HEADER) < events.indexOf(Operation.SYNC_FINAL_WAV));
                yes(events.indexOf(Operation.SYNC_FINAL_WAV) < events.indexOf(Operation.WRITE_MANIFEST));
                yes(events.indexOf(Operation.SYNC_MANIFEST) < events.indexOf(Operation.PUBLISH));
            }
        }
    }
    private static void corruptionAndOwnership() throws Exception {
        Path root = root(); List<Published> out = new ArrayList<Published>();
        try (DirectorySpool spool = open(root)) {
            fails(() -> open(root));
            PcmSegmentWriter w = new PcmSegmentWriter(spool, epoch(1, 0, "phone"), 2, 0, out::add);
            w.append(new byte[] {1, 2, 3, 4}, 0, 4); w.stop();
            String id = out.get(0).localObjectId; Path file = root.resolve(id).resolve("audio.wav");
            byte[] bad = Files.readAllBytes(file); bad[44] ^= 1; Files.write(file, bad, StandardOpenOption.TRUNCATE_EXISTING);
            fails(() -> spool.confirmReady(id)); yes(scan(spool).get(0).state == RecoveryState.CORRUPT_PRESERVED); eq(48, Files.size(file));
            fails(() -> spool.confirmReady("../outside.ready")); fails(() -> spool.confirmReady(null));
        }
        DirectorySpool.DirectoryProtocol unsupported = new DirectorySpool.DirectoryProtocol() {
            @Override public void syncDirectory(Path directory) throws IOException { throw new DirectorySpool.StorageException(DirectorySpool.Failure.UNSUPPORTED_DURABILITY, "unsupported test directory sync", null); }
            @Override public void atomicPublish(Path a, Path b) throws IOException { throw new AssertionError("must not publish"); }
        };
        Path unsupportedRoot = root(); fails(() -> new DirectorySpool(unsupportedRoot, new CachePolicy(100000, 0), unsupported, (op, path) -> { }));
        // Failed initialization released its lock; a supported instance can acquire it.
        try (DirectorySpool supported = open(unsupportedRoot)) { eq(0, scan(supported).size()); }
        DirectorySpool.DirectoryProtocol noAtomic = new DirectorySpool.DirectoryProtocol() {
            private final DirectorySpool.NioProtocol nio = new DirectorySpool.NioProtocol();
            @Override public void syncDirectory(Path p) throws IOException { nio.syncDirectory(p); }
            @Override public void atomicPublish(Path a, Path b) throws IOException { throw new DirectorySpool.StorageException(DirectorySpool.Failure.UNSUPPORTED_DURABILITY, "unsupported test atomic rename", null); }
        };
        try (DirectorySpool spool = new DirectorySpool(root(), new CachePolicy(100000, 0), noAtomic, (op, path) -> { })) {
            List<Published> none = new ArrayList<Published>(); PcmSegmentWriter w = new PcmSegmentWriter(spool, epoch(1, 0, "phone"), 2, 0, none::add);
            fails(() -> w.append(new byte[4], 0, 4)); eq(0, none.size()); yes(scan(spool).get(0).state == RecoveryState.PRESERVED_PARTIAL);
        }
    }
    private static void extraFailureEdges() throws Exception {
        Path root = root(); List<Published> out = new ArrayList<Published>();
        try (DirectorySpool spool = open(root)) {
            PcmSegmentWriter empty = new PcmSegmentWriter(spool, epoch(1, 0, "phone"), 1, 0, out::add);
            empty.stop(); eq(0, scan(spool).size());
            PcmSegmentWriter exhausted = new PcmSegmentWriter(spool, epoch(1, 0, "phone"), 1, Long.MAX_VALUE, out::add);
            fails(() -> exhausted.append(new byte[2], 0, 2)); eq(0, scan(spool).size());
            PcmSegmentWriter callbackFailure = new PcmSegmentWriter(spool, epoch(1, 0, "phone"), 1, 0,
                p -> { throw new IOException("queue temporarily unavailable"); });
            fails(() -> callbackFailure.append(new byte[2], 0, 2));
            Recovery r = scan(spool).get(0); yes(r.state == RecoveryState.FINALIZED_UNCONFIRMED);
            Published recovered = spool.confirmReady(r.localObjectId); eq(1, recovered.metadata.frameCount);
            Files.write(root.resolve(r.localObjectId).resolve("unknown-unverified-tail"), new byte[] {7});
            fails(() -> spool.confirmReady(r.localObjectId)); yes(scan(spool).get(0).state == RecoveryState.CORRUPT_PRESERVED);
            eq(1, Files.size(root.resolve(r.localObjectId).resolve("unknown-unverified-tail")));
        }
        Path bitflip = root(); List<Published> one = new ArrayList<Published>();
        try (DirectorySpool spool = open(bitflip)) {
            PcmSegmentWriter w = new PcmSegmentWriter(spool, epoch(1, 0, "phone"), 1, 0, one::add);
            w.append(new byte[2], 0, 2); w.stop();
            Path manifest = bitflip.resolve(one.get(0).localObjectId).resolve("manifest.bin");
            byte[] changed = Files.readAllBytes(manifest); changed[changed.length - 33] ^= 1;
            Files.write(manifest, changed, StandardOpenOption.TRUNCATE_EXISTING);
            fails(() -> spool.confirmReady(one.get(0).localObjectId));
            yes(scan(spool).get(0).state == RecoveryState.CORRUPT_PRESERVED); eq(changed.length, Files.size(manifest));
        }
        // Simulate a short/torn payload write, then throw before the writer can advance its counters.
        Path torn = root(); boolean[] fired = {false};
        try (DirectorySpool spool = open(torn, 1000000, (op, file) -> {
            if (op == Operation.APPEND_PCM && !fired[0]) {
                fired[0] = true; Files.write(file, new byte[] {11, 12, 13}, StandardOpenOption.APPEND);
                throw new IOException("simulated partial write");
            }
        })) {
            PcmSegmentWriter w = new PcmSegmentWriter(spool, epoch(1, 0, "phone"), 10, 0, out::add);
            fails(() -> w.append(new byte[8], 0, 8)); eq(0, out.size());
            Recovery r = scan(spool).get(0); eq(1, r.observedWholeFrames); eq(1, r.trailingBytes);
            eq(47, Files.size(torn.resolve(r.localObjectId).resolve("audio.wav")));
        }
    }
    private static void initializationReachability() throws Exception {
        Path parent = root().toAbsolutePath(); Path missing = parent.resolve("spool");
        List<Path> synced = new ArrayList<Path>();
        DirectorySpool.DirectoryProtocol tracking = new DirectorySpool.DirectoryProtocol() {
            private final DirectorySpool.NioProtocol nio = new DirectorySpool.NioProtocol();
            @Override public void syncDirectory(Path p) throws IOException { nio.syncDirectory(p); synced.add(p); }
            @Override public void atomicPublish(Path a, Path b) throws IOException { nio.atomicPublish(a, b); }
        };
        try (DirectorySpool spool = new DirectorySpool(missing, new CachePolicy(100000, 0), tracking, (op, p) -> { })) {
            yes(Files.isDirectory(missing)); yes(synced.contains(missing)); yes(synced.contains(parent));
            yes(synced.indexOf(missing) < synced.indexOf(parent)); eq(0, scan(spool).size());
        }
        Path absentParent = parent.resolve("not-established");
        fails(() -> open(absentParent.resolve("spool"))); yes(!Files.exists(absentParent));
        Path failedRoot = parent.resolve("parent-sync-fails");
        DirectorySpool.DirectoryProtocol failing = new DirectorySpool.DirectoryProtocol() {
            private final DirectorySpool.NioProtocol nio = new DirectorySpool.NioProtocol();
            @Override public void syncDirectory(Path p) throws IOException {
                if (p.equals(parent)) throw new IOException("injected parent sync failure");
                nio.syncDirectory(p);
            }
            @Override public void atomicPublish(Path a, Path b) throws IOException { throw new AssertionError("no recording admission"); }
        };
        fails(() -> new DirectorySpool(failedRoot, new CachePolicy(100000, 0), failing, (op, p) -> { }));
        try (DirectorySpool reopened = open(failedRoot)) { eq(0, scan(reopened).size()); }
    }
    private static void partialEvidenceValidation() throws Exception {
        Path extraRoot = root();
        try (DirectorySpool spool = open(extraRoot)) {
            PcmSegmentWriter w = new PcmSegmentWriter(spool, epoch(1, 0, "phone"), 10, 0, p -> { });
            w.append(new byte[] {1, 2, 3}, 0, 3); fails(w::stop);
            Path object = extraRoot.resolve(scan(spool).get(0).localObjectId);
            byte[] original = Files.readAllBytes(object.resolve("audio.wav"));
            Path extra = object.resolve("unverified-extra"); Files.write(extra, new byte[] {99});
            yes(scan(spool).get(0).state == RecoveryState.CORRUPT_PRESERVED);
            eq(1, Files.size(extra)); yes(Arrays.equals(original, Files.readAllBytes(object.resolve("audio.wav"))));
        }
        for (int variant = 0; variant < 3; variant++) {
            Path root = root();
            try (DirectorySpool spool = open(root, 1000000, (op, path) -> {
                if (op == Operation.PUBLISH) throw new IOException("preserve interrupted finalization");
            })) {
                PcmSegmentWriter w = new PcmSegmentWriter(spool, epoch(1, 0, "phone"), 2, 0, p -> { });
                fails(() -> w.append(new byte[] {4, 3, 2, 1}, 0, 4));
                Recovery before = scan(spool).get(0); yes(before.state == RecoveryState.PRESERVED_PARTIAL);
                Path object = root.resolve(before.localObjectId); Path manifest = object.resolve("manifest.bin");
                byte[] bytes = Files.readAllBytes(manifest);
                if (variant == 0) {
                    bytes[bytes.length - 1] ^= 1; Files.write(manifest, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                } else if (variant == 1) {
                    SegmentMetadata existing = SegmentMetadata.decode(bytes);
                    Epoch other = new Epoch("other-capture", "run", 0, PcmFormat.DEFAULT, "phone", "phone", Gap.START);
                    CaptureTimeline timeline = new CaptureTimeline(other, 2, 0); timeline.appendFrames(2);
                    SegmentMetadata replacement = new SegmentMetadata(new SegmentMetadata.Intent(other, 0, 0, 2),
                        timeline.seal(existing.pcmSha256, CaptureTimeline.Close.ROTATION), existing.wavSha256);
                    Files.write(manifest, replacement.encode(), StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    Path wav = object.resolve("audio.wav"); byte[] audio = Files.readAllBytes(wav); audio[44] ^= 1;
                    Files.write(wav, audio, StandardOpenOption.TRUNCATE_EXISTING);
                }
                byte[] audioBefore = Files.readAllBytes(object.resolve("audio.wav"));
                byte[] metadataBefore = Files.readAllBytes(manifest);
                yes(scan(spool).get(0).state == RecoveryState.CORRUPT_PRESERVED);
                yes(Arrays.equals(audioBefore, Files.readAllBytes(object.resolve("audio.wav"))));
                yes(Arrays.equals(metadataBefore, Files.readAllBytes(manifest)));
            }
        }
    }
    public static void main(String[] args) throws Exception {
        headers(); arbitraryBuffers(); epochTransitions(); partialRecovery(); boundedCache(); publicationFaults(); corruptionAndOwnership(); extraFailureEdges(); initializationReachability(); partialEvidenceValidation();
        System.out.println("PASS: " + checks + " WAV/storage assertions; synthetic local JVM fixtures only");
    }
}
