package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

/** Read-only allowlist in the app-owned immutable .ready namespace. Never follows spool symlinks. */
public final class CommittedSegments {
    public static final int PAGE_SIZE = 100;
    private final Path root;
    public static final class Entry {
        public final String id;
        public final SegmentMetadata metadata;
        private final CommittedSegments owner;
        private final byte[] intentBytes, manifestBytes;
        private final Stamp directory, wav, intent, manifest;
        private Entry(CommittedSegments owner, String id, SegmentMetadata metadata,
                byte[] intentBytes, byte[] manifestBytes, Stamp directory, Stamp wav, Stamp intent, Stamp manifest) {
            this.owner = owner; this.id = id; this.metadata = metadata;
            this.intentBytes = intentBytes; this.manifestBytes = manifestBytes;
            this.directory = directory; this.wav = wav; this.intent = intent; this.manifest = manifest;
        }
        public String suggestedName() { return id.substring(0, id.length() - 6) + ".wav"; }
    }
    public static final class Page {
        public final List<Entry> entries;
        public final boolean hasMore;
        public final long rejected;
        private Page(List<Entry> entries, boolean hasMore, long rejected) {
            this.entries = Collections.unmodifiableList(entries); this.hasMore = hasMore; this.rejected = rejected;
        }
        public String cursor() { return entries.isEmpty() ? null : entries.get(entries.size() - 1).id; }
    }
    private static final class Stamp {
        final BasicFileAttributes attrs;
        Stamp(Path p, boolean directory) throws IOException {
            attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (directory ? !attrs.isDirectory() : !attrs.isRegularFile())
                throw new IOException("non-regular or symlink spool entry");
        }
        void same(Stamp other) throws IOException {
            Object key = attrs.fileKey(), otherKey = other.attrs.fileKey();
            if (attrs.size() != other.attrs.size() || !attrs.lastModifiedTime().equals(other.attrs.lastModifiedTime())
                    || !attrs.creationTime().equals(other.attrs.creationTime())
                    || (key != null && !key.equals(otherKey))) throw new IOException("source identity or attributes changed");
        }
    }
    public CommittedSegments(Path root) {
        if (root == null) throw new IllegalArgumentException();
        this.root = root.toAbsolutePath().normalize();
    }
    private void safeRoot() throws IOException {
        // The caller supplies Context.getFilesDir().toPath().toRealPath() as the trusted anchor.
        // Validate every component of the supplied canonical path, including the spool itself.
        Path part = root.getRoot();
        for (Path name : root) { part = part.resolve(name); new Stamp(part, true); }
    }
    private Path directory(String id) throws IOException {
        if (id == null || !id.matches("segment-[0-9a-f]{64}-(0|[1-9][0-9]*)\\.ready"))
            throw new IOException("only committed segment identities may be exported");
        safeRoot(); Path dir = root.resolve(id); new Stamp(dir, true); return dir;
    }
    private static byte[] small(Path path) throws IOException {
        new Stamp(path, false);
        byte[] bytes = new byte[SegmentMetadata.MAX_BYTES + 1]; int count = 0;
        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (count < bytes.length) {
                int n = in.read(bytes, count, bytes.length - count);
                if (n == -1) break;
                if (n == 0) throw new IOException("metadata read made no progress");
                count += n;
            }
        }
        if (count > SegmentMetadata.MAX_BYTES) throw new IOException("oversized source metadata");
        return Arrays.copyOf(bytes, count);
    }
    private Entry inspect(String id) throws IOException {
        Path dir = directory(id); Stamp d = new Stamp(dir, true);
        int names = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                String n = f.getFileName().toString();
                if (!(n.equals("audio.wav") || n.equals("intent.bin") || n.equals("manifest.bin")))
                    throw new IOException("unexpected committed segment entry");
                new Stamp(f, false); names++;
            }
        }
        if (names != 3) throw new IOException("incomplete committed segment");
        Stamp wav = new Stamp(dir.resolve("audio.wav"), false);
        Stamp intent = new Stamp(dir.resolve("intent.bin"), false), manifest = new Stamp(dir.resolve("manifest.bin"), false);
        byte[] ib = small(dir.resolve("intent.bin")), mb = small(dir.resolve("manifest.bin"));
        SegmentMetadata.Intent i = SegmentMetadata.decodeIntent(ib); SegmentMetadata m = SegmentMetadata.decode(mb);
        String expected = "segment-" + PcmSegmentWriter.hex(PcmSegmentWriter.sha256().digest(
            i.epoch.captureId.getBytes(java.nio.charset.StandardCharsets.UTF_8))) + "-" + i.sequence + ".ready";
        if (!id.equals(expected) || !SegmentMetadata.sameIntent(i, m.intent) || wav.attrs.size() != m.wavBytes)
            throw new IOException("committed identity, intent or length mismatch");
        d.same(new Stamp(dir, true)); intent.same(new Stamp(dir.resolve("intent.bin"), false));
        manifest.same(new Stamp(dir.resolve("manifest.bin"), false)); wav.same(new Stamp(dir.resolve("audio.wav"), false));
        return new Entry(this, id, m, ib, mb, d, wav, intent, manifest);
    }
    /** Bounded page: metadata only, never reads audio on the UI/listing path. Content checked before copying. */
    public Page page(String after) throws IOException {
        if (after != null && !after.matches("segment-[0-9a-f]{64}-(0|[1-9][0-9]*)\\.ready")) throw new IOException("invalid page cursor");
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return new Page(new ArrayList<Entry>(), false, 0);
        safeRoot(); TreeMap<String, Entry> selected = new TreeMap<String, Entry>(); long rejected = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                String id = dir.getFileName().toString();
                if (!id.endsWith(".ready") || (after != null && id.compareTo(after) <= 0)) continue;
                if (selected.size() == PAGE_SIZE + 1 && id.compareTo(selected.lastKey()) >= 0) continue;
                try { selected.put(id, inspect(id)); }
                catch (IOException e) { rejected++; }
                if (selected.size() > PAGE_SIZE + 1) selected.pollLastEntry();
            }
        }
        boolean more = selected.size() > PAGE_SIZE; if (more) selected.pollLastEntry();
        return new Page(new ArrayList<Entry>(selected.values()), more, rejected);
    }
    /** Re-admit a durable queue identity; the same strict .ready allowlist applies. */
    public Entry load(String id) throws IOException { return inspect(id); }
    public void unchanged(Entry entry) throws IOException {
        if (entry == null || entry.owner != this) throw new IOException("entry was not admitted by this catalog");
        Entry now = inspect(entry.id);
        if (!Arrays.equals(entry.intentBytes, now.intentBytes) || !Arrays.equals(entry.manifestBytes, now.manifestBytes))
            throw new IOException("source metadata changed");
        entry.directory.same(now.directory); entry.wav.same(now.wav); entry.intent.same(now.intent); entry.manifest.same(now.manifest);
    }
    public InputStream open(Entry entry) throws IOException {
        unchanged(entry);
        InputStream in = Files.newInputStream(root.resolve(entry.id).resolve("audio.wav"), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try { unchanged(entry); return in; }
        catch (IOException | RuntimeException e) { try { in.close(); } catch (IOException close) { e.addSuppressed(close); } throw e; }
    }
    public void verify(Entry entry, VerifiedExport.Cancellation cancellation) throws IOException {
        unchanged(entry); MessageDigest full = PcmSegmentWriter.sha256(), pcm = PcmSegmentWriter.sha256();
        byte[] expected = WavHeader.encode(entry.metadata.intent.epoch.format, entry.metadata.frameCount);
        byte[] block = new byte[VerifiedExport.BUFFER_BYTES]; long seen = 0;
        try (InputStream in = open(entry)) {
            int n;
            while (true) {
                VerifiedExport.checkCancelled(cancellation); n = in.read(block);
                if (n == -1) break;
                if (n <= 0 || n > entry.metadata.wavBytes - seen) throw new IOException("invalid or changed source length");
                for (int j = 0; j < n && seen + j < WavHeader.BYTES; j++)
                    if (block[j] != expected[(int) seen + j]) throw new IOException("source WAV header mismatch");
                full.update(block, 0, n);
                int skip = (int) Math.max(0, WavHeader.BYTES - seen);
                if (skip < n) pcm.update(block, skip, n - skip);
                seen += n;
            }
        }
        if (seen != entry.metadata.wavBytes || !entry.metadata.wavSha256.equals(PcmSegmentWriter.hex(full.digest()))
                || !entry.metadata.pcmSha256.equals(PcmSegmentWriter.hex(pcm.digest()))) throw new IOException("source content changed or corrupt");
        unchanged(entry); VerifiedExport.checkCancelled(cancellation);
    }
}
