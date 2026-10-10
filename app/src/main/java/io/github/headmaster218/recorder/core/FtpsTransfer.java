package io.github.headmaster218.recorder.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Narrow explicit FTPS: TLS control/data, binary EPSV, whole-file readback. Never deletes or overwrites an old attempt. */
public final class FtpsTransfer {
    public static final int BUFFER_BYTES = 32768;
    private static final int MAX_LINE = 1024, MAX_REPLY = 8192, MAX_CONTROL = 65536;
    public static final class Profile {
        public final String host, user, directory;
        public final int port;
        public Profile(String host, int port, String user, String directory) {
            if (host == null || !host.matches("[A-Za-z0-9.:-]{1,253}") || port < 1 || port > 65535)
                throw new IllegalArgumentException("Use an ASCII hostname or IP and a valid FTPS port");
            argument(user, 128); argument(directory, 512);
            if (!directory.startsWith("/")) throw new IllegalArgumentException("FTPS base directory must be absolute");
            for (String component : directory.split("/", -1))
                if (component.equals(".") || component.equals("..")) throw new IllegalArgumentException("Directory traversal is not allowed");
            this.host = host; this.port = port; this.user = user; this.directory = directory;
        }
    }
    public interface Connector { Connection connect(String host, int port) throws IOException; }
    public interface Connection extends AutoCloseable {
        InputStream input() throws IOException;
        OutputStream output() throws IOException;
        /** Must validate trust and expected hostname, and establish TLS 1.2+; throws before credentials/data on failure. */
        void secure(String expectedHost) throws IOException;
        /** Raw passive connection to this control peer address on the SAME approved Network. No supplied hostname. */
        Connection openData(int port) throws IOException;
        @Override void close() throws IOException;
    }
    public interface Guard { void check() throws IOException; }
    public static final class ProtocolException extends IOException {
        private static final long serialVersionUID = 1L;
        public final boolean retryable;
        ProtocolException(String message, boolean retryable) { super(message); this.retryable = retryable; }
    }
    public static final class VerificationException extends IOException {
        private static final long serialVersionUID = 1L;
        VerificationException(String message) { super(message); }
    }
    public static final class Result {
        public final boolean verifiedAtTime = true;
        public final long bytes;
        public final String wavSha256, metadataSha256, markerSha256;
        public final String detail = "Authenticated remote readback matched at verification time. All originals retained; server durability and immutable commit are not proven.";
        private Result(long bytes, String wav, String metadata, String marker) {
            this.bytes = bytes; this.wavSha256 = wav; this.metadataSha256 = metadata; this.markerSha256 = marker;
        }
    }
    private static void argument(String value, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum) throw new IllegalArgumentException("Invalid bounded FTPS setting");
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) > 126)
            throw new IllegalArgumentException("Initial FTPS profile accepts printable ASCII only; no control characters");
    }
    private static String revision(String value) {
        if (value == null || !value.matches("[A-Za-z0-9-]{1,128}")) throw new IllegalArgumentException("Invalid destination revision");
        return value;
    }
    public static String newAttemptName(String sourceId, String wavSha256, String profileRevision) {
        Checks.text(sourceId, "sourceId"); Checks.hash(wavSha256); revision(profileRevision);
        byte[] binding = (sourceId + "\n" + wavSha256 + "\n" + profileRevision).getBytes(StandardCharsets.UTF_8);
        return "r-" + hash(binding) + "-" + UUID.randomUUID().toString().replace("-", "");
    }
    private static void attempt(String value) {
        if (value == null || !value.matches("r-[0-9a-f]{64}-[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid owned attempt name");
    }
    private static String hash(byte[] bytes) { return PcmSegmentWriter.hex(PcmSegmentWriter.sha256().digest(bytes)); }
    private static byte[] metadata(CommittedSegments.Entry entry, String profileRevision) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(b);
        out.writeInt(0x52465431); out.writeUTF(revision(profileRevision)); out.writeUTF(entry.id);
        byte[] local = entry.metadata.encode(); out.writeInt(local.length); out.write(local); out.flush();
        if (b.size() > SegmentMetadata.MAX_BYTES + 512) throw new IOException("Transport metadata exceeds bound");
        return b.toByteArray();
    }
    private static void verifySource(final CommittedSegments catalog, final CommittedSegments.Entry entry, final Guard guard) throws IOException {
        guard.check();
        final IOException[] blocked = {null};
        try {
            catalog.verify(entry, new VerifiedExport.Cancellation() {
                @Override public boolean cancelled() { try { guard.check(); return false; } catch (IOException e) { blocked[0] = e; return true; } }
            });
        } catch (IOException e) {
            if (blocked[0] != null) throw blocked[0];
            throw new VerificationException("Source validation failed; originals retained");
        }
        guard.check();
    }
    /** Persist attemptName BEFORE this call. Reconcile mode never issues MKD/STOR/rename/delete. */
    public static Result run(CommittedSegments catalog, CommittedSegments.Entry entry, Profile profile,
            char[] password, Connector connector, String profileRevision, String attemptName,
            boolean reconcileOnly, Guard guard) throws IOException {
        if (catalog == null || entry == null || profile == null || connector == null || guard == null) throw new IllegalArgumentException();
        attempt(attemptName); revision(profileRevision);
        if (password == null || password.length == 0 || password.length > 256) throw new IllegalArgumentException("Password is required and bounded");
        for (char c : password) if (c < 32 || c > 126) throw new IllegalArgumentException("Initial FTPS password supports printable ASCII only");
        String binding = newAttemptName(entry.id, entry.metadata.wavSha256, profileRevision).substring(0, 67);
        if (!attemptName.startsWith(binding)) throw new IOException("Attempt is not bound to this source and destination revision");
        verifySource(catalog, entry, guard);
        byte[] meta = metadata(entry, profileRevision); String metaHash = hash(meta);
        byte[] marker = ("RECORDER-DELIVERY-V1\n" + attemptName + "\n" + profileRevision + "\n" + entry.id + "\n"
            + entry.metadata.wavBytes + "\n" + entry.metadata.wavSha256 + "\n" + meta.length + "\n" + metaHash + "\n").getBytes(StandardCharsets.US_ASCII);
        String markerHash = hash(marker);
        guard.check();
        try (Connection control = connector.connect(profile.host, profile.port)) {
            if (control == null) throw new IOException("Transport returned no control connection");
            Session s = new Session(control, profile.host, guard);
            s.expect(s.reply(), 220); s.command("AUTH TLS"); s.expect(s.reply(), 234);
            guard.check(); control.secure(profile.host); guard.check();
            s.command("USER " + profile.user); Reply login = s.reply();
            if (login.code == 331) {
                // Transient command only, never included in diagnostics, queue state or logs.
                s.command("PASS " + new String(password)); s.expect(s.reply(), 230);
            } else s.expect(login, 230);
            s.command("PBSZ 0"); s.expect(s.reply(), 200);
            s.command("PROT P"); s.expect(s.reply(), 200);
            s.command("TYPE I"); s.expect(s.reply(), 200);
            s.command("CWD " + profile.directory); s.expect(s.reply(), 250);
            if (!reconcileOnly) { s.command("MKD " + attemptName); s.expect(s.reply(), 257); }
            s.command("CWD " + attemptName); s.expect(s.reply(), 250);
            if (!reconcileOnly) {
                try (InputStream source = catalog.open(entry)) { s.store("audio.wav", source, entry.metadata.wavBytes, entry.metadata.wavSha256); }
                verifySource(catalog, entry, guard);
            }
            s.readback("audio.wav", entry.metadata.wavBytes, entry.metadata.wavSha256);
            if (!reconcileOnly) s.store("segment.v1.bin", new ByteArrayInputStream(meta), meta.length, metaHash);
            s.readback("segment.v1.bin", meta.length, metaHash);
            if (!reconcileOnly) s.store("delivery.v1.txt", new ByteArrayInputStream(marker), marker.length, markerHash);
            s.readback("delivery.v1.txt", marker.length, markerHash);
            // Recheck final objects after marker publication. This is observed content, never immutable-commit proof.
            s.readback("audio.wav", entry.metadata.wavBytes, entry.metadata.wavSha256);
            s.readback("segment.v1.bin", meta.length, metaHash);
            s.readback("delivery.v1.txt", marker.length, markerHash);
            verifySource(catalog, entry, guard);
        }
        guard.check(); return new Result(entry.metadata.wavBytes, entry.metadata.wavSha256, metaHash, markerHash);
    }
    private static final class Reply {
        final int code; final String last;
        Reply(int code, String last) { this.code = code; this.last = last; }
    }
    private static final class Session {
        final Connection control; final String host; final Guard guard;
        int totalRead, commands;
        Session(Connection control, String host, Guard guard) { this.control = control; this.host = host; this.guard = guard; }
        void command(String command) throws IOException {
            guard.check(); if (++commands > 64 || command.length() > 768 || command.indexOf("\r") >= 0 || command.indexOf("\n") >= 0)
                throw new ProtocolException("Command bound exceeded", false);
            byte[] line = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
            OutputStream out = control.output(); out.write(line); out.flush(); guard.check();
        }
        String line() throws IOException {
            guard.check(); ByteArrayOutputStream b = new ByteArrayOutputStream(); InputStream in = control.input();
            boolean cr = false;
            while (true) {
                int c = in.read(); if (c < 0) throw new IOException("FTP control connection ended before completion");
                if (++totalRead > MAX_CONTROL || b.size() >= MAX_LINE) throw new ProtocolException("FTP response exceeds bound", false);
                if (cr) { if (c != 10) throw new ProtocolException("Malformed FTP line ending", false); return new String(b.toByteArray(), StandardCharsets.US_ASCII); }
                if (c == 13) cr = true;
                else if (c == 10 || c == 0) throw new ProtocolException("Malformed FTP reply", false);
                else b.write(c);
            }
        }
        Reply reply() throws IOException {
            int before = totalRead; String first = line();
            if (!first.matches("[1-5][0-9][0-9][ -].*")) throw new ProtocolException("Malformed FTP status", false);
            int code = Integer.parseInt(first.substring(0, 3)); String last = first;
            if (first.charAt(3) == 45) {
                String end = first.substring(0, 3) + " ";
                do {
                    last = line(); if (totalRead - before > MAX_REPLY) throw new ProtocolException("Multiline FTP response exceeds bound", false);
                } while (!last.startsWith(end));
            }
            guard.check(); return new Reply(code, last);
        }
        void expect(Reply reply, int... codes) throws IOException {
            for (int code : codes) if (reply.code == code) return;
            throw new ProtocolException("FTPS operation rejected with status " + reply.code, reply.code >= 400 && reply.code < 500);
        }
        Connection passive() throws IOException {
            command("EPSV"); Reply r = reply(); expect(r, 229);
            Matcher m = Pattern.compile("^229 [^()\\r\\n]*\\(\\|\\|\\|([0-9]{1,5})\\|\\)[^()\\r\\n]*$").matcher(r.last);
            if (!m.matches()) throw new ProtocolException("Unsupported EPSV response", false);
            int port = Integer.parseInt(m.group(1)); if (port < 1 || port > 65535) throw new ProtocolException("Invalid EPSV port", false);
            guard.check(); Connection data = control.openData(port);
            if (data == null) throw new IOException("Transport returned no data connection");
            return data;
        }
        void store(String name, InputStream source, long expectedBytes, String expectedHash) throws IOException {
            MessageDigest digest = PcmSegmentWriter.sha256(); long copied = 0; byte[] bytes = new byte[BUFFER_BYTES];
            try (Connection data = passive()) {
                command("STOR " + name); expect(reply(), 125, 150); data.secure(host); guard.check(); OutputStream out = data.output();
                while (true) {
                    guard.check(); int n = source.read(bytes); if (n == -1) break;
                    if (n <= 0 || n > expectedBytes - copied) throw new VerificationException("Source size changed during FTPS upload");
                    out.write(bytes, 0, n); digest.update(bytes, 0, n); copied += n;
                }
                if (copied != expectedBytes || !expectedHash.equals(PcmSegmentWriter.hex(digest.digest()))) throw new VerificationException("Source content changed during FTPS upload");
                out.flush(); guard.check();
            }
            expect(reply(), 226, 250); guard.check();
        }
        void readback(String name, long expectedBytes, String expectedHash) throws IOException {
            MessageDigest digest = PcmSegmentWriter.sha256(); long read = 0; byte[] bytes = new byte[BUFFER_BYTES];
            try (Connection data = passive()) {
                command("RETR " + name); expect(reply(), 125, 150); data.secure(host); guard.check(); InputStream in = data.input();
                while (true) {
                    guard.check(); int n = in.read(bytes); if (n == -1) break;
                    if (n <= 0 || n > expectedBytes - read) throw new VerificationException("Remote content byte count mismatch");
                    digest.update(bytes, 0, n); read += n;
                }
            }
            expect(reply(), 226, 250);
            if (read != expectedBytes || !expectedHash.equals(PcmSegmentWriter.hex(digest.digest()))) throw new VerificationException("Remote content verification mismatch");
            guard.check();
        }
    }
    private FtpsTransfer() { }
}
