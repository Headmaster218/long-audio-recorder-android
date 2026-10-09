package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import io.github.headmaster218.recorder.core.CaptureTimeline.SegmentManifest;
import io.github.headmaster218.recorder.core.SegmentMetadata.Intent;

/** Owned, private directory only. One cooperating owner; no protection against hostile filesystem mutation. */
public final class DirectorySpool implements SegmentStore, AutoCloseable {
    public enum Operation { CREATE_STAGE, WRITE_INTENT, SYNC_INTENT, WRITE_HEADER, APPEND_PCM, SYNC_PCM,
        PATCH_HEADER, SYNC_FINAL_WAV, HASH_WAV, WRITE_MANIFEST, SYNC_MANIFEST, SYNC_STAGE, PUBLISH, SYNC_ROOT, VALIDATE }
    public interface Faults { void before(Operation operation, Path path) throws IOException; }
    public interface DirectoryProtocol {
        void syncDirectory(Path directory) throws IOException;
        void atomicPublish(Path staging, Path destination) throws IOException;
    }
    public enum Failure { CACHE_FULL, UNSUPPORTED_DURABILITY, IO_FAILURE }
    public static final class StorageException extends IOException {
        private static final long serialVersionUID = 1L;
        public final Failure failure;
        public StorageException(Failure failure, String message, Throwable cause) {
            super(message, cause); this.failure = failure;
        }
    }
    public static final class NioProtocol implements DirectoryProtocol {
        @Override public void syncDirectory(Path directory) throws IOException {
            try (FileChannel c = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                c.force(true);
            } catch (UnsupportedOperationException e) {
                throw new StorageException(Failure.UNSUPPORTED_DURABILITY, "directory sync unsupported", e);
            } catch (IOException e) {
                throw new StorageException(Failure.IO_FAILURE, "directory sync failed", e);
            }
        }
        @Override public void atomicPublish(Path from, Path to) throws IOException {
            // The exclusive spool lock and private immutable namespace must protect this no-replace check.
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new IOException("destination already exists");
            try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) {
                throw new StorageException(Failure.UNSUPPORTED_DURABILITY, "atomic directory rename unsupported", e);
            } catch (UnsupportedOperationException e) {
                throw new StorageException(Failure.UNSUPPORTED_DURABILITY, "atomic directory rename unsupported", e);
            }
        }
    }
    public enum RecoveryState { PRESERVED_PARTIAL, CORRUPT_PRESERVED, FINALIZED_UNCONFIRMED }
    public static final class Recovery {
        public final String localObjectId;
        public final RecoveryState state;
        public final long observedWholeFrames, trailingBytes;
        public final String detail;
        private Recovery(String id, RecoveryState state, long frames, long tail, String detail) {
            this.localObjectId = id; this.state = state; this.observedWholeFrames = frames;
            this.trailingBytes = tail; this.detail = detail;
        }
    }
    public interface RecoveryListener { void found(Recovery recovery) throws IOException; }
    public static final long FINALIZATION_RESERVE = SegmentMetadata.MAX_BYTES + 4096L;
    private static final String WAV = "audio.wav", INTENT = "intent.bin", MANIFEST = "manifest.bin";
    private final Path root;
    private final CachePolicy cache;
    private final DirectoryProtocol protocol;
    private final Faults faults;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private Stage active;
    private boolean closed;
    public DirectorySpool(Path root, CachePolicy cache, DirectoryProtocol protocol, Faults faults) throws IOException {
        if (root == null || cache == null || protocol == null || faults == null) throw new IllegalArgumentException();
        this.root = root.toAbsolutePath().normalize(); this.cache = cache; this.protocol = protocol; this.faults = faults;
        Path parent = this.root.getParent();
        // Bounded initialization: the caller supplies an already durably established parent hierarchy.
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("spool parent must already exist as an established directory");
        if (!Files.exists(this.root, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(this.root);
        if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("spool root must be a directory, not a symlink");
        FileChannel channel = FileChannel.open(this.root.resolve(".writer.lock"), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            try { acquired = channel.tryLock(); } catch (OverlappingFileLockException e) { throw new IOException("spool already owned", e); }
            if (acquired == null) throw new IOException("spool already owned");
            protocol.syncDirectory(this.root);
            // The parent entry makes this root reachable after restart. Do this even if another
            // cooperating creator made the root before this constructor acquired the lock.
            protocol.syncDirectory(parent); // Unsupported/failed durability blocks recording admission.
        } catch (IOException | RuntimeException e) {
            try { if (acquired != null) acquired.release(); }
            catch (IOException closeFailure) { e.addSuppressed(closeFailure); }
            finally { try { channel.close(); } catch (IOException closeFailure) { e.addSuppressed(closeFailure); } }
            throw e;
        }
        lockChannel = channel; lock = acquired;
    }
    private void live() throws IOException { if (closed || !lock.isValid()) throw new IOException("spool closed or ownership lost"); }
    private void op(Operation operation, Path path) throws IOException { live(); faults.before(operation, path); }
    private void syncDirectory(Operation operation, Path path) throws IOException { op(operation, path); protocol.syncDirectory(path); }
    private static boolean regular(Path p) throws IOException {
        return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile();
    }
    private long occupied() throws IOException {
        long total = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path p : dirs) {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isRegularFile()) total = Checks.add(total, a.size());
                else if (a.isDirectory()) {
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(p)) {
                        for (Path f : files) {
                            if (!regular(f)) throw new IOException("unexpected nested/symlink spool entry");
                            total = Checks.add(total, Files.size(f));
                        }
                    }
                } else throw new IOException("unexpected spool entry");
            }
        }
        return total;
    }
    private void budget(long growth, boolean reserveFinalization) throws IOException {
        live(); long required = Checks.add(growth, reserveFinalization ? FINALIZATION_RESERVE : 0);
        if (cache.beforeWrite(occupied(), Files.getFileStore(root).getUsableSpace(), required) != CachePolicy.Decision.CONTINUE)
            throw new StorageException(Failure.CACHE_FULL, "STOP_AND_ALERT: preserve all staged and ready data", null);
    }
    private static String prefix(String captureId) {
        Checks.text(captureId, "captureId"); MessageDigest digest = PcmSegmentWriter.sha256();
        return "segment-" + PcmSegmentWriter.hex(digest.digest(captureId.getBytes(java.nio.charset.StandardCharsets.UTF_8))) + "-";
    }
    private static String id(Intent intent) { return prefix(intent.epoch.captureId) + intent.sequence; }
    /** Includes empty/failed staging reservations. This suggests an ordinal; open() reserves it under the lock. */
    public long nextSequence(String captureId) throws IOException {
        live(); String prefix = prefix(captureId); long next = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path p : entries) {
                String name = p.getFileName().toString(); if (!name.startsWith(prefix)) continue;
                String suffix = name.substring(prefix.length());
                if (!suffix.matches("[0-9]+\\.(part|ready)")) throw new IOException("malformed sequence reservation");
                try {
                    long used = Long.parseLong(suffix.substring(0, suffix.indexOf('.')));
                    if (used == Long.MAX_VALUE) throw new IOException("sequence exhausted");
                    next = Math.max(next, used + 1);
                } catch (NumberFormatException e) { throw new IOException("invalid sequence reservation", e); }
            }
        }
        return next;
    }
    private Path object(String name) throws IOException {
        if (name == null || !name.matches("segment-[0-9a-f]{64}-[0-9]+\\.(part|ready)"))
            throw new IOException("invalid local object identity");
        Path p = root.resolve(name);
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing/invalid object directory");
        return p;
    }
    @Override public Staging open(Intent intent) throws IOException {
        live(); if (active != null && !active.done) throw new IOException("only one open segment allowed");
        if (intent.sequence < nextSequence(intent.epoch.captureId)) throw new IOException("stale/reused sequence");
        String identity = id(intent); Path stage = root.resolve(identity + ".part");
        if (Files.exists(root.resolve(identity + ".ready"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("sequence already published");
        byte[] journal = SegmentMetadata.encodeIntent(intent);
        budget(journal.length + WavHeader.BYTES, true);
        op(Operation.CREATE_STAGE, stage); Files.createDirectory(stage); // CREATE_NEW reserves identity; never overwrites.
        FileChannel wav = null;
        try {
            writeNew(stage.resolve(INTENT), journal, Operation.WRITE_INTENT, Operation.SYNC_INTENT, true);
            op(Operation.WRITE_HEADER, stage.resolve(WAV)); budget(WavHeader.BYTES, true);
            wav = FileChannel.open(stage.resolve(WAV), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
            writeFully(wav, ByteBuffer.wrap(WavHeader.encode(intent.epoch.format, 0))); wav.force(true);
            syncDirectory(Operation.SYNC_STAGE, stage); syncDirectory(Operation.SYNC_ROOT, root);
            active = new Stage(intent, stage, wav); return active;
        } catch (IOException | RuntimeException e) {
            if (wav != null) try { wav.close(); } catch (IOException closeFailure) { e.addSuppressed(closeFailure); }
            throw e;
        }
    }
    private void writeNew(Path path, byte[] bytes, Operation write, Operation sync, boolean reserve) throws IOException {
        budget(bytes.length, reserve); op(write, path);
        try (FileChannel c = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            writeFully(c, ByteBuffer.wrap(bytes)); op(sync, path); c.force(true);
        }
    }
    private static void writeFully(FileChannel c, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) if (c.write(b) <= 0) throw new IOException("zero-progress file write");
    }
    private static byte[] readSmall(Path p) throws IOException {
        if (!regular(p)) throw new IOException("metadata is not a regular file");
        byte[] buffer = new byte[SegmentMetadata.MAX_BYTES + 1]; int count = 0;
        try (FileChannel c = FileChannel.open(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer b = ByteBuffer.wrap(buffer); int n;
            while (b.hasRemaining() && (n = c.read(b)) != -1) { if (n == 0) throw new IOException("zero-progress read"); count += n; }
        }
        if (count > SegmentMetadata.MAX_BYTES) throw new IOException("oversized metadata");
        return Arrays.copyOf(buffer, count);
    }
    private static String[] hashWav(Path p, Intent intent, long frames) throws IOException {
        if (!regular(p)) throw new IOException("WAV is not a regular file");
        byte[] expectedHeader = WavHeader.encode(intent.epoch.format, frames);
        long expectedSize = Checks.add(intent.epoch.format.bytesForFrames(frames), WavHeader.BYTES);
        MessageDigest full = PcmSegmentWriter.sha256(), pcm = PcmSegmentWriter.sha256();
        try (FileChannel c = FileChannel.open(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (c.size() != expectedSize) throw new IOException("WAV length mismatch");
            ByteBuffer header = ByteBuffer.allocate(WavHeader.BYTES);
            while (header.hasRemaining()) if (c.read(header) <= 0) throw new IOException("short WAV header");
            if (!Arrays.equals(expectedHeader, header.array())) throw new IOException("WAV header mismatch");
            full.update(header.array()); ByteBuffer block = ByteBuffer.allocate(8192); long seen = WavHeader.BYTES;
            int n; while ((n = c.read(block)) != -1) {
                if (n == 0) throw new IOException("zero-progress WAV read");
                full.update(block.array(), 0, n); pcm.update(block.array(), 0, n); seen += n; ((java.nio.Buffer) block).clear();
                if (seen > expectedSize) throw new IOException("WAV changed during verification");
            }
            if (seen != expectedSize) throw new IOException("WAV truncated during verification");
        }
        return new String[] { PcmSegmentWriter.hex(pcm.digest()), PcmSegmentWriter.hex(full.digest()) };
    }
    private static void checkStagedHeader(Path wav, Intent intent, long dataBytes) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(WavHeader.BYTES);
        try (FileChannel c = FileChannel.open(wav, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (header.hasRemaining()) if (c.read(header) <= 0) throw new IOException("short staged header");
        }
        boolean placeholder = Arrays.equals(header.array(), WavHeader.encode(intent.epoch.format, 0));
        long frameBytes = intent.epoch.format.channels * 2L;
        boolean finalized = dataBytes % frameBytes == 0
            && Arrays.equals(header.array(), WavHeader.encode(intent.epoch.format, dataBytes / frameBytes));
        if (!placeholder && !finalized) throw new IOException("corrupt staged WAV header; retain without salvage assertion");
    }
    private static boolean checkEntries(Path directory, boolean requireManifest) throws IOException {
        boolean intent = false, wav = false, manifest = false;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path p : files) {
                if (!regular(p)) throw new IOException("unexpected nested/symlink object entry");
                String name = p.getFileName().toString();
                if (name.equals(INTENT)) intent = true;
                else if (name.equals(WAV)) wav = true;
                else if (name.equals(MANIFEST)) manifest = true;
                else throw new IOException("unexpected object entry; preserve all bytes");
            }
        }
        if (!intent || !wav || (requireManifest && !manifest)) throw new IOException("incomplete object");
        return manifest;
    }
    private static SegmentMetadata checkManifest(Path directory, Intent intent) throws IOException {
        SegmentMetadata m = SegmentMetadata.decode(readSmall(directory.resolve(MANIFEST)));
        if (!SegmentMetadata.sameIntent(intent, m.intent)) throw new IOException("manifest/intent mismatch");
        String[] hashes = hashWav(directory.resolve(WAV), intent, m.frameCount);
        if (!m.pcmSha256.equals(hashes[0]) || !m.wavSha256.equals(hashes[1])) throw new IOException("content hash mismatch");
        return m;
    }
    private SegmentMetadata validate(Path directory) throws IOException {
        op(Operation.VALIDATE, directory); checkEntries(directory, true);
        Intent intent = SegmentMetadata.decodeIntent(readSmall(directory.resolve(INTENT)));
        if (!directory.getFileName().toString().equals(id(intent) + ".ready")) throw new IOException("object identity mismatch");
        return checkManifest(directory, intent);
    }
    /** Revalidate and re-sync existing final objects; enumeration alone never grants upload eligibility. */
    public Published confirmReady(String localObjectId) throws IOException {
        live(); if (localObjectId == null || !localObjectId.endsWith(".ready")) throw new IOException("partial objects are ineligible");
        Path dir = object(localObjectId); SegmentMetadata m = validate(dir);
        for (String name : new String[] { INTENT, WAV, MANIFEST }) {
            try (FileChannel c = FileChannel.open(dir.resolve(name), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { c.force(true); }
        }
        syncDirectory(Operation.SYNC_STAGE, dir); syncDirectory(Operation.SYNC_ROOT, root);
        SegmentMetadata finalCheck = validate(dir);
        if (!Arrays.equals(m.encode(), finalCheck.encode())) throw new IOException("metadata changed during confirmation");
        return new Published(localObjectId, m);
    }
    /** Read-only recovery report: partial/corrupt bytes are preserved, never silently truncated or promoted. */
    public void scan(RecoveryListener listener) throws IOException {
        live(); if (listener == null) throw new IllegalArgumentException();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path p : dirs) {
                String name = p.getFileName().toString(); if (name.equals(".writer.lock")) continue;
                Recovery result;
                try {
                    Path dir = object(name);
                    if (name.endsWith(".ready")) {
                        SegmentMetadata m = validate(dir);
                        result = new Recovery(name, RecoveryState.FINALIZED_UNCONFIRMED, m.frameCount, 0, "re-sync and revalidate before upload");
                    } else {
                        boolean hasManifest = checkEntries(dir, false);
                        Intent i = SegmentMetadata.decodeIntent(readSmall(dir.resolve(INTENT)));
                        if (!name.equals(id(i) + ".part") || !regular(dir.resolve(WAV))) throw new IOException("partial identity mismatch");
                        long data = Files.size(dir.resolve(WAV)) - WavHeader.BYTES;
                        if (data < 0 || data > i.epoch.format.bytesForFrames(i.targetFrames)) throw new IOException("invalid staged size");
                        checkStagedHeader(dir.resolve(WAV), i, data);
                        // Interrupted finalization evidence cannot be ignored merely because the name is .part.
                        if (hasManifest) checkManifest(dir, i);
                        long frameBytes = i.epoch.format.channels * 2L;
                        result = new Recovery(name, RecoveryState.PRESERVED_PARTIAL, data / frameBytes, data % frameBytes,
                            "observed frames only; no durable-prefix, header, content or continuity assertion");
                    }
                } catch (IOException | IllegalArgumentException e) {
                    result = new Recovery(name, RecoveryState.CORRUPT_PRESERVED, 0, 0, e.getMessage());
                }
                listener.found(result);
            }
        }
    }
    private final class Stage implements Staging {
        private final Intent intent;
        private final Path directory;
        private final FileChannel wav;
        private long pcmBytes;
        private boolean done;
        Stage(Intent intent, Path directory, FileChannel wav) { this.intent = intent; this.directory = directory; this.wav = wav; }
        @Override public void append(byte[] bytes, int offset, int length) throws IOException {
            live(); if (done) throw new IOException("stage closed");
            if (bytes == null || offset < 0 || length <= 0 || offset > bytes.length - length
                || length > intent.epoch.format.bytesForFrames(intent.targetFrames) - pcmBytes) throw new IllegalArgumentException();
            budget(length, true); op(Operation.APPEND_PCM, directory.resolve(WAV));
            writeFully(wav, ByteBuffer.wrap(bytes, offset, length)); pcmBytes += length;
            op(Operation.SYNC_PCM, directory.resolve(WAV)); wav.force(true);
        }
        @Override public Published finish(SegmentManifest manifest) throws IOException {
            live(); if (done) throw new IOException("stage closed");
            if (pcmBytes != manifest.payloadBytes || wav.size() != WavHeader.BYTES + pcmBytes)
                throw new IOException("staged byte count mismatch");
            budget(0, true); op(Operation.PATCH_HEADER, directory.resolve(WAV)); wav.position(0);
            writeFully(wav, ByteBuffer.wrap(WavHeader.encode(intent.epoch.format, manifest.frameCount)));
            op(Operation.SYNC_FINAL_WAV, directory.resolve(WAV)); wav.force(true); wav.close(); done = true;
            op(Operation.HASH_WAV, directory.resolve(WAV)); String[] hashes = hashWav(directory.resolve(WAV), intent, manifest.frameCount);
            if (!manifest.payloadSha256.equals(hashes[0])) throw new IOException("PCM hash mismatch");
            SegmentMetadata metadata = new SegmentMetadata(intent, manifest, hashes[1]);
            writeNew(directory.resolve(MANIFEST), metadata.encode(), Operation.WRITE_MANIFEST, Operation.SYNC_MANIFEST, false);
            syncDirectory(Operation.SYNC_STAGE, directory);
            Path target = root.resolve(id(intent) + ".ready"); op(Operation.PUBLISH, target); protocol.atomicPublish(directory, target);
            syncDirectory(Operation.SYNC_ROOT, root); validate(target);
            active = null; return new Published(target.getFileName().toString(), metadata);
        }
        @Override public void preserve() throws IOException {
            if (!wav.isOpen()) { done = true; return; }
            try { op(Operation.SYNC_PCM, directory.resolve(WAV)); wav.force(true); }
            finally { done = true; wav.close(); }
        }
    }
    @Override public void close() throws IOException {
        if (closed) return;
        try { if (active != null) active.preserve(); }
        finally { closed = true; try { lock.release(); } finally { lockChannel.close(); } }
    }
}
