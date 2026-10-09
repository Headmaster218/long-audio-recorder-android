package io.github.headmaster218.recorder.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import io.github.headmaster218.recorder.core.CaptureTimeline.Epoch;
import io.github.headmaster218.recorder.core.CaptureTimeline.Gap;
import io.github.headmaster218.recorder.core.SegmentStore.Published;

/** Synthetic tiny fixtures only. Host logic tests, not SAF/provider/Activity instrumentation. */
public final class ExportTest {
    private static int checks, cases;
    private static final Path BASE = Paths.get("app/build/export-tests/fixtures/run-" + System.nanoTime());
    private static final VerifiedExport.Cancellation NEVER = () -> false;
    private interface Action { void run() throws Exception; }
    private static void yes(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void eq(Object a, Object b) { checks++; if (!a.equals(b)) throw new AssertionError(a + " != " + b + " at " + checks); }
    private static void fails(Action action) throws Exception { checks++; try { action.run(); } catch (IOException | IllegalArgumentException e) { return; } throw new AssertionError("expected rejection " + checks); }
    private static final class Fixture {
        final Path root, wav;
        final CommittedSegments catalog;
        final CommittedSegments.Entry entry;
        final byte[] original;
        Fixture(int frames) throws Exception {
            root = Files.createDirectories(BASE.resolve("case-" + cases++));
            publish(root, frames, 1);
            catalog = new CommittedSegments(root); entry = catalog.page(null).entries.get(0);
            wav = root.resolve(entry.id).resolve("audio.wav"); original = Files.readAllBytes(wav);
        }
        void retained() throws Exception { yes(Files.exists(wav)); eq((long) original.length, Files.size(wav)); }
    }
    private static void publish(Path root, int frames, int count) throws Exception {
        List<Published> items = new ArrayList<Published>();
        try (DirectorySpool spool = new DirectorySpool(root, new CachePolicy(10000000, 0), new DirectorySpool.NioProtocol(), (op, p) -> { })) {
            Epoch epoch = new Epoch("synthetic-capture", "synthetic-run", 0, PcmFormat.DEFAULT, "phone", "phone", Gap.START);
            PcmSegmentWriter writer = new PcmSegmentWriter(spool, epoch, frames, 0, items::add);
            byte[] pcm = new byte[frames * 2]; for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i * 31);
            for (int i = 0; i < count; i++) writer.append(pcm, 0, pcm.length);
            writer.stop();
        }
        eq(count, items.size());
    }
    private static class MemoryTarget implements VerifiedExport.Target {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int writes, reads, maxWrite; boolean closed;
        @Override public OutputStream openWrite() throws IOException {
            writes++;
            return new OutputStream() {
                @Override public void write(int b) { bytes.write(b); }
                @Override public void write(byte[] b, int offset, int length) throws IOException {
                    maxWrite = Math.max(maxWrite, length); bytes.write(b, offset, length);
                }
                @Override public void close() throws IOException { closed = true; }
            };
        }
        @Override public InputStream openRead() throws IOException {
            reads++; yes(closed); return new ByteArrayInputStream(bytes.toByteArray());
        }
    }
    private static VerifiedExport.Result copy(Fixture f, VerifiedExport.Target t) {
        return VerifiedExport.copy(f.catalog, f.entry, t, NEVER);
    }
    private static void happyAndBounded() throws Exception {
        Fixture f = new Fixture(40000); MemoryTarget t = new MemoryTarget();
        VerifiedExport.Result r = copy(f, t);
        eq(VerifiedExport.Status.VERIFIED, r.status); eq((long) f.original.length, r.bytes);
        eq(f.entry.metadata.wavSha256, r.sha256); yes(Arrays.equals(f.original, t.bytes.toByteArray()));
        yes(t.maxWrite <= VerifiedExport.BUFFER_BYTES); eq(1, t.writes); eq(1, t.reads); f.retained();
        eq(f.entry.id.replace(".ready", ".wav"), f.entry.suggestedName());
        Fixture foreign = new Fixture(2); fails(() -> foreign.catalog.open(f.entry));
    }
    private static void writeFailures() throws Exception {
        for (int mode = 0; mode < 5; mode++) {
            final int fault = mode; Fixture f = new Fixture(2);
            MemoryTarget t = new MemoryTarget() {
                @Override public OutputStream openWrite() throws IOException {
                    writes++;
                    if (fault == 0) throw new IOException("open");
                    if (fault == 1) return null;
                    return new OutputStream() {
                        @Override public void write(int b) { bytes.write(b); }
                        @Override public void write(byte[] b, int o, int n) throws IOException {
                            if (fault == 2) { bytes.write(b, o, 2); throw new IOException("mid-write"); }
                            bytes.write(b, o, n);
                        }
                        @Override public void flush() throws IOException { if (fault == 3) throw new IOException("flush"); }
                        @Override public void close() throws IOException { closed = true; if (fault == 4) throw new IOException("close"); }
                    };
                }
            };
            eq(VerifiedExport.Status.FAILED, copy(f, t).status); eq(0, t.reads); f.retained();
        }
    }
    private static void readbackFailures() throws Exception {
        for (int mode = 0; mode < 8; mode++) {
            final int fault = mode; Fixture f = new Fixture(2);
            MemoryTarget t = new MemoryTarget() {
                @Override public InputStream openRead() throws IOException {
                    reads++; yes(closed);
                    if (fault == 0) throw new IOException("unreadable");
                    if (fault == 1) return null;
                    if (fault == 2) throw new SecurityException("revoked");
                    byte[] data = bytes.toByteArray();
                    if (fault == 3) data = Arrays.copyOf(data, data.length - 1);
                    if (fault == 4) data[44] ^= 1;
                    if (fault == 5) data = Arrays.copyOf(data, data.length + 1);
                    final byte[] input = data;
                    if (fault == 6) return new InputStream() { @Override public int read() { return 0; } @Override public int read(byte[] b) { return 0; } };
                    return new FilterInputStream(new ByteArrayInputStream(input)) {
                        @Override public void close() throws IOException { if (fault == 7) throw new IOException("read close"); super.close(); }
                    };
                }
            };
            VerifiedExport.Status expected = fault >= 3 && fault <= 5 ? VerifiedExport.Status.FAILED : VerifiedExport.Status.UNVERIFIED;
            eq(expected, copy(f, t).status); f.retained();
        }
    }
    private static void sourceMutation() throws Exception {
        Fixture before = new Fixture(2); byte[] corrupt = before.original.clone(); corrupt[44] ^= 1;
        Files.write(before.wav, corrupt, StandardOpenOption.TRUNCATE_EXISTING);
        MemoryTarget unopened = new MemoryTarget(); eq(VerifiedExport.Status.FAILED, copy(before, unopened).status); eq(0, unopened.writes); before.retained();
        for (int mode = 0; mode < 3; mode++) {
            final int fault = mode; final Fixture f = new Fixture(2); final FileTime originalTime = Files.getLastModifiedTime(f.wav);
            MemoryTarget target = new MemoryTarget() {
                private void mutate() throws IOException { byte[] data = f.original.clone(); data[44] ^= 1; Files.write(f.wav, data, StandardOpenOption.TRUNCATE_EXISTING); Files.setLastModifiedTime(f.wav, originalTime); }
                @Override public OutputStream openWrite() throws IOException {
                    if (fault == 0) mutate();
                    OutputStream output = super.openWrite();
                    return new java.io.FilterOutputStream(output) {
                        @Override public void close() throws IOException { super.close(); if (fault == 1) mutate(); }
                    };
                }
                @Override public InputStream openRead() throws IOException { InputStream in = super.openRead(); if (fault == 2) mutate(); return in; }
            };
            eq(VerifiedExport.Status.FAILED, copy(f, target).status); f.retained();
        }
        Fixture metadata = new Fixture(2); Files.write(metadata.root.resolve(metadata.entry.id).resolve("manifest.bin"), new byte[10]);
        eq(VerifiedExport.Status.FAILED, copy(metadata, new MemoryTarget()).status); metadata.retained();
    }
    private static void allowlistAndPaging() throws Exception {
        Fixture f = new Fixture(2); Path directory = f.root.resolve(f.entry.id);
        fails(() -> f.catalog.page("../outside.ready")); fails(() -> f.catalog.page(f.entry.id.replace(".ready", ".part")));
        Path parent = f.root.toAbsolutePath().getParent(); Path linked = parent.resolve("root-link-" + cases++);
        Files.createSymbolicLink(linked, f.root.toAbsolutePath()); fails(() -> new CommittedSegments(linked).page(null));
        for (String filename : new String[] {"audio.wav", "intent.bin", "manifest.bin"}) {
            Fixture link = new Fixture(2); Path file = link.root.resolve(link.entry.id).resolve(filename);
            Path moved = link.root.resolve("kept-" + filename); Files.move(file, moved); Files.createSymbolicLink(file, moved.toAbsolutePath());
            eq(0, link.catalog.page(null).entries.size()); eq(VerifiedExport.Status.FAILED, copy(link, new MemoryTarget()).status); yes(Files.exists(moved));
        }
        Fixture objectLink = new Fixture(2); Path object = objectLink.root.resolve(objectLink.entry.id), kept = objectLink.root.resolve("kept-directory");
        Files.move(object, kept); Files.createSymbolicLink(object, kept.toAbsolutePath()); eq(0, objectLink.catalog.page(null).entries.size());
        Fixture partial = new Fixture(2); Files.move(partial.root.resolve(partial.entry.id), partial.root.resolve(partial.entry.id.replace(".ready", ".part")));
        eq(0, partial.catalog.page(null).entries.size()); eq(VerifiedExport.Status.FAILED, copy(partial, new MemoryTarget()).status);
        Files.write(directory.resolve("unexpected"), new byte[] {1}); eq(0, f.catalog.page(null).entries.size());
        Path many = Files.createDirectories(BASE.resolve("case-" + cases++)); publish(many, 1, 103);
        CommittedSegments catalog = new CommittedSegments(many); CommittedSegments.Page page = catalog.page(null);
        eq(100, page.entries.size()); yes(page.hasMore); Set<String> ids = new HashSet<String>();
        for (CommittedSegments.Entry e : page.entries) yes(ids.add(e.id));
        page = catalog.page(page.cursor()); eq(3, page.entries.size()); yes(!page.hasMore);
        for (CommittedSegments.Entry e : page.entries) yes(ids.add(e.id)); eq(103, ids.size());
        eq(0, catalog.page(page.cursor()).entries.size());
        eq(0, new CommittedSegments(BASE.resolve("does-not-exist")).page(null).entries.size());
    }
    private static void cancellation() throws Exception {
        Fixture f = new Fixture(2); MemoryTarget untouched = new MemoryTarget();
        eq(VerifiedExport.Status.CANCELLED, VerifiedExport.copy(f.catalog, f.entry, untouched, () -> true).status); eq(0, untouched.writes);
        for (int mode = 0; mode < 3; mode++) {
            final int fault = mode; final boolean[] cancel = {false};
            MemoryTarget target = new MemoryTarget() {
                @Override public OutputStream openWrite() throws IOException {
                    if (fault == 0) cancel[0] = true;
                    OutputStream out = super.openWrite();
                    return new java.io.FilterOutputStream(out) {
                        @Override public void close() throws IOException { super.close(); if (fault == 1) cancel[0] = true; }
                    };
                }
                @Override public InputStream openRead() throws IOException { if (fault == 2) cancel[0] = true; return super.openRead(); }
            };
            eq(VerifiedExport.Status.CANCELLED, VerifiedExport.copy(f.catalog, f.entry, target, () -> cancel[0]).status); f.retained();
        }
    }
    private static void singleFlightAndRecreation() throws Exception {
        Fixture f = new Fixture(2); CommittedSegments.Page page = f.catalog.page(null); ExportSession state = new ExportSession();
        yes(state.choose(0) == null); String listing = state.list(); yes(listing != null); yes(state.list() == null);
        state.listed("stale", page, null); eq(ExportSession.Phase.LISTING, state.snapshot().phase);
        state.listed(listing, page, null); yes(state.choose(-1) == null); yes(state.choose(1) == null);
        String choosing = state.choose(0); yes(state.choose(0) == null); yes(state.list() == null);
        ExportSession recreatedControllerReference = state; eq(choosing, recreatedControllerReference.snapshot().token);
        yes(state.destination("stale") == null); state.pickerCancelled("stale", "stale"); eq(ExportSession.Phase.CHOOSING, state.snapshot().phase);
        state.pickerCancelled(choosing, "cancelled"); eq(ExportSession.Phase.CANCELLED, state.snapshot().phase); yes(state.destination(choosing) == null);
        String retry = state.choose(0); yes(!retry.equals(choosing)); yes(state.destination(choosing) == null);
        yes(recreatedControllerReference.destination(retry) == page.entries.get(0)); yes(state.destination(retry) == null);
        yes(state.choose(0) == null); yes(state.list() == null); state.cancelCopy(); state.cancelCopy(); yes(state.cancelled(retry));
        VerifiedExport.Result result = copy(f, new MemoryTarget()); state.finished(choosing, result); eq(ExportSession.Phase.COPYING, state.snapshot().phase);
        state.finished(retry, result); eq(ExportSession.Phase.VERIFIED, state.snapshot().phase); yes(state.choose(0) != null);
        ExportSession newProcess = new ExportSession(); yes(newProcess.destination(retry) == null); eq(ExportSession.Phase.IDLE, newProcess.snapshot().phase);
    }
    private static void pickerTickets() throws Exception {
        ExportPickerTicket original = new ExportPickerTicket();
        int a = original.issue("a"); yes(a >= 10000); yes(original.consume(1) == null);
        ExportPickerTicket rotated = new ExportPickerTicket(original.code(), original.token());
        eq("a", rotated.consume(a)); yes(rotated.consume(a) == null);
        int b = rotated.issue("b"); yes(b > a); yes(rotated.consume(a) == null); eq("b", rotated.consume(b));
        int c = rotated.issue("c"); rotated.forgetToken(); yes(rotated.consume(c) == null);
        ExportPickerTicket processRestart = new ExportPickerTicket(rotated.code(), null);
        int d = processRestart.issue("d"); yes(d > c); yes(processRestart.consume(c) == null); eq("d", processRestart.consume(d));
        ExportPickerTicket exhausted = new ExportPickerTicket(65535, null); eq(-1, exhausted.issue("e"));
        fails(() -> new ExportPickerTicket(1, "bad"));
        eq(-1, original.issue(null));
    }
    public static void main(String[] args) throws Exception {
        happyAndBounded(); writeFailures(); readbackFailures(); sourceMutation(); allowlistAndPaging(); cancellation(); singleFlightAndRecreation(); pickerTickets();
        System.out.println("PASS export: " + checks + " assertions across streaming, target failures, mutation, allowlist, pagination, cancellation and single-flight/recreation model cases");
        System.out.println("Host synthetic tests only. Android picker/grants/Activity/provider/process-death runtime remain untested.");
    }
}
