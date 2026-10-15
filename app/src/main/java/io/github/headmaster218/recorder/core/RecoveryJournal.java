package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Bounded immutable observation records. No deletion, audio replay, or restoration of authority. */
public final class RecoveryJournal {
    public static final int MAX_RECORD_BYTES = 4096, MAX_FILES = 512;
    public static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024;
    public enum Step { WRITE, FILE_SYNC, PUBLISH, DIRECTORY_SYNC }
    public interface Faults { void before(Step step) throws IOException; }
    private final Path root;
    private final DirectorySpool.DirectoryProtocol protocol;
    private final Faults faults;
    public RecoveryJournal(Path root, DirectorySpool.DirectoryProtocol protocol, Faults faults) {
        if (root == null || protocol == null || faults == null) throw new IllegalArgumentException();
        this.root = root; this.protocol = protocol; this.faults = faults;
    }
    /** Acknowledge only after return; true warns of unrelated historical partial/unknown/oversized entries. Run off UI/PCM lane. */
    public boolean persist(String session, int ordinal, String record) throws IOException {
        if (session == null || !session.matches("[a-zA-Z0-9-]{1,80}") || ordinal < 0 || ordinal > 127 || record == null)
            throw new IllegalArgumentException("bounded session record identity required");
        byte[] bytes = record.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_RECORD_BYTES) throw new IOException("Gap record exceeds its bound");
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe journal directory");
        protocol.syncDirectory(root); protocol.syncDirectory(root.toAbsolutePath().getParent());
        try (FileChannel lockChannel = FileChannel.open(root.resolve(".journal.lock"), StandardOpenOption.CREATE,
                 StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock lock = lockChannel.tryLock()) {
            if (lock == null || !lock.isValid()) throw new IOException("Gap journal is busy");
            Path target = root.resolve(session + "-" + ordinal + ".ready");
            Path part = root.resolve(session + "-" + ordinal + ".part");
            // A partial for this exact identity cannot be replaced or silently treated as committed.
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Uncommitted gap identity already exists; retained for inspection");
            int count = 0; long total = 0; boolean historicalUncertainty = false;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
                for (Path p : entries) {
                    String name = p.getFileName().toString();
                    if (name.equals(".journal.lock")) continue;
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!a.isRegularFile() || ++count > MAX_FILES || a.size() > MAX_TOTAL_BYTES - total)
                        throw new IOException("Gap journal full or contains an unsafe entry; existing evidence retained");
                    total += a.size();
                    // Old unrelated partial/unknown/oversized records do not grant or revoke this session's authority.
                    // Preserve and count them, and report incomplete historical coverage to the caller.
                    if (!name.matches("[a-zA-Z0-9-]{1,80}-(0|[1-9][0-9]?|1[01][0-9]|12[0-7])\\.ready") || a.size() > MAX_RECORD_BYTES) historicalUncertainty = true;
                }
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes a = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!a.isRegularFile() || a.size() != bytes.length) throw new IOException("Gap identity collision");
                byte[] existing = Files.readAllBytes(target);
                if (!java.util.Arrays.equals(bytes, existing)) throw new IOException("Gap identity collision");
                protocol.syncDirectory(root); return historicalUncertainty;
            }
            if (count >= MAX_FILES) throw new IOException("Gap journal file quota reached; existing evidence retained");
            if (bytes.length > MAX_TOTAL_BYTES - total) throw new IOException("Gap journal byte quota reached");
            try (FileChannel file = FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                faults.before(Step.WRITE); ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) if (file.write(buffer) <= 0) throw new IOException("Gap journal write made no progress");
                faults.before(Step.FILE_SYNC); file.force(true);
            }
            faults.before(Step.PUBLISH); protocol.atomicPublish(part, target);
            faults.before(Step.DIRECTORY_SYNC); protocol.syncDirectory(root);
            return historicalUncertainty;
        }
    }
    /** Observation timestamps are callback times; physical gap boundaries remain unknown. */
    public static String encode(String session, InterruptionRecovery.GapEvent e, CaptureEvidence.Result result,
                                long nextEpoch, CaptureEvidence.FirstPcm first) {
        StringBuilder b = new StringBuilder("schema=1\nsession=").append(session);
        if (e == null) b.append("\nkind=RUN_FINISHED\nphysicalGapStart=unknown\nphysicalGapEnd=unknown\nnextEpoch=").append(nextEpoch).append("\nreplay=never\n");
        else b.append("\nkind=").append(e.kind).append("\ngeneration=").append(e.generation)
            .append("\ngap=").append(e.gap).append("\nobservedStartMillis=").append(e.startMillis)
            .append("\nobservedAtMillis=").append(e.observedAtMillis).append("\nobservedEndMillis=").append(e.endMillis)
            .append("\nphysicalGapStart=unknown\nphysicalGapEnd=unknown\nreasonMask=").append(e.reasonMask)
            .append("\nretriesUsed=").append(e.retriesUsed).append("\nend=").append(e.end)
            .append("\nnextEpoch=").append(nextEpoch).append("\nreplay=never\n");
        if (result != null) b.append("captureId=").append(result.captureId).append("\nretiredRunId=").append(result.runId)
            .append("\nfailureMask=").append(result.failureMask).append("\nrelease=").append(result.release)
            .append("\nwriter=").append(result.writer).append("\nspool=").append(result.spool)
            .append("\nproducerTerminated=").append(result.producerTerminated)
            .append("\nreadSamples=").append(result.pcm.readSamples).append("\nconfirmedSamples=").append(result.pcm.confirmedSamples)
            .append("\nunconfirmedUpperBoundSamples=").append(result.pcm.unconfirmedSamples)
            .append("\ncounterOverflow=").append(result.pcm.counterOverflow).append("\n");
        if (first != null) b.append("newRunId=").append(first.runId).append("\nfirstAppendedNanos=").append(first.appendedAtNanos)
            .append("\nfirstReadBeforeNanos=").append(first.readBeforeNanos).append("\nfirstReadAfterNanos=").append(first.readAfterNanos).append("\n");
        return b.toString();
    }
}
