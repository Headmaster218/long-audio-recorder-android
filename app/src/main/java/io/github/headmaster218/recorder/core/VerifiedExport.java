package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** Streaming copy + closed-target readback. Never deletes or modifies any source. */
public final class VerifiedExport {
    public static final int BUFFER_BYTES = 32768;
    public interface Cancellation { boolean cancelled(); }
    public interface Target {
        OutputStream openWrite() throws IOException;
        InputStream openRead() throws IOException;
    }
    public enum Status { VERIFIED, UNVERIFIED, FAILED, CANCELLED }
    public static final class Result {
        public final Status status;
        public final long bytes;
        public final String sha256, detail;
        public final boolean targetWriteAttempted;
        private Result(Status status, long bytes, String sha256, String detail, boolean targetWriteAttempted) {
            this.status = status; this.bytes = bytes; this.sha256 = sha256; this.detail = detail; this.targetWriteAttempted = targetWriteAttempted;
        }
    }
    private static final class Cancelled extends IOException { private static final long serialVersionUID = 1L; }
    static void checkCancelled(Cancellation c) throws IOException { if (c.cancelled()) throw new Cancelled(); }
    private VerifiedExport() { }
    public static Result copy(CommittedSegments source, CommittedSegments.Entry entry, Target target, Cancellation cancel) {
        long copied = 0; boolean opened = false; String hash = "";
        try {
            checkCancelled(cancel); source.verify(entry, cancel); // Reject corrupt/mutated source before target open.
            byte[] block = new byte[BUFFER_BYTES]; MessageDigest written = PcmSegmentWriter.sha256();
            try (InputStream in = source.open(entry)) {
                checkCancelled(cancel); opened = true;
                OutputStream stream = target.openWrite(); if (stream == null) throw new IOException("provider returned no output stream");
                try (OutputStream out = stream) {
                    while (true) {
                        checkCancelled(cancel); int n = in.read(block);
                        if (n == -1) break;
                        if (n <= 0 || n > entry.metadata.wavBytes - copied) throw new IOException("source length changed during copy");
                        out.write(block, 0, n); copied += n; written.update(block, 0, n);
                    }
                    checkCancelled(cancel); out.flush();
                } // A failing close is FAILED, never a successful copy.
            }
            hash = PcmSegmentWriter.hex(written.digest());
            if (copied != entry.metadata.wavBytes || !hash.equals(entry.metadata.wavSha256)) throw new IOException("source changed during copy");
            source.verify(entry, cancel); // Also detects same-length changes after bytes were read.
            long read = 0; MessageDigest observed = PcmSegmentWriter.sha256();
            try {
                InputStream stream = target.openRead(); if (stream == null) throw new IOException("provider returned no input stream");
                try (InputStream in = stream) {
                    while (true) {
                        checkCancelled(cancel); int n = in.read(block);
                        if (n == -1) break;
                        if (n <= 0) throw new IOException("target read made no progress");
                        if (n > copied - read) return new Result(Status.FAILED, copied, hash, "Target has extra bytes; source retained.", true);
                        read += n; observed.update(block, 0, n);
                    }
                }
            } catch (Cancelled e) { throw e; }
            catch (IOException | RuntimeException e) {
                // Copy closed successfully, but readback/close could not be completed. Never promote it.
                source.verify(entry, cancel);
                return new Result(Status.UNVERIFIED, copied, hash, "Copy closed; target readback unavailable (" + e.getClass().getSimpleName() + "). Source retained.", true);
            }
            if (read != copied || !hash.equals(PcmSegmentWriter.hex(observed.digest())))
                return new Result(Status.FAILED, copied, hash, "Target byte count or SHA-256 mismatch; source retained.", true);
            source.verify(entry, cancel); checkCancelled(cancel);
            return new Result(Status.VERIFIED, copied, hash, "Target byte count and SHA-256 matched on readback. Source retained. Remote sync/durability is not confirmed.", true);
        } catch (Cancelled e) {
            return new Result(Status.CANCELLED, copied, hash, "Cancelled; source retained. The chosen target may contain an incomplete file.", opened);
        } catch (IOException | RuntimeException e) {
            if (cancel.cancelled()) return new Result(Status.CANCELLED, copied, hash, "Cancelled; source retained. Target may be incomplete.", opened);
            return new Result(Status.FAILED, copied, hash, "Export failed (" + e.getClass().getSimpleName() + "); source retained. Target may be incomplete.", opened);
        }
    }
}
