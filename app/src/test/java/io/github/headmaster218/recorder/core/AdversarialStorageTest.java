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
import io.github.headmaster218.recorder.core.CaptureTimeline.Close;
import io.github.headmaster218.recorder.core.CaptureTimeline.Epoch;
import io.github.headmaster218.recorder.core.CaptureTimeline.Gap;
import io.github.headmaster218.recorder.core.CaptureTimeline.SegmentManifest;
import io.github.headmaster218.recorder.core.DirectorySpool.Operation;
import io.github.headmaster218.recorder.core.DirectorySpool.Recovery;
import io.github.headmaster218.recorder.core.DirectorySpool.RecoveryState;
import io.github.headmaster218.recorder.core.SegmentMetadata.Intent;
import io.github.headmaster218.recorder.core.SegmentStore.Published;

/** Independent tiny-fixture review; no real audio and no cleanup/deletion. */
public final class AdversarialStorageTest {
    private interface Case { void run() throws Exception; }
    private static int passed, failed, fixture;
    private static final Path BASE = Paths.get("app/build/storage-review/fixtures/run-" + System.nanoTime());
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void rejected(Case c) throws Exception {
        try { c.run(); } catch (IOException | IllegalArgumentException | IllegalStateException e) { return; }
        throw new AssertionError("expected rejection");
    }
    private static void run(String label, Case c) {
        try { c.run(); passed++; System.out.println("PASS " + label); }
        catch (Exception | AssertionError e) { failed++; System.out.println("FAIL " + label + ": " + e); }
    }
    private static Epoch epoch(int channels, long number) {
        return new Epoch("capture", "run", number, new PcmFormat(16000, channels), "phone",
            number == 0 ? "phone" : "headset", number == 0 ? Gap.START : Gap.ROUTE_CHANGE_UNCERTAIN);
    }
    private static Path fixture() throws IOException { return Files.createDirectories(BASE.resolve("case-" + fixture++)); }
    private static DirectorySpool open(Path path) throws IOException {
        return new DirectorySpool(path, new CachePolicy(1000000, 0), new DirectorySpool.NioProtocol(), (o, p) -> { });
    }
    private static List<Recovery> scan(DirectorySpool spool) throws IOException {
        List<Recovery> out = new ArrayList<Recovery>(); spool.scan(out::add); return out;
    }
    private static final class MemoryStore implements SegmentStore {
        final ByteArrayOutputStream complete = new ByteArrayOutputStream();
        final List<SegmentManifest> manifests = new ArrayList<SegmentManifest>();
        ByteArrayOutputStream pending; boolean preserved; int maximumAppend;
        @Override public Staging open(final Intent intent) {
            pending = new ByteArrayOutputStream();
            return new Staging() {
                @Override public void append(byte[] bytes, int offset, int length) {
                    maximumAppend = Math.max(maximumAppend, length); pending.write(bytes, offset, length);
                }
                @Override public Published finish(SegmentManifest manifest) throws IOException {
                    byte[] pcm = pending.toByteArray();
                    check(pcm.length == manifest.payloadBytes, "exact PCM byte count");
                    check(PcmSegmentWriter.hex(PcmSegmentWriter.sha256().digest(pcm)).equals(manifest.payloadSha256), "PCM hash");
                    ByteArrayOutputStream wav = new ByteArrayOutputStream();
                    wav.write(WavHeader.encode(intent.epoch.format, manifest.frameCount)); wav.write(pcm);
                    complete.write(pcm); manifests.add(manifest);
                    return new Published("memory-" + intent.sequence,
                        new SegmentMetadata(intent, manifest, PcmSegmentWriter.hex(PcmSegmentWriter.sha256().digest(wav.toByteArray()))));
                }
                @Override public void preserve() { preserved = true; }
            };
        }
    }
    private static void boundedExactFrames() throws Exception {
        for (int channels : new int[] {1, 2, 32}) {
            byte[] source = new byte[137 * channels * 2];
            for (int i = 0; i < source.length; i++) source[i] = (byte) (31 * i + channels);
            MemoryStore store = new MemoryStore();
            PcmSegmentWriter writer = new PcmSegmentWriter(store, epoch(channels, 0), 31, 19, p -> { });
            int position = 0, read = 0; int[] sizes = {1, 8193, 3, 5, 13};
            while (position < source.length) {
                int count = Math.min(sizes[read++ % sizes.length], source.length - position);
                byte[] padded = new byte[count + 4]; System.arraycopy(source, position, padded, 2, count);
                writer.append(padded, 2, count); position += count;
            }
            writer.stop(); check(Arrays.equals(source, store.complete.toByteArray()), "no lost/invented interleaved bytes");
            check(store.manifests.size() == 5, "exact segment count");
            for (int i = 0; i < store.manifests.size(); i++) {
                SegmentManifest m = store.manifests.get(i);
                check(m.sequence == 19 + i && m.frameStart == 31 * i, "identity and frame adjacency");
                check(m.frameCount == (i == 4 ? 13 : 31), "target or short-tail frames");
                check(m.trustedPreviousSeam == (i > 0), "seam only within the uninterrupted epoch");
            }
            check(store.maximumAppend <= 8192, "storage call remains bounded");
        }
    }
    private static void partialEpochPreserved() throws Exception {
        MemoryStore store = new MemoryStore();
        PcmSegmentWriter writer = new PcmSegmentWriter(store, epoch(2, 0), 5, 0, p -> { });
        byte[] bytes = {1, 2, 3, 4, 5}; writer.append(bytes, 0, bytes.length);
        rejected(() -> writer.beginEpoch(epoch(2, 1)));
        check(store.preserved && store.manifests.isEmpty(), "partial interleaved frame cannot publish");
        check(Arrays.equals(bytes, store.pending.toByteArray()), "every partial byte retained");
        rejected(() -> writer.append(new byte[] {6, 7, 8}, 0, 3));
    }
    private static void largeInputRemainsChunked() throws Exception {
        MemoryStore store = new MemoryStore();
        PcmSegmentWriter writer = new PcmSegmentWriter(store, epoch(1, 0), 5000, 0, p -> { });
        byte[] bytes = new byte[20004];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 13);
        writer.append(bytes, 0, bytes.length); writer.stop();
        check(store.maximumAppend == 8192, "large input is actually split at the 8 KiB storage boundary");
        check(store.manifests.size() == 3 && store.manifests.get(2).frameCount == 2, "chunk and segment boundaries compose");
        check(Arrays.equals(bytes, store.complete.toByteArray()), "cross-chunk and cross-segment bytes remain exact");
    }
    private static void formatEpochs() throws Exception {
        MemoryStore store = new MemoryStore();
        PcmSegmentWriter writer = new PcmSegmentWriter(store, epoch(1, 0), 5, 0, p -> { });
        writer.append(new byte[6], 0, 6); writer.beginEpoch(epoch(2, 1));
        writer.append(new byte[24], 0, 24); writer.stop();
        check(store.manifests.size() == 3, "mono tail plus stereo rotation/tail");
        check(store.manifests.get(0).closeReason == Close.INTERRUPTION, "old epoch explicitly interrupted");
        check(store.manifests.get(1).frameStart == 0 && !store.manifests.get(1).trustedPreviousSeam, "new epoch seam uncertain");
        check(store.manifests.get(2).frameStart == 5 && store.manifests.get(2).trustedPreviousSeam, "new format counts frames, not samples");
    }
    private static void cacheRejectsBeforeGrowth() throws Exception {
        Path root = fixture(); Intent intent = new Intent(epoch(1, 0), 0, 0, 20);
        long ceiling = SegmentMetadata.encodeIntent(intent).length + 44 + DirectorySpool.FINALIZATION_RESERVE + 4;
        try (DirectorySpool spool = new DirectorySpool(root, new CachePolicy(ceiling, 0),
                new DirectorySpool.NioProtocol(), (op, p) -> { })) {
            PcmSegmentWriter writer = new PcmSegmentWriter(spool, intent.epoch, 20, 0, p -> { throw new AssertionError("no publish"); });
            writer.append(new byte[] {1, 2, 3, 4}, 0, 4);
            Path wav = root.resolve(scan(spool).get(0).localObjectId).resolve("audio.wav"); byte[] before = Files.readAllBytes(wav);
            rejected(() -> writer.append(new byte[] {5, 6}, 0, 2));
            check(Arrays.equals(before, Files.readAllBytes(wav)), "cache denial occurs before payload growth");
        }
    }
    private static void readyIdentityAndHashes() throws Exception {
        Path root = fixture(); List<Published> results = new ArrayList<Published>();
        try (DirectorySpool spool = open(root)) {
            PcmSegmentWriter writer = new PcmSegmentWriter(spool, epoch(1, 0), 2, 0, results::add);
            byte[] pcm = {9, 8, 7, 6}; writer.append(pcm, 0, 4); writer.stop();
            Published p = results.get(0); Path object = root.resolve(p.localObjectId);
            byte[] wav = Files.readAllBytes(object.resolve("audio.wav"));
            check(ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40) == 4, "canonical RIFF data length");
            check(p.metadata.pcmSha256.equals(PcmSegmentWriter.hex(PcmSegmentWriter.sha256().digest(pcm))), "raw content hash");
            check(p.metadata.wavSha256.equals(PcmSegmentWriter.hex(PcmSegmentWriter.sha256().digest(wav))), "full WAV hash includes header");
            Intent changed = new Intent(new Epoch("capturX", "run", 0, PcmFormat.DEFAULT, "phone", "phone", Gap.START), 0, 0, 2);
            byte[] otherIntent = SegmentMetadata.encodeIntent(changed);
            Files.write(object.resolve("intent.bin"), otherIntent, StandardOpenOption.TRUNCATE_EXISTING);
            rejected(() -> spool.confirmReady(p.localObjectId));
            check(scan(spool).get(0).state == RecoveryState.CORRUPT_PRESERVED, "valid checksum does not excuse identity substitution");
            check(Arrays.equals(wav, Files.readAllBytes(object.resolve("audio.wav"))), "corruption inspection preserves audio");
        }
    }
    private static void postRenameRecovery() throws Exception {
        Path root = fixture(); boolean[] renamed = {false}; List<Published> results = new ArrayList<Published>();
        DirectorySpool.DirectoryProtocol protocol = new DirectorySpool.DirectoryProtocol() {
            private final DirectorySpool.NioProtocol nio = new DirectorySpool.NioProtocol();
            @Override public void syncDirectory(Path p) throws IOException { nio.syncDirectory(p); }
            @Override public void atomicPublish(Path a, Path b) throws IOException {
                nio.atomicPublish(a, b); renamed[0] = true; throw new IOException("failure after actual rename");
            }
        };
        String id;
        try (DirectorySpool spool = new DirectorySpool(root, new CachePolicy(1000000, 0), protocol, (op, p) -> { })) {
            PcmSegmentWriter writer = new PcmSegmentWriter(spool, epoch(1, 0), 1, 0, results::add);
            rejected(() -> writer.append(new byte[] {4, 3}, 0, 2));
            check(renamed[0] && results.isEmpty(), "uncertain publication gives no callback permission");
            Recovery found = scan(spool).get(0); id = found.localObjectId;
            check(found.state == RecoveryState.FINALIZED_UNCONFIRMED, "complete renamed object requires reconfirmation");
        }
        try (DirectorySpool reopened = open(root)) {
            check(reopened.confirmReady(id).metadata.frameCount == 1, "revalidate and sync recover complete object");
            check(reopened.nextSequence("capture") == 1, "published identity is never reused");
        }
    }
    private static void newRootMustBeDurablyReachable() throws Exception {
        Path parent = fixture().toAbsolutePath(); Path missingRoot = parent.resolve("new-spool");
        List<Path> synced = new ArrayList<Path>();
        DirectorySpool.DirectoryProtocol protocol = new DirectorySpool.DirectoryProtocol() {
            private final DirectorySpool.NioProtocol nio = new DirectorySpool.NioProtocol();
            @Override public void syncDirectory(Path p) throws IOException { nio.syncDirectory(p); synced.add(p.toAbsolutePath()); }
            @Override public void atomicPublish(Path a, Path b) throws IOException { nio.atomicPublish(a, b); }
        };
        DirectorySpool spool;
        try { spool = new DirectorySpool(missingRoot, new CachePolicy(1000000, 0), protocol, (op, p) -> { }); }
        catch (IOException e) { return; } // Requiring an already durably established root is also safe.
        try { check(synced.contains(parent), "created root admitted without syncing its parent directory entry"); }
        finally { spool.close(); }
    }
    private static void unexpectedPartialEntriesReported() throws Exception {
        Path root = fixture();
        try (DirectorySpool spool = open(root)) {
            PcmSegmentWriter writer = new PcmSegmentWriter(spool, epoch(1, 0), 5, 0, p -> { });
            writer.append(new byte[] {1, 2, 3}, 0, 3); rejected(writer::stop);
            String id = scan(spool).get(0).localObjectId; Path extra = root.resolve(id).resolve("unexpected-unverified-audio");
            Files.write(extra, new byte[] {99});
            check(scan(spool).get(0).state == RecoveryState.CORRUPT_PRESERVED, "partial scan silently ignores unexpected object entries");
            check(Files.size(extra) == 1, "unexpected bytes are preserved");
        }
    }
    private static void parentSyncFailureBlocksAdmission() throws Exception {
        Path parent = fixture().toAbsolutePath(); Path root = parent.resolve("spool");
        List<Path> synced = new ArrayList<Path>();
        DirectorySpool.DirectoryProtocol failing = new DirectorySpool.DirectoryProtocol() {
            private final DirectorySpool.NioProtocol nio = new DirectorySpool.NioProtocol();
            @Override public void syncDirectory(Path p) throws IOException {
                synced.add(p);
                if (p.equals(parent)) throw new IOException("parent sync failed");
                nio.syncDirectory(p);
            }
            @Override public void atomicPublish(Path a, Path b) { throw new AssertionError("must never admit recording"); }
        };
        rejected(() -> new DirectorySpool(root, new CachePolicy(1000000, 0), failing, (op, p) -> { }));
        check(synced.size() == 2 && synced.get(0).equals(root) && synced.get(1).equals(parent), "root then parent before admission");
        try (DirectorySpool reopened = open(root)) { check(scan(reopened).isEmpty(), "failed admission releases lock and creates no audio"); }
        Path absentParent = parent.resolve("missing-ancestor");
        rejected(() -> open(absentParent.resolve("spool")));
        check(!Files.exists(absentParent), "missing ancestors are never created implicitly");
    }
    private static void interruptedManifestValidation() throws Exception {
        for (boolean corruptManifest : new boolean[] {true, false}) {
            Path root = fixture(); List<Published> results = new ArrayList<Published>();
            try (DirectorySpool spool = new DirectorySpool(root, new CachePolicy(1000000, 0),
                    new DirectorySpool.NioProtocol(), (op, p) -> {
                        if (op == Operation.PUBLISH) throw new IOException("interrupted before rename");
                    })) {
                PcmSegmentWriter writer = new PcmSegmentWriter(spool, epoch(1, 0), 2, 0, results::add);
                rejected(() -> writer.append(new byte[] {2, 4, 6, 8}, 0, 4));
                Recovery initial = scan(spool).get(0);
                check(initial.state == RecoveryState.PRESERVED_PARTIAL && results.isEmpty(), "valid interrupted finalization is never promoted");
                rejected(() -> spool.confirmReady(initial.localObjectId));
                Path object = root.resolve(initial.localObjectId);
                Path changed = object.resolve(corruptManifest ? "manifest.bin" : "audio.wav");
                byte[] bytes = Files.readAllBytes(changed);
                bytes[corruptManifest ? bytes.length - 1 : 44] ^= 1;
                Files.write(changed, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                check(scan(spool).get(0).state == RecoveryState.CORRUPT_PRESERVED, "present manifest's checksum and content hashes must validate");
                check(Arrays.equals(bytes, Files.readAllBytes(changed)), "corruption reporting does not rewrite or delete evidence");
            }
        }
    }
    public static void main(String[] args) {
        run("arbitrary offset buffers preserve exact mono/stereo/32-channel frames", AdversarialStorageTest::boundedExactFrames);
        run("large input crosses both 8 KiB chunks and exact segment cuts", AdversarialStorageTest::largeInputRemainsChunked);
        run("partial-frame epoch change preserves all bytes and becomes terminal", AdversarialStorageTest::partialEpochPreserved);
        run("format changes start a new uncertain epoch", AdversarialStorageTest::formatEpochs);
        run("cache denial precedes payload growth", AdversarialStorageTest::cacheRejectsBeforeGrowth);
        run("raw/full hashes and checksummed identity substitution", AdversarialStorageTest::readyIdentityAndHashes);
        run("failure after actual rename requires recovery confirmation", AdversarialStorageTest::postRenameRecovery);
        run("new spool root requires durable parent reachability", AdversarialStorageTest::newRootMustBeDurablyReachable);
        run("unexpected partial entries are reported and preserved", AdversarialStorageTest::unexpectedPartialEntriesReported);
        run("parent-sync failure blocks admission and absent ancestors stay absent", AdversarialStorageTest::parentSyncFailureBlocksAdmission);
        run("interrupted manifests validate before conservative recovery reporting", AdversarialStorageTest::interruptedManifestValidation);
        System.out.println("Independent storage review: " + passed + " cases passed, " + failed + " cases failed");
        if (failed != 0) throw new AssertionError("storage review failures: " + failed);
    }
}
