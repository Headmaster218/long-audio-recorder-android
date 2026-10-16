package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Finite synthetic host checks. No Android runtime, remote destination, or upload is involved. */
public final class ContinuousEnrollmentTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static final DirectorySpool.DirectoryProtocol NORMAL = new DirectorySpool.NioProtocol();
    private static final DirectorySpool.Faults NONE = (op, path) -> { };
    private static final CachePolicy CACHE = new CachePolicy(4 * 1024 * 1024, 0);
    private static final VerifiedExport.Cancellation NEVER = () -> false;

    private static void yes(boolean value, String detail) {
        checks++;
        if (!value) throw new AssertionError("check " + checks + ": " + detail);
    }
    private static void denied(Action action, boolean busy) throws Exception {
        checks++;
        try { action.run(); }
        catch (IOException e) {
            if ((e instanceof DirectorySpool.MaintenanceBusy) == busy) return;
            throw new AssertionError("wrong rejection type", e);
        }
        throw new AssertionError("expected " + (busy ? "temporary busy" : "IOException"));
    }
    private static void invalid(Action action) throws Exception {
        checks++;
        try { action.run(); }
        catch (IllegalArgumentException e) { return; }
        throw new AssertionError("expected invalid arguments");
    }
    private static Path root(String label) throws IOException {
        return Files.createTempDirectory(Paths.get("app/build"), "enrollment-" + label + "-synthetic-").toAbsolutePath();
    }
    private static String source(String capture, long sequence) {
        return "segment-" + SegmentIdentity.capture(capture) + "-" + sequence + ".ready";
    }
    private static CaptureTimeline.Epoch epoch(String capture) {
        return new CaptureTimeline.Epoch(capture, "run", 0, new PcmFormat(16000, 1), "mic", "mic", CaptureTimeline.Gap.START);
    }
    private static Thread start(String name, Action action, AtomicReference<Throwable> error) {
        Thread thread = new Thread(() -> {
            try { action.run(); } catch (Throwable e) { error.compareAndSet(null, e); }
        }, "synthetic-enrollment-" + name);
        thread.setDaemon(true); thread.start(); return thread;
    }
    private static void join(Thread thread) throws Exception {
        thread.join(3000);
        yes(!thread.isAlive(), thread.getName() + " finished within the finite deadline");
    }
    private static void clean(AtomicReference<Throwable> error) {
        checks++;
        if (error.get() != null) throw new AssertionError("background failure", error.get());
    }
    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("synthetic latch timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("synthetic latch interrupted", e);
        }
    }
    private static List<String> ids(FinalizedDiscovery.Page page) {
        return new ArrayList<String>(page.ids);
    }
    private static void pageEquals(FinalizedDiscovery.Page page, List<String> all, Set<String> known, int limit) {
        List<String> unknown = new ArrayList<String>();
        for (String id : all) if (!known.contains(id)) unknown.add(id);
        yes(ids(page).equals(unknown.subList(0, Math.min(limit, unknown.size()))), "exact sorted smallest unknown page");
        yes(page.hasMore == (unknown.size() > limit), "hasMore reflects remaining unknown names");
    }

    private static void pagingAndRestart() throws Exception {
        Path root = root("paging"); List<String> all = new ArrayList<String>();
        // Deliberately reverse creation order. These are empty names-only fixtures, not valid audio.
        for (int i = 600; i >= 0; i--) {
            String id = source("names-only", i); Files.createDirectory(root.resolve(id)); all.add(id);
        }
        Collections.sort(all);
        Files.createDirectory(root.resolve("unrelated.part"));
        Files.createDirectory(root.resolve("wrong.ready"));
        Files.createDirectory(root.resolve(source("names-only", 0).replace("-0.ready", "-00.ready")));
        final Set<String> known = new HashSet<String>();
        pageEquals(FinalizedDiscovery.page(root, known::contains, 4096, 500), all, known, 500);
        pageEquals(FinalizedDiscovery.page(root, known::contains, 4096, 601), all, known, 601);
        pageEquals(FinalizedDiscovery.page(root, known::contains, 4096, 1), all, known, 1);
        AtomicInteger lookups = new AtomicInteger(), cancellationPolls = new AtomicInteger();
        FinalizedDiscovery.Known counted = id -> { lookups.incrementAndGet(); return false; };
        denied(() -> FinalizedDiscovery.page(root, counted, 4096, 64, () -> true), false);
        yes(lookups.get() == 0, "initial cancellation avoids every Known lookup");
        denied(() -> FinalizedDiscovery.page(root, counted, 4096, 64, () -> cancellationPolls.incrementAndGet() >= 32), false);
        yes(cancellationPolls.get() == 32 && lookups.get() <= 30, "mid-scan cancellation bounds subsequent membership work");
        pageEquals(FinalizedDiscovery.page(root, known::contains, 4096, 601), all, known, 601);
        for (String id : all) yes(Files.isDirectory(root.resolve(id)), "cancelled discovery retains every source name");

        // Model a finite 500-row queue: only successful durable admission advances Known.
        int occupied = 0;
        while (occupied < 500) {
            FinalizedDiscovery.Page page = FinalizedDiscovery.page(root, known::contains, 4096, 64);
            pageEquals(page, all, known, 64);
            for (String id : page.ids) {
                if (occupied == 500) break;
                yes(known.add(id), "each admitted name is new"); occupied++;
            }
        }
        yes(known.size() == 500, "queue fills without rejecting a 601-name directory");
        FinalizedDiscovery.Page full = FinalizedDiscovery.page(root, known::contains, 4096, 64);
        pageEquals(full, all, known, 64);
        yes(full.hasMore, "101 names remain beyond the full queue");
        yes(ids(full).equals(ids(FinalizedDiscovery.page(root, known::contains, 4096, 64))), "full admission does not skip names");

        // Restart rebuilds exact durable membership, not a volatile directory cursor.
        final Set<String> restarted = new HashSet<String>(known);
        pageEquals(FinalizedDiscovery.page(root, restarted::contains, 4096, 64), all, restarted, 64);
        yes(ids(full).equals(ids(FinalizedDiscovery.page(root, restarted::contains, 4096, 64))), "restart retries the same first unadmitted names");
        occupied -= 73; // Modeled completed-history capacity release; Known retains the durable record/floor.
        while (occupied < 500) {
            FinalizedDiscovery.Page page = FinalizedDiscovery.page(root, restarted::contains, 4096, 64);
            pageEquals(page, all, restarted, 64);
            for (String id : page.ids) {
                if (occupied == 500) break;
                yes(restarted.add(id), "restart admits each remaining name at most once"); occupied++;
            }
        }
        yes(restarted.size() == 573, "only available slots advance durable membership");
        FinalizedDiscovery.Page last = FinalizedDiscovery.page(root, restarted::contains, 4096, 64);
        pageEquals(last, all, restarted, 64);
        yes(last.ids.size() == 28 && !last.hasMore, "finite tail remains available after another full queue");
        restarted.addAll(last.ids);
        FinalizedDiscovery.Page empty = FinalizedDiscovery.page(root, restarted::contains, 4096, 64);
        yes(empty.ids.isEmpty() && !empty.hasMore, "all 601 durable names are suppressed after restart");

        // Entry bound remains a fail-closed bound even when every candidate is already known.
        for (int i = 604; i < 4096; i++) Files.createFile(root.resolve("ignored-" + i));
        yes(FinalizedDiscovery.page(root, restarted::contains, 4096, 64).ids.isEmpty(), "exactly 4096 entries allowed");
        Files.createFile(root.resolve("entry-4097"));
        denied(() -> FinalizedDiscovery.page(root, restarted::contains, 4096, 64), false);
        denied(() -> FinalizedDiscovery.page(root, id -> false, 4096, 1), false);
        Path missing = root.resolve("absent");
        FinalizedDiscovery.Page absent = FinalizedDiscovery.page(missing, id -> false, 4096, 64);
        yes(absent.ids.isEmpty() && !absent.hasMore, "missing spool is an empty page");
        Path alias = root.resolveSibling(root.getFileName() + "-alias"); Files.createSymbolicLink(alias, root);
        denied(() -> FinalizedDiscovery.page(alias, id -> false, 4096, 64), false);
        invalid(() -> FinalizedDiscovery.page(root, null, 4096, 64));
        invalid(() -> FinalizedDiscovery.page(root, id -> false, 0, 64));
        invalid(() -> FinalizedDiscovery.page(root, id -> false, 4096, 0));
    }

    private static void canonicalConfirmationAndAppend() throws Exception {
        Path root = root("capture"); String capture = "live-capture", id = source(capture, 0);
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(); AtomicInteger validations = new AtomicInteger();
        DirectorySpool.DirectoryProtocol blocked = new DirectorySpool.DirectoryProtocol() {
            public void syncDirectory(Path path) throws IOException {
                NORMAL.syncDirectory(path);
                if (path.equals(root.resolve(id)) && armed.compareAndSet(true, false)) {
                    inside.countDown(); await(release);
                }
            }
            public void atomicPublish(Path from, Path to) throws IOException { NORMAL.atomicPublish(from, to); }
        };
        DirectorySpool.Faults observe = (op, path) -> {
            if (op == DirectorySpool.Operation.VALIDATE && path.equals(root.resolve(id))) validations.incrementAndGet();
        };
        try (DirectorySpool spool = new DirectorySpool(root, CACHE, blocked, observe)) {
            List<SegmentStore.Published> published = new ArrayList<SegmentStore.Published>();
            PcmSegmentWriter writer = new PcmSegmentWriter(spool, epoch(capture), 8192, 0, published::add);
            writer.append(new byte[32784], 0, 32784);
            yes(published.size() == 2, "two actual finalized chunks and another active chunk");
            String active = source(capture, 2).replace(".ready", ".part");
            Path activeWav = root.resolve(active).resolve("audio.wav"); long before = Files.size(activeWav);
            denied(() -> spool.enrollment(source(capture, 2)), false);
            denied(() -> spool.enrollment(active), false);
            int previousValidations = validations.get();
            AtomicReference<Throwable> error = new AtomicReference<Throwable>();
            AtomicReference<SegmentStore.Published> confirmed = new AtomicReference<SegmentStore.Published>();
            try (DirectorySpool.Maintenance lease = spool.enrollment(id)) {
                lease.checkEnrollment(); armed.set(true);
                Thread confirmation = start("confirm", () -> confirmed.set(lease.confirmEnrollment(NEVER)), error);
                Thread append = null;
                try {
                    yes(inside.await(3, TimeUnit.SECONDS), "canonical confirmation reached directory fsync");
                    CountDownLatch appended = new CountDownLatch(1);
                    append = start("append", () -> { writer.append(new byte[16], 0, 16); appended.countDown(); }, error);
                    yes(appended.await(2, TimeUnit.SECONDS), "active PCM append is not blocked by the confirmation monitor");
                    yes(Files.size(activeWav) == before + 16, "active chunk actually grew during confirmation");
                    denied(() -> spool.enrollment(id), true);
                    denied(() -> spool.enrollment(published.get(1).localObjectId), true);
                } finally { release.countDown(); }
                join(confirmation); if (append != null) join(append); clean(error);
                yes(confirmed.get() != null && confirmed.get().localObjectId.equals(id), "confirmation returns the exact leased source");
                yes(Arrays.equals(confirmed.get().metadata.encode(), published.get(0).metadata.encode()), "canonical metadata is preserved");
                yes(validations.get() == previousValidations + 2, "cached publication confirmation never replaces both canonical validations");
                lease.checkEnrollment();
            }

            Path wav = root.resolve(id).resolve("audio.wav"); byte[] original = Files.readAllBytes(wav);
            byte[] intent = Files.readAllBytes(root.resolve(id).resolve("intent.bin"));
            byte[] manifest = Files.readAllBytes(root.resolve(id).resolve("manifest.bin"));
            try (DirectorySpool.Maintenance lease = spool.enrollment(id)) {
                denied(() -> lease.confirmEnrollment(() -> true), false);
                AtomicInteger polls = new AtomicInteger();
                denied(() -> lease.confirmEnrollment(() -> polls.incrementAndGet() >= 4), false);
                yes(polls.get() >= 4, "cancellation occurs during streamed WAV confirmation");
                yes(Arrays.equals(original, Files.readAllBytes(wav)), "cancelled confirmation preserves WAV bytes");
                yes(Arrays.equals(intent, Files.readAllBytes(root.resolve(id).resolve("intent.bin"))), "cancellation preserves intent");
                yes(Arrays.equals(manifest, Files.readAllBytes(root.resolve(id).resolve("manifest.bin"))), "cancellation preserves manifest");
                byte[] corrupt = original.clone(); corrupt[corrupt.length - 1] ^= 1; Files.write(wav, corrupt);
                denied(() -> lease.confirmEnrollment(NEVER), false);
                yes(Arrays.equals(corrupt, Files.readAllBytes(wav)), "a previously confirmed corrupt source is rejected and preserved");
                Files.write(wav, original);
                yes(lease.confirmEnrollment(NEVER).localObjectId.equals(id), "exact source can be reconfirmed after synthetic restoration");
            }
            writer.stop();
        }
    }

    private static List<SegmentStore.Published> seed(Path root, String capture) throws IOException {
        List<SegmentStore.Published> published = new ArrayList<SegmentStore.Published>();
        try (DirectorySpool spool = new DirectorySpool(root, CACHE, NORMAL, NONE)) {
            PcmSegmentWriter writer = new PcmSegmentWriter(spool, epoch(capture), 8, 0, published::add);
            writer.append(new byte[32], 0, 32); writer.stop();
        }
        return published;
    }

    private static void identitiesAndStaleLeases() throws Exception {
        Path root = root("identity"); List<SegmentStore.Published> published = seed(root, "identity");
        String id = published.get(0).localObjectId, other = published.get(1).localObjectId;
        String mismatch = source("different-capture", 0); Files.createDirectory(root.resolve(mismatch));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root.resolve(id))) {
            for (Path file : files) Files.copy(file, root.resolve(mismatch).resolve(file.getFileName()));
        }
        CommittedSegments catalog = new CommittedSegments(root);
        CommittedSegments.Entry otherEntry = null;
        for (CommittedSegments.Entry entry : catalog.page(null).entries) if (entry.id.equals(other)) otherEntry = entry;
        yes(otherEntry != null, "second synthetic source is available for mismatch binding");
        final VerifiedLocalCleanup.Prepared wrongBinding = VerifiedLocalCleanup.prepare(catalog, otherEntry, NEVER);
        DirectorySpool.Maintenance stale;
        try (DirectorySpool spool = new DirectorySpool(root, CACHE, NORMAL, NONE)) {
            denied(() -> spool.enrollment(null), false);
            denied(() -> spool.enrollment("../" + id), false);
            denied(() -> spool.enrollment(id.replace("-0.ready", "-00.ready")), false);
            denied(() -> spool.enrollment(id.replace("-0.ready", "-9223372036854775807.ready")), false);
            denied(() -> spool.enrollment(source("missing", 0)), false);
            try (DirectorySpool.Maintenance lease = spool.enrollment(mismatch)) {
                denied(() -> lease.confirmEnrollment(NEVER), false);
                yes(Files.exists(root.resolve(mismatch).resolve("audio.wav")), "identity-mismatched bytes remain untouched");
            }
            stale = spool.enrollment(id); stale.confirmEnrollment(NEVER);
            denied(() -> stale.quarantine(wrongBinding), false);
            yes(Files.exists(root.resolve(other).resolve("audio.wav")), "exact-object lease rejects another source binding");
            stale.close(); denied(stale::checkEnrollment, false);
            denied(() -> stale.confirmEnrollment(NEVER), false); denied(stale::close, false);
            try (DirectorySpool.Maintenance next = spool.enrollment(other)) {
                denied(stale::checkEnrollment, false);
                yes(next.confirmEnrollment(NEVER).localObjectId.equals(other), "new lease cannot revive the old lease");
            }
            try (DirectorySpool.Maintenance retirement = spool.retirement(id, 2)) {
                denied(retirement::checkEnrollment, false);
                denied(() -> retirement.confirmEnrollment(NEVER), false);
            }
        }
        denied(stale::checkEnrollment, false);
        denied(() -> stale.confirmEnrollment(NEVER), false);
    }

    private static void publicationWindow() throws Exception {
        Path root = root("publication"); String id = source("publication", 0);
        CountDownLatch visible = new CountDownLatch(1), release = new CountDownLatch(1);
        DirectorySpool.DirectoryProtocol blocked = new DirectorySpool.DirectoryProtocol() {
            public void syncDirectory(Path path) throws IOException { NORMAL.syncDirectory(path); }
            public void atomicPublish(Path from, Path to) throws IOException {
                NORMAL.atomicPublish(from, to); visible.countDown(); await(release);
            }
        };
        try (DirectorySpool spool = new DirectorySpool(root, CACHE, blocked, NONE)) {
            AtomicReference<Throwable> error = new AtomicReference<Throwable>();
            AtomicInteger callbacks = new AtomicInteger();
            PcmSegmentWriter writer = new PcmSegmentWriter(spool, epoch("publication"), 8, 0, p -> callbacks.incrementAndGet());
            Thread publish = start("publish", () -> writer.append(new byte[16], 0, 16), error);
            try {
                yes(visible.await(3, TimeUnit.SECONDS), "ready name is visible before publication completes");
                FinalizedDiscovery.Page page = FinalizedDiscovery.page(root, candidate -> false, 4096, 64);
                yes(page.ids.size() == 1 && page.ids.peek().equals(id), "names-only discovery can see the in-flight rename");
                yes(callbacks.get() == 0, "no finalized callback before durability and eligibility");
                denied(() -> spool.enrollment(id), true);
            } finally { release.countDown(); }
            join(publish); clean(error); yes(callbacks.get() == 1, "publication callback follows completed publication");
            try (DirectorySpool.Maintenance lease = spool.enrollment(id)) {
                yes(lease.confirmEnrollment(NEVER).localObjectId.equals(id), "busy source is retryable after publication completes");
            }
            writer.stop();
        }
    }

    private static void stopRetainsOwnership(boolean duringHash) throws Exception {
        Path root = root(duringHash ? "stop-hash" : "stop-fsync"); String capture = "stop", id = source(capture, 0);
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(), closed = new AtomicBoolean(), rejected = new AtomicBoolean();
        AtomicInteger polls = new AtomicInteger(), confirmationSyncs = new AtomicInteger();
        DirectorySpool.DirectoryProtocol blocked = new DirectorySpool.DirectoryProtocol() {
            public void syncDirectory(Path path) throws IOException {
                NORMAL.syncDirectory(path);
                if (path.equals(root.resolve(id)) && armed.compareAndSet(true, false)) {
                    confirmationSyncs.incrementAndGet();
                    if (!duringHash) { inside.countDown(); await(release); }
                }
            }
            public void atomicPublish(Path from, Path to) throws IOException { NORMAL.atomicPublish(from, to); }
        };
        DirectorySpool spool = new DirectorySpool(root, CACHE, blocked, NONE);
        PcmSegmentWriter writer = new PcmSegmentWriter(spool, epoch(capture), 16384, 0, p -> { });
        writer.append(new byte[32784], 0, 32784);
        byte[] original = Files.readAllBytes(root.resolve(id).resolve("audio.wav"));
        DirectorySpool.Maintenance lease = spool.enrollment(id);
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        armed.set(true);
        VerifiedExport.Cancellation caller = () -> {
            if (duringHash && polls.incrementAndGet() == 4) {
                inside.countDown();
                try { await(release); } catch (IOException e) { throw new AssertionError(e); }
            }
            return false;
        };
        Thread confirmation = start("closing-confirm", () -> {
            try { lease.confirmEnrollment(caller); throw new AssertionError("closing owner must reject confirmation result"); }
            catch (DirectorySpool.MaintenanceBusy expected) { rejected.set(true); }
        }, error);
        Thread stop = null;
        try {
            yes(inside.await(3, TimeUnit.SECONDS), "confirmation is active when Stop begins");
            stop = start("stop", () -> { spool.close(); closed.set(true); }, error);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (stop.getState() != Thread.State.WAITING && stop.isAlive() && System.nanoTime() < deadline) Thread.sleep(1);
            yes(stop.getState() == Thread.State.WAITING && !closed.get(), "Stop waits for the enrollment lease");
            denied(lease::checkEnrollment, true);
            denied(() -> lease.confirmEnrollment(NEVER), true);
            denied(() -> spool.enrollment(id), true);
            denied(() -> { try (DirectorySpool second = new DirectorySpool(root, CACHE, NORMAL, NONE)) { second.nextSequence(capture); } }, false);
            release.countDown(); join(confirmation); clean(error);
            yes(rejected.get(), "confirmation finishing after Stop cannot admit a row");
            if (duringHash) {
                yes(polls.get() == 4, "owner closing cooperatively cancels further hash polls without caller cancellation");
                yes(confirmationSyncs.get() == 0, "Stop cancels ongoing hashing before confirmation fsync");
            } else yes(confirmationSyncs.get() == 1, "in-flight platform fsync finishes before cancellation is observed");
            yes(!closed.get(), "finished confirmation still retains ownership until lease close");
            denied(() -> { try (DirectorySpool second = new DirectorySpool(root, CACHE, NORMAL, NONE)) { second.nextSequence(capture); } }, false);
        } finally {
            release.countDown();
            confirmation.join(3000);
            lease.close();
            if (stop != null) stop.join(3000); else spool.close();
        }
        if (stop != null) join(stop); clean(error);
        yes(closed.get(), "Stop completes after enrollment releases ownership");
        denied(lease::checkEnrollment, false);
        yes(Arrays.equals(original, Files.readAllBytes(root.resolve(id).resolve("audio.wav"))), "Stop preserves the confirmed source");
        yes(Files.exists(root.resolve(source(capture, 1).replace(".ready", ".part")).resolve("audio.wav")), "Stop preserves the unrelated active chunk");
        try (DirectorySpool reopened = new DirectorySpool(root, CACHE, NORMAL, NONE)) {
            yes(reopened.nextSequence(capture) == 2, "physical owner is available only after full lease release");
            try (DirectorySpool.Maintenance next = reopened.enrollment(id)) {
                yes(next.confirmEnrollment(NEVER).localObjectId.equals(id), "preserved source is retryable after owner restart");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        pagingAndRestart();
        canonicalConfirmationAndAppend();
        identitiesAndStaleLeases();
        publicationWindow();
        stopRetainsOwnership(false);
        stopRetainsOwnership(true);
        System.out.println("PASS: " + checks + " continuous-enrollment paging/ownership/cancellation assertions; finite synthetic host filesystem only; no Android or uploads");
    }
}
