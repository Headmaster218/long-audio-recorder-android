package io.github.headmaster218.recorder.android;

import android.content.Context;
import android.net.Network;
import android.net.NetworkCapabilities;
import io.github.headmaster218.recorder.core.CachePolicy;
import io.github.headmaster218.recorder.core.CaptureTimeline;
import io.github.headmaster218.recorder.core.CommittedSegments;
import io.github.headmaster218.recorder.core.DirectorySpool;
import io.github.headmaster218.recorder.core.FtpsTransfer;
import io.github.headmaster218.recorder.core.PcmFormat;
import io.github.headmaster218.recorder.core.PcmSegmentWriter;
import io.github.headmaster218.recorder.core.SegmentStore;
import io.github.headmaster218.recorder.core.VerifiedExport;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Host-only wire test. The classpath MUST contain the unchanged compiled production
 * FtpsTransfer and FtpsNetwork. There is no custom TLS implementation or trust manager.
 * Credentials, PCM, and server are synthetic. No cleanup/retirement API is called.
 */
public final class WireClient {
    private static final String REVISION = "wire-fixture-v1";
    private static final int PCM_BYTES = 16384;
    private static final VerifiedExport.Cancellation NEVER_CANCEL = new VerifiedExport.Cancellation() {
        @Override public boolean cancelled() { return false; }
    };
    private WireClient() { }

    public static void main(String[] args) {
        try { execute(args); }
        catch (Throwable failure) {
            // Intentionally omit exception text and stacks: a receipt never logs credentials.
            System.out.println("{\"status\":\"fixture_error\",\"error_class\":" + quote(failure.getClass().getName()) + "}");
            System.exit(1);
        }
    }

    private static void execute(String[] args) throws Exception {
        require(args.length == 3, "Expected scenario, workdir, port");
        String scenario = args[0];
        require(Arrays.asList("success", "untrusted_control", "wrong_host_control", "wrong_data_cert",
            "corrupt_readback", "truncated_readback", "partial_disconnect", "lost_final_reply",
            "stalled_handshake").contains(scenario), "Unknown scenario");
        int port = Integer.parseInt(args[2]);
        require(port > 0 && port <= 65535, "Invalid port");
        Path workdir = Paths.get(args[1]).toAbsolutePath().normalize();
        if (!Files.exists(workdir, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(workdir);
        require(Files.isDirectory(workdir, LinkOption.NOFOLLOW_LINKS)
            && workdir.equals(workdir.toRealPath()), "Workdir must have a canonical non-symlink path");
        Path root = workdir.resolve("spool");
        require(!Files.exists(root, LinkOption.NOFOLLOW_LINKS), "Each case needs a fresh spool");
        CommittedSegments.Entry entry = createSource(root);
        CommittedSegments catalog = new CommittedSegments(root);
        entry = catalog.load(entry.id);
        catalog.verify(entry, NEVER_CANCEL);
        String before = sourceHash(catalog, entry);
        require(before.equals(entry.metadata.wavSha256), "Finalized source hash mismatch");
        require(entry.metadata.wavBytes <= 128 * 1024, "Synthetic WAV exceeds fixture bound");
        String attempt = FtpsTransfer.newAttemptName(entry.id, before, REVISION);
        persistAttempt(workdir, attempt);
        FtpsTransfer.Profile profile = new FtpsTransfer.Profile("127.0.0.1", port, "synthetic", "/fixture");
        List<Attempt> attempts = new ArrayList<Attempt>();
        boolean stalled = "stalled_handshake".equals(scenario);
        Attempt first = transfer(catalog, entry, profile, attempt, false, stalled);
        attempts.add(first);
        FtpsTransfer.Result result = first.result;
        boolean reconcileOnly = false, reconcileFailed = false;
        if ("success".equals(scenario)) {
            require(first.failure == null && result != null, "Success case did not verify");
        } else {
            require(first.failure != null && result == null, "Negative case unexpectedly verified");
            if ("partial_disconnect".equals(scenario) || "lost_final_reply".equals(scenario)) {
                // The original persisted attempt name and source are reused without a single write command.
                // The independent server transcript is the authoritative command-absence assertion.
                reconcileOnly = true;
                Attempt second = transfer(catalog, entry, profile, attempt, true, false);
                attempts.add(second); result = second.result; reconcileFailed = second.failure != null;
                if ("partial_disconnect".equals(scenario))
                    require(reconcileFailed && result == null, "Partial attempt reconciled unexpectedly");
                else require(!reconcileFailed && result != null, "Complete attempt did not reconcile");
            }
        }
        if (result != null) verifyResult(result, entry, attempt);
        catalog.verify(entry, NEVER_CANCEL);
        String after = sourceHash(catalog, entry);
        require(before.equals(after), "Local source changed");
        require(Files.isDirectory(root.resolve(entry.id), LinkOption.NOFOLLOW_LINKS), "Ready source was removed");
        int sockets = 0, connects = 0, registrations = 0, unregistrations = 0;
        for (Attempt current : attempts) {
            sockets += current.socketCount; connects += current.connectionCount;
            registrations += current.registrations; unregistrations += current.unregistrations;
            require(current.allClosed && current.callbacksReleased && current.receiversReleased,
                "Network resources leaked");
        }
        String status = "success".equals(scenario) ? "verified" : result == null ? "expected_failure" : "reconciled";
        String receipt = "{\"scenario\":" + quote(scenario) + ",\"status\":" + quote(status)
            + ",\"attempt\":" + quote(attempt) + ",\"remote_directory\":" + quote("/fixture/" + attempt)
            + ",\"source_id\":" + quote(entry.id) + ",\"source_bytes\":" + entry.metadata.wavBytes
            + ",\"source_hash_before\":" + quote(before) + ",\"source_hash_after\":" + quote(after)
            + ",\"local_unchanged\":true,\"cleanup_invoked\":false,\"attempt_count\":" + attempts.size()
            + ",\"first_failed\":" + (first.failure != null) + ",\"first_error_class\":" + errorClass(first.failure)
            + ",\"reconcile_only\":" + reconcileOnly + ",\"reconcile_failed\":" + reconcileFailed
            + ",\"raw_sockets_created\":" + sockets + ",\"raw_connections_made\":" + connects
            + ",\"all_raw_sockets_closed\":true,\"callback_registrations\":" + registrations
            + ",\"callback_unregistrations\":" + unregistrations
            + ",\"cancel_fired\":" + first.cancelFired + ",\"closed_at_cancel\":" + first.closedAtCancel
            + ",\"first_elapsed_ms\":" + first.elapsedMillis + ",\"cancel_return_ms\":" + first.cancelReturnMillis
            + ",\"result\":" + resultJson(result) + "}";
        require(receipt.length() <= 4096, "Receipt exceeds bound");
        System.out.println(receipt);
    }

    private static CommittedSegments.Entry createSource(Path root) throws IOException {
        final List<SegmentStore.Published> published = new ArrayList<SegmentStore.Published>();
        try (DirectorySpool spool = new DirectorySpool(root, new CachePolicy(1024 * 1024, 0),
                new DirectorySpool.NioProtocol(), new DirectorySpool.Faults() {
                    @Override public void before(DirectorySpool.Operation operation, Path path) { }
                })) {
            CaptureTimeline.Epoch epoch = new CaptureTimeline.Epoch("synthetic-wire-capture", "synthetic-wire-run",
                0, new PcmFormat(16000, 1), "synthetic-input", "synthetic-input", CaptureTimeline.Gap.START);
            PcmSegmentWriter writer = new PcmSegmentWriter(spool, epoch, PCM_BYTES / 2 + 1, 0,
                new PcmSegmentWriter.Listener() {
                    @Override public void published(SegmentStore.Published segment) { published.add(segment); }
                });
            byte[] pcm = new byte[PCM_BYTES];
            for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) ((i * 37 + i / 7 + 11) & 255);
            writer.append(pcm, 0, pcm.length); writer.stop();
        }
        require(published.size() == 1, "Expected exactly one finalized WAV");
        CommittedSegments catalog = new CommittedSegments(root);
        CommittedSegments.Page page = catalog.page(null);
        require(page.entries.size() == 1 && !page.hasMore && page.rejected == 0, "Invalid synthetic catalog");
        return catalog.load(published.get(0).localObjectId);
    }

    private static void persistAttempt(Path workdir, String attempt) throws IOException {
        byte[] bytes = (attempt + "\n").getBytes(StandardCharsets.US_ASCII);
        try (FileChannel channel = FileChannel.open(workdir.resolve("attempt.txt"),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) require(channel.write(buffer) > 0, "Attempt write stalled");
            channel.force(true);
        }
        new DirectorySpool.NioProtocol().syncDirectory(workdir);
    }

    private static Attempt transfer(CommittedSegments catalog, CommittedSegments.Entry entry,
            FtpsTransfer.Profile profile, String attemptName, boolean reconcileOnly, boolean stalled) throws Exception {
        final Context context = new Context();
        final Network wifi = new Network();
        context.connectivity.networks.put(wifi, new NetworkCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED));
        final Attempt outcome = new Attempt();
        long started = System.nanoTime();
        Thread canceller = null;
        char[] password = "synthetic".toCharArray();
        try (final FtpsNetwork network = new FtpsNetwork(context, wifi)) {
            if (stalled) {
                canceller = new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            if (!wifi.awaitConnection(5000)) return;
                            Thread.sleep(300);
                            outcome.cancelAt = System.nanoTime();
                            network.cancel("Synthetic stalled-handshake cancellation");
                            outcome.closedAtCancel = wifi.allSocketsClosed();
                            outcome.cancelFired = true;
                        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                }, "wire-fixture-cancel");
                canceller.setDaemon(true); canceller.start();
            }
            try {
                outcome.result = FtpsTransfer.run(catalog, entry, profile, password, network, REVISION,
                    attemptName, reconcileOnly, network);
            } catch (IOException expected) { outcome.failure = expected; }
            finally { outcome.returnedAt = System.nanoTime(); }
        } finally {
            Arrays.fill(password, '\0');
            if (canceller != null) {
                canceller.join(6000);
                require(!canceller.isAlive(), "Cancellation thread failed to stop");
            }
        }
        outcome.elapsedMillis = TimeUnit.NANOSECONDS.toMillis(outcome.returnedAt - started);
        if (outcome.cancelFired)
            outcome.cancelReturnMillis = TimeUnit.NANOSECONDS.toMillis(outcome.returnedAt - outcome.cancelAt);
        if (stalled) {
            require(outcome.cancelFired && outcome.closedAtCancel, "Actual FtpsNetwork did not close raw socket on cancel");
            require(outcome.cancelReturnMillis >= 0 && outcome.cancelReturnMillis <= 3000
                && outcome.elapsedMillis <= 5000, "Stalled handshake cancellation was not bounded");
        }
        outcome.socketCount = wifi.socketsCreated(); outcome.connectionCount = wifi.connectionsMade();
        outcome.allClosed = wifi.allSocketsClosed();
        outcome.registrations = context.connectivity.registrations;
        outcome.unregistrations = context.connectivity.unregistrations;
        outcome.callbacksReleased = context.connectivity.callbacksReleased();
        outcome.receiversReleased = context.receiversReleased()
            && context.receiverRegistrations == context.receiverUnregistrations;
        return outcome;
    }

    private static void verifyResult(FtpsTransfer.Result result, CommittedSegments.Entry entry, String attempt) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x52465431); out.writeUTF(REVISION); out.writeUTF(entry.id);
        byte[] local = entry.metadata.encode(); out.writeInt(local.length); out.write(local); out.flush();
        byte[] metadata = bytes.toByteArray(); String metadataHash = hash(metadata);
        byte[] marker = ("RECORDER-DELIVERY-V1\n" + attempt + "\n" + REVISION + "\n" + entry.id + "\n"
            + entry.metadata.wavBytes + "\n" + entry.metadata.wavSha256 + "\n" + metadata.length + "\n"
            + metadataHash + "\n").getBytes(StandardCharsets.US_ASCII);
        require(result.verifiedAtTime && result.bytes == entry.metadata.wavBytes
            && result.wavSha256.equals(entry.metadata.wavSha256)
            && result.metadataSha256.equals(metadataHash) && result.markerSha256.equals(hash(marker)),
            "Production Result hash/size mismatch");
    }

    private static String sourceHash(CommittedSegments catalog, CommittedSegments.Entry entry) throws IOException {
        MessageDigest digest = sha256(); byte[] block = new byte[8192]; long size = 0;
        try (InputStream source = catalog.open(entry)) {
            int count;
            while ((count = source.read(block)) != -1) {
                require(count > 0 && size + count <= 128 * 1024, "Source exceeds fixture bound");
                digest.update(block, 0, count); size += count;
            }
        }
        require(size == entry.metadata.wavBytes, "Retained source length mismatch");
        return hex(digest.digest());
    }
    private static String hash(byte[] bytes) { return hex(sha256().digest(bytes)); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray(), encoded = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            encoded[i * 2] = digits[(bytes[i] & 255) >>> 4]; encoded[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(encoded);
    }
    private static String resultJson(FtpsTransfer.Result result) {
        if (result == null) return "null";
        return "{\"verified_at_time\":" + result.verifiedAtTime + ",\"bytes\":" + result.bytes
            + ",\"wav_sha256\":" + quote(result.wavSha256) + ",\"metadata_sha256\":" + quote(result.metadataSha256)
            + ",\"marker_sha256\":" + quote(result.markerSha256) + "}";
    }
    private static String errorClass(IOException failure) { return failure == null ? "null" : quote(failure.getClass().getName()); }
    private static String quote(String value) {
        require(value.matches("[A-Za-z0-9_./$-]*"), "Unexpected receipt text");
        return "\"" + value + "\"";
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static final class Attempt {
        FtpsTransfer.Result result;
        IOException failure;
        int socketCount, connectionCount, registrations, unregistrations;
        boolean allClosed, callbacksReleased, receiversReleased;
        volatile boolean cancelFired, closedAtCancel;
        volatile long cancelAt;
        long returnedAt, elapsedMillis, cancelReturnMillis = -1;
    }
}
