package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Bounded retirement-generation witness, separate from the database. The caller owns the spool
 * writer lock and supplies an established private root with trusted ancestors. This is not a
 * journal and does not protect against hostile filesystem mutation or rollbacks of both stores.
 */
public final class RetirementSentinel {
    public static final String FILE_NAME = ".retirement-state";
    public static final String TEMP_FILE_NAME = ".retirement-state.tmp";
    public static final int MAX_FILE_BYTES = 4096;
    private static final int MAGIC = 0x52545331, VERSION = 1, UUID_BYTES = 36, CHECKSUM_BYTES = 32;
    private static final int PAYLOAD_BYTES = 4 + 4 + UUID_BYTES + 8;
    public static final int ENCODED_BYTES = PAYLOAD_BYTES + CHECKSUM_BYTES;

    public static final class State {
        public final String storeUuid;
        public final long generation;
        public State(String storeUuid, long generation) {
            if (storeUuid == null || !storeUuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || generation < 0) throw new IllegalArgumentException("Invalid retirement state");
            this.storeUuid = storeUuid; this.generation = generation;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof State)) return false;
            State state = (State) other;
            return storeUuid.equals(state.storeUuid) && generation == state.generation;
        }
        @Override public int hashCode() { return 31 * storeUuid.hashCode() + (int) (generation ^ (generation >>> 32)); }
    }

    // Narrow failure points for synthetic interruption tests, not an alternate filesystem.
    enum Operation { WRITE_TEMP, SYNC_FILE, PUBLISH, SYNC_ROOT }
    interface Faults { void before(Operation operation, Path path) throws IOException; }
    private static final Faults NONE = new Faults() { @Override public void before(Operation operation,Path path) { } };
    private RetirementSentinel() { }

    private static Path root(Path directory) throws IOException {
        if (directory == null) throw new IllegalArgumentException("Missing retirement root");
        Path result = directory.toAbsolutePath().normalize();
        if (!Files.readAttributes(result, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory())
            throw new IOException("Retirement root is not a directory");
        return result;
    }

    /** Reads only the published witness. Absence is not permission to ignore a pending temp file. */
    public static State read(Path directory) throws IOException { return readFile(root(directory).resolve(FILE_NAME)); }

    private static State readFile(Path file) throws IOException {
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException e) { return null; }
        if (!attributes.isRegularFile() || attributes.size() > MAX_FILE_BYTES)
            throw new IOException("Invalid retirement witness file");
        ByteBuffer bytes = ByteBuffer.allocate(MAX_FILE_BYTES + 1);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while (bytes.hasRemaining() && (count = channel.read(bytes)) != -1)
                if (count == 0) throw new IOException("Zero-progress retirement witness read");
        }
        if (bytes.position() != ENCODED_BYTES) throw new IOException("Invalid retirement witness length");
        byte[] encoded = bytes.array();
        MessageDigest digest = PcmSegmentWriter.sha256(); digest.update(encoded, 0, PAYLOAD_BYTES);
        if (!MessageDigest.isEqual(digest.digest(), Arrays.copyOfRange(encoded, PAYLOAD_BYTES, ENCODED_BYTES)))
            throw new IOException("Retirement witness checksum mismatch");
        ByteBuffer payload = ByteBuffer.wrap(encoded, 0, PAYLOAD_BYTES);
        if (payload.getInt() != MAGIC || payload.getInt() != VERSION)
            throw new IOException("Unsupported retirement witness");
        byte[] uuid = new byte[UUID_BYTES]; payload.get(uuid);
        try { return new State(new String(uuid, StandardCharsets.US_ASCII), payload.getLong()); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid retirement witness state", e); }
    }

    private static byte[] encode(State state) {
        ByteBuffer bytes = ByteBuffer.allocate(ENCODED_BYTES);
        bytes.putInt(MAGIC).putInt(VERSION).put(state.storeUuid.getBytes(StandardCharsets.US_ASCII)).putLong(state.generation);
        MessageDigest digest = PcmSegmentWriter.sha256(); digest.update(bytes.array(), 0, PAYLOAD_BYTES);
        bytes.put(digest.digest()); return bytes.array();
    }

    /**
     * Publishes monotonically for one store, then syncs the root. A retained temp may be replayed
     * only for its exact state. No failure path deletes or replaces uncertain scratch evidence.
     * The caller reconciles the database before choosing the target; this method never chooses it.
     */
    public static void write(Path directory, State target, DirectorySpool.DirectoryProtocol protocol) throws IOException {
        write(directory, target, protocol, NONE);
    }

    static void write(Path directory, State target, DirectorySpool.DirectoryProtocol protocol, Faults faults) throws IOException {
        if (target == null || protocol == null || faults == null) throw new IllegalArgumentException("Missing retirement writer argument");
        Path root = root(directory), main = root.resolve(FILE_NAME), temp = root.resolve(TEMP_FILE_NAME);
        State current = readFile(main), pending = readFile(temp);
        if (current != null && (!current.storeUuid.equals(target.storeUuid) || target.generation < current.generation))
            throw new IOException("Retirement witness store mismatch or generation rollback");
        if (pending != null) {
            if (!pending.equals(target) || (current != null && pending.generation < current.generation))
                throw new IOException("Uncertain retirement temp state; preserve for inspection");
        } else if (target.equals(current)) {
            // A prior rename may have succeeded while root fsync or acknowledgment failed.
            force(main, faults); syncRoot(root, protocol, faults); return;
        } else {
            faults.before(Operation.WRITE_TEMP, temp);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = ByteBuffer.wrap(encode(target));
                while (bytes.hasRemaining()) if (channel.write(bytes) <= 0) throw new IOException("Zero-progress retirement witness write");
                faults.before(Operation.SYNC_FILE, temp); channel.force(true);
            }
        }
        // Existing interrupted scratch must be forced again; readback alone proves no durability.
        if (pending != null) force(temp, faults);
        faults.before(Operation.PUBLISH, temp);
        try { Files.move(temp, main, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
            throw new IOException("Atomic retirement witness replacement unsupported", e);
        }
        syncRoot(root, protocol, faults);
    }

    private static void force(Path file, Faults faults) throws IOException {
        faults.before(Operation.SYNC_FILE, file);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
    }
    private static void syncRoot(Path root, DirectorySpool.DirectoryProtocol protocol, Faults faults) throws IOException {
        faults.before(Operation.SYNC_ROOT, root); protocol.syncDirectory(root);
    }
}
