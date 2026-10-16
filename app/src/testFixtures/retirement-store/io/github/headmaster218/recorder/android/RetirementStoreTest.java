package io.github.headmaster218.recorder.android;

import android.content.Context;
import io.github.headmaster218.recorder.core.CachePolicy;
import io.github.headmaster218.recorder.core.CaptureTimeline;
import io.github.headmaster218.recorder.core.CommittedSegments;
import io.github.headmaster218.recorder.core.DirectorySpool;
import io.github.headmaster218.recorder.core.PcmFormat;
import io.github.headmaster218.recorder.core.PcmSegmentWriter;
import io.github.headmaster218.recorder.core.RetirementSentinel;
import io.github.headmaster218.recorder.core.SegmentIdentity;
import io.github.headmaster218.recorder.core.VerifiedLocalCleanup;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Actual RetirementStore + actual core filesystem code; modeled database and Android ports. */
public final class RetirementStoreTest {
    private static final String STORE = "00000000-0000-4000-8000-000000000001";
    private static final String OTHER = "00000000-0000-4000-8000-000000000002";
    private static final CachePolicy CACHE = new CachePolicy(1024 * 1024, 0);
    private static final DirectorySpool.DirectoryProtocol NORMAL = new DirectorySpool.NioProtocol();
    private static final DirectorySpool.Faults NO_FAULT = (operation, path) -> { };
    private static final List<Path> ROOTS = new ArrayList<Path>();
    private static int checks, cases, serial;
    private interface Action { void run() throws Exception; }

    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static IOException fails(String messagePart, Action action) throws Exception {
        checks++;
        try { action.run(); }
        catch (IOException e) {
            if (!e.getMessage().contains(messagePart))
                throw new AssertionError("Wrong rejection: expected " + messagePart + ", got " + e, e);
            return e;
        }
        throw new AssertionError("Expected rejection: " + messagePart);
    }
    private static void run(String name, Action action) throws Exception {
        try { action.run(); cases++; }
        catch (Exception | AssertionError e) { throw new AssertionError("Case failed: " + name, e); }
    }

    private static final class Fixture {
        final Path root;
        final String capture;
        final List<VerifiedLocalCleanup.Prepared> prepared = new ArrayList<VerifiedLocalCleanup.Prepared>();
        Fixture(int segments) throws Exception {
            root = Files.createTempDirectory(Paths.get("app/build"), "retirement-store-synthetic-").toAbsolutePath();
            ROOTS.add(root); capture = "synthetic-retirement-store-" + serial++;
            if (segments == 0) return;
            // Generate tiny real committed recordings, then preserve their original directory
            // inodes through the real prepare -> quarantine -> purge -> retired sequence.
            try (DirectorySpool spool = new DirectorySpool(root, CACHE, NORMAL, NO_FAULT)) {
                CaptureTimeline.Epoch epoch = new CaptureTimeline.Epoch(capture, "synthetic-run", 0,
                    new PcmFormat(16000, 1), "synthetic-mic", "synthetic-mic", CaptureTimeline.Gap.START);
                PcmSegmentWriter writer = new PcmSegmentWriter(spool, epoch, 8, 0, published -> { });
                byte[] pcm = new byte[segments * 16]; writer.append(pcm, 0, pcm.length); writer.stop();
                CommittedSegments catalog = new CommittedSegments(root);
                for (CommittedSegments.Entry entry : catalog.page(null).entries)
                    prepared.add(VerifiedLocalCleanup.prepare(catalog, entry, () -> false, new AndroidCleanupIdentity()));
                check(prepared.size() == segments, "Synthetic recording count");
                for (VerifiedLocalCleanup.Prepared binding : prepared) {
                    spool.quarantineVerified(binding); spool.purgeQuarantine(binding);
                    check(VerifiedLocalCleanup.verifyRetired(root, binding, false), "Original empty marker exists");
                }
            }
        }
        Path marker(int index) { return root.resolve(prepared.get(index).source.replace(".ready", ".retired")); }
        void witness(String store, long generation) throws IOException {
            RetirementSentinel.write(root, new RetirementSentinel.State(store, generation), NORMAL);
        }
        FtpsQueue.Model model(long generation, boolean ready) { return new FtpsQueue.Model(STORE, generation, ready); }
        void row(FtpsQueue.Model model, int index, long generation) throws IOException {
            VerifiedLocalCleanup.Prepared binding = prepared.get(index);
            model.rows.add(new FtpsQueue.Item(binding.source, generation, "REMOVED", binding.encode()));
            SegmentIdentity id = SegmentIdentity.parse(binding.source);
            Long previous = model.floors.get(id.capture);
            model.floors.put(id.capture, Math.max(previous == null ? 0 : previous, id.sequence + 1));
            model.hasRanges = true;
        }
    }

    private enum Failure { NONE, BEFORE_ROOT_FSYNC, AFTER_ROOT_FSYNC }
    private static final class Protocol implements DirectorySpool.DirectoryProtocol {
        final Fixture fixture;
        final FtpsQueue.Model model;
        Failure failure = Failure.NONE;
        int attempts, completed;
        Protocol(Fixture fixture, FtpsQueue.Model model) { this.fixture = fixture; this.model = model; }
        @Override public void syncDirectory(Path path) throws IOException {
            // The database port is open only inside the real initializer. The spool's earlier
            // lock/root/parent synchronization remains real and does not consume this fault.
            boolean reconciliation = model.open && path.equals(fixture.root);
            if (reconciliation) {
                attempts++; model.events.add("root-before");
                if (failure == Failure.BEFORE_ROOT_FSYNC) {
                    failure = Failure.NONE; throw new IOException("Synthetic before root fsync");
                }
            }
            NORMAL.syncDirectory(path);
            if (reconciliation) {
                completed++; model.events.add("root-after");
                if (failure == Failure.AFTER_ROOT_FSYNC) {
                    failure = Failure.NONE; throw new IOException("Synthetic after root fsync");
                }
            }
        }
        @Override public void atomicPublish(Path from, Path to) throws IOException { NORMAL.atomicPublish(from, to); }
    }

    private static void admit(Fixture fixture, FtpsQueue.Model model, Protocol protocol) throws Exception {
        try (DirectorySpool spool = new DirectorySpool(fixture.root, CACHE, protocol, NO_FAULT,
                new RetirementStore(new Context(model)))) {
            if (!fixture.prepared.isEmpty())
                check(spool.nextSequence(fixture.capture) >= fixture.prepared.size(), "Admitted sequence floor");
        }
    }
    private static void witnessIs(Fixture fixture, String store, long generation) throws IOException {
        check(new RetirementSentinel.State(store, generation).equals(RetirementSentinel.read(fixture.root)), "Witness state");
    }
    private static void synchronizeOwned(Fixture fixture, FtpsQueue.Model model, Protocol protocol) throws Exception {
        try (FileChannel channel = FileChannel.open(fixture.root.resolve(".writer.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FileLock lock = channel.lock(); FtpsQueue queue = new FtpsQueue(new Context(model))) {
            check(lock.isValid(), "Direct reconciliation owns physical writer lock");
            RetirementStore.synchronize(queue, fixture.root, protocol);
        }
    }
    private static void rejectedWithoutPublication(Fixture fixture, FtpsQueue.Model model,
            Protocol protocol, long oldGeneration, String message) throws Exception {
        fails(message, () -> admit(fixture, model, protocol));
        witnessIs(fixture, STORE, oldGeneration);
        check(protocol.attempts == 0 && model.floorCalls == 0, "No witness sync or admission floors after rejection");
        check(!model.open && model.closeCalls == 1, "Database port closed after rejection");
    }

    private static void initializingReplay(boolean mainPresent, boolean tempPresent) throws Exception {
        Fixture fixture = new Fixture(0); FtpsQueue.Model model = fixture.model(0, false);
        if (mainPresent || tempPresent) fixture.witness(STORE, 0);
        if (tempPresent) Files.move(fixture.root.resolve(RetirementSentinel.FILE_NAME),
            fixture.root.resolve(RetirementSentinel.TEMP_FILE_NAME));
        Protocol protocol = new Protocol(fixture, model); admit(fixture, model, protocol);
        witnessIs(fixture, STORE, 0);
        check(model.meta.ready && model.readyCalls == 1, "Initializing state marked ready once");
        check(protocol.attempts == 1 && protocol.completed == 1, "Initialization sync completed");
        check(model.events.indexOf("root-after") < model.events.indexOf("ready"), "Root fsync precedes READY");
        check(model.events.indexOf("ready") < model.events.indexOf("floors"), "READY precedes floor admission");
        check(!Files.exists(fixture.root.resolve(RetirementSentinel.TEMP_FILE_NAME)), "Exact temp replay published");
    }

    private static void initializingAmbiguity(int kind) throws Exception {
        Fixture fixture = new Fixture(0); FtpsQueue.Model model = fixture.model(kind == 0 ? 1 : 0, false);
        if (kind == 1) model.hasRanges = true;
        if (kind == 2) model.floors.put(repeat('a', 64), 1L);
        if (kind == 3) fixture.witness(STORE, 1);
        Protocol protocol = new Protocol(fixture, model);
        fails("ambiguous retirement initialization", () -> admit(fixture, model, protocol));
        check(!model.meta.ready && model.readyCalls == 0 && model.floorCalls == 0, "Ambiguous initialization remains unadmitted");
        check(protocol.attempts == 0, "Ambiguous initialization does not publish");
        if (kind == 3) witnessIs(fixture, STORE, 1);
        else check(RetirementSentinel.read(fixture.root) == null, "Missing main remains missing");
    }

    private static void readyMissing(boolean retainedTemp) throws Exception {
        Fixture fixture = new Fixture(0); FtpsQueue.Model model = fixture.model(0, true);
        Path temp = fixture.root.resolve(RetirementSentinel.TEMP_FILE_NAME);
        if (retainedTemp) {
            fixture.witness(STORE, 0);
            Files.move(fixture.root.resolve(RetirementSentinel.FILE_NAME), temp);
        }
        byte[] pending = retainedTemp ? Files.readAllBytes(temp) : null;
        Protocol protocol = new Protocol(fixture, model);
        fails("missing sentinel", () -> admit(fixture, model, protocol));
        check(RetirementSentinel.read(fixture.root) == null && protocol.attempts == 0, "READY cannot recreate missing main");
        check(model.floorCalls == 0 && model.readyCalls == 0, "Missing witness blocks admission");
        if (retainedTemp) check(Arrays.equals(pending, Files.readAllBytes(temp)), "READY preserves exact temp when main is missing");
    }

    private static void uuidMismatch(boolean recreated) throws Exception {
        Fixture fixture = new Fixture(0); fixture.witness(STORE, 0);
        FtpsQueue.Model model = new FtpsQueue.Model(OTHER, 0, !recreated);
        Protocol protocol = new Protocol(fixture, model);
        rejectedWithoutPublication(fixture, model, protocol, 0, "UUID mismatch");
        check(model.boundsCalls == 0 && model.readyCalls == 0, "UUID rejected before database bounds or READY");
    }

    private static void boundedAdvance() throws Exception {
        Fixture fixture = new Fixture(FtpsQueue.MAX_RETIRING); fixture.witness(STORE, 3);
        FtpsQueue.Model model = fixture.model(3 + FtpsQueue.MAX_RETIRING, true);
        for (int i = 0; i < fixture.prepared.size(); i++) fixture.row(model, i, 4 + i);
        Protocol protocol = new Protocol(fixture, model); admit(fixture, model, protocol);
        witnessIs(fixture, STORE, 3 + FtpsQueue.MAX_RETIRING);
        check(model.validatedSources.size() == FtpsQueue.MAX_RETIRING, "Every pending source reaches database proof gate");
        check(model.events.indexOf("bounds") < model.events.indexOf("pending"), "History validated before pending rows");
        int publication = model.events.indexOf("root-before");
        for (VerifiedLocalCleanup.Prepared binding : fixture.prepared) {
            check(model.events.indexOf("proof:" + binding.source) < publication, "Proof gate precedes witness publication");
            check(VerifiedLocalCleanup.verifyRetired(fixture.root, binding, false), "Synchronization preserves each original marker");
        }
        check(model.rows.size() == FtpsQueue.MAX_RETIRING && protocol.completed == 1, "Synchronization neither prunes nor deletes rows");
    }

    private static void missingGeneration(boolean lateGap) throws Exception {
        Fixture fixture = new Fixture(1); fixture.witness(STORE, 0);
        FtpsQueue.Model model = fixture.model(2, true); fixture.row(model, 0, lateGap ? 1 : 2);
        Protocol protocol = new Protocol(fixture, model);
        rejectedWithoutPublication(fixture, model, protocol, 0, "missing committed generation evidence");
        check(Files.isDirectory(fixture.marker(0)), "Gap preserves existing marker");
    }

    private static void markerRejected(String kind) throws Exception {
        Fixture fixture = new Fixture(1); fixture.witness(STORE, 0);
        FtpsQueue.Model model = fixture.model(1, true); fixture.row(model, 0, 1);
        Path marker = fixture.marker(0); String message;
        if (kind.equals("absent")) { Files.delete(marker); message = "missing retired marker"; }
        else if (kind.equals("replacement")) {
            Object oldKey = Files.readAttributes(marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            Files.move(marker, fixture.root.resolve("saved-original-marker")); Files.createDirectory(marker);
            Object newKey = Files.readAttributes(marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            check(!oldKey.equals(newKey), "Replacement test must use a different live inode");
            message = "Cleanup identity changed";
        } else if (kind.equals("nonempty")) {
            Files.write(marker.resolve("unexpected"), new byte[] { 1 }); message = "retired marker is not empty";
        } else {
            Files.createDirectory(fixture.root.resolve(fixture.prepared.get(0).source.replace(".ready", "." + kind)));
            message = "live/partial source blocks retirement";
        }
        Protocol protocol = new Protocol(fixture, model);
        if (kind.equals("ready") || kind.equals("part") || kind.equals("quarantine")) {
            // Admission now detects duplicate reservations before invoking the initializer.
            // Independently exercise the reconciler's own refusal while holding the same lock.
            fails("duplicate spool reservation", () -> admit(fixture, model, protocol));
            check(model.closeCalls == 0 && model.validatedSources.isEmpty(), "Admission census rejects before opening DB");
            final String expected = message;
            fails(expected, () -> synchronizeOwned(fixture, model, protocol));
            witnessIs(fixture, STORE, 0);
            check(protocol.attempts == 0 && model.floorCalls == 0, "Conflicting names cannot publish or admit");
        } else rejectedWithoutPublication(fixture, model, protocol, 0, message);
        check(model.validatedSources.size() == 1, "Local marker check follows database proof gate");
        if (!kind.equals("absent")) check(Files.isDirectory(marker), "Rejected marker preserved");
    }

    private static void equalGenerationAbsent() throws Exception {
        Fixture fixture = new Fixture(1); fixture.witness(STORE, 1);
        FtpsQueue.Model model = fixture.model(1, true); fixture.row(model, 0, 1);
        Files.delete(fixture.marker(0));
        Protocol protocol = new Protocol(fixture, model); admit(fixture, model, protocol);
        witnessIs(fixture, STORE, 1);
        check(model.validatedSources.size() == 1 && protocol.completed == 1, "Equal generation validates DB then resyncs");
        check(model.rows.size() == 1 && !Files.exists(fixture.marker(0)), "Already-absent marker accepted without finishing pending rows");
        check(model.readyCalls == 0, "READY replay does not initialize again");
    }

    private static void malformedBinding() throws Exception {
        Fixture fixture = new Fixture(1); fixture.witness(STORE, 0);
        FtpsQueue.Model model = fixture.model(1, true); fixture.row(model, 0, 1);
        FtpsQueue.Item row = model.rows.remove(0);
        model.rows.add(new FtpsQueue.Item(row.source, 1, "REMOVED", new byte[] { 0, 0, 0, 2 }));
        Protocol protocol = new Protocol(fixture, model);
        rejectedWithoutPublication(fixture, model, protocol, 0, "Unsupported cleanup binding");
        check(Files.isDirectory(fixture.marker(0)), "Bad binding preserves marker");
    }

    private static String repeat(char c, int count) { char[] result = new char[count]; Arrays.fill(result, c); return new String(result); }
    private static String source(int sequence) { return "segment-" + repeat('a', 64) + "-" + sequence + ".ready"; }
    private static void pendingRejected(String kind) throws Exception {
        Fixture fixture = new Fixture(0); fixture.witness(STORE, 1);
        FtpsQueue.Model model = fixture.model(1, true); String message = "missing/bounded retirement proof";
        if (kind.equals("zero")) {
            model.rows.add(new FtpsQueue.Item(source(0), 0, "REMOVED", new byte[] { 1 })); message = "invalid pending generation";
        } else if (kind.equals("split")) {
            model.rows.add(new FtpsQueue.Item(source(0), 1, "REMOVED", new byte[] { 1 }));
            model.rows.add(new FtpsQueue.Item(source(0), 2, "NONE", null)); message = "split retirement intent";
        } else if (kind.equals("missing")) model.rows.add(new FtpsQueue.Item(source(0), 1, "NONE", null));
        else if (kind.equals("null")) model.rows.add(new FtpsQueue.Item(source(0), 1, "REMOVED", null));
        else for (int i = 0; i <= FtpsQueue.MAX_RETIRING; i++)
            model.rows.add(new FtpsQueue.Item(source(i), 1, "REMOVED", new byte[] { 1 }));
        Protocol protocol = new Protocol(fixture, model);
        rejectedWithoutPublication(fixture, model, protocol, 1, message);
        check(model.validatedSources.isEmpty(), "Structural pending rejection precedes proof gate");
    }

    private static void interruptedAdmission(Failure failure, boolean initializing) throws Exception {
        Fixture fixture = new Fixture(initializing ? 0 : 1);
        FtpsQueue.Model model = fixture.model(initializing ? 0 : 1, !initializing);
        if (!initializing) { fixture.witness(STORE, 0); fixture.row(model, 0, 1); }
        Protocol protocol = new Protocol(fixture, model); protocol.failure = failure;
        fails(failure == Failure.BEFORE_ROOT_FSYNC ? "before root fsync" : "after root fsync",
            () -> admit(fixture, model, protocol));
        witnessIs(fixture, STORE, initializing ? 0 : 1); // Rename has occurred; success is still not acknowledged.
        check(model.floorCalls == 0 && model.readyCalls == 0, "Failed synchronization never admits floors or commits READY");
        check(!model.open && model.closeCalls == 1, "Failed admission closes database owner");
        check(protocol.attempts == 1, "Fault reached only witness root sync");
        check(protocol.completed == (failure == Failure.AFTER_ROOT_FSYNC ? 1 : 0), "Fault injection straddles actual root fsync");
        if (initializing) check(!model.meta.ready, "Initializing database remains replayable");
        Object witnessKey = Files.readAttributes(fixture.root.resolve(RetirementSentinel.FILE_NAME), BasicFileAttributes.class).fileKey();
        admit(fixture, model, protocol); // Also proves the failed DirectorySpool constructor released its physical lock.
        check(protocol.attempts == 2, "Same-state replay performs root fsync again");
        check(protocol.completed == (failure == Failure.AFTER_ROOT_FSYNC ? 2 : 1), "Replay completes root fsync");
        check(witnessKey.equals(Files.readAttributes(fixture.root.resolve(RetirementSentinel.FILE_NAME), BasicFileAttributes.class).fileKey()),
            "Exact-state replay forces existing witness rather than replacing it");
        check(model.floorCalls == 1 && model.closeCalls == 2 && model.meta.ready, "Only replay admits floors and closes owner");
        if (initializing) check(model.readyCalls == 1, "READY follows successful replay");
        else check(model.validatedSources.size() == 2 && Files.isDirectory(fixture.marker(0)), "Replay revalidates pending proof and retains marker");
    }

    private static void uncertainSentinel(String kind) throws Exception {
        Fixture fixture = new Fixture(0); fixture.witness(STORE, 0);
        FtpsQueue.Model model = fixture.model(0, true);
        Path main = fixture.root.resolve(RetirementSentinel.FILE_NAME);
        Path temp = fixture.root.resolve(RetirementSentinel.TEMP_FILE_NAME);
        String message;
        if (kind.equals("main")) { Files.write(main, new byte[] { 1 }); message = "Invalid retirement witness length"; }
        else if (kind.equals("malformed-temp")) { Files.write(temp, new byte[] { 1 }); message = "Invalid retirement witness length"; }
        else if (kind.equals("same-temp")) { Files.copy(main, temp); message = null; }
        else {
            Fixture other = new Fixture(0); other.witness(STORE, 1);
            Files.copy(other.root.resolve(RetirementSentinel.FILE_NAME), temp, StandardCopyOption.COPY_ATTRIBUTES);
            message = "Uncertain retirement temp state";
        }
        byte[] oldMain = Files.readAllBytes(main);
        byte[] oldTemp = Files.exists(temp) ? Files.readAllBytes(temp) : null;
        Protocol protocol = new Protocol(fixture, model);
        if (message == null) {
            admit(fixture, model, protocol); witnessIs(fixture, STORE, 0);
            check(protocol.completed == 1 && !Files.exists(temp), "Exact temp replay completes publication");
        } else {
            final String expected = message; fails(expected, () -> admit(fixture, model, protocol));
            check(Arrays.equals(oldMain, Files.readAllBytes(main)), "Uncertain main bytes preserved");
            if (oldTemp != null) check(Arrays.equals(oldTemp, Files.readAllBytes(temp)), "Uncertain temp bytes preserved");
            check(protocol.attempts == 0 && model.floorCalls == 0, "Uncertain sentinel cannot admit recording");
        }
    }

    private static void cleanSyntheticRoots() throws IOException {
        for (Path root : ROOTS) {
            try (Stream<Path> paths = Files.walk(root)) {
                Path[] ordered = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
                for (Path path : ordered) Files.delete(path);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            run("initializing UUID committed, main missing", () -> initializingReplay(false, false));
            run("initializing same main replay", () -> initializingReplay(true, false));
            run("initializing exact pending temp replay", () -> initializingReplay(false, true));
            for (int kind = 0; kind < 4; kind++) { final int value = kind; run("ambiguous initialization " + kind, () -> initializingAmbiguity(value)); }
            run("ready main missing", () -> readyMissing(false));
            run("ready main missing with exact retained temp", () -> readyMissing(true));
            run("ready UUID mismatch", () -> uuidMismatch(false));
            run("recreated database UUID mismatch", () -> uuidMismatch(true));
            run("database older than witness", () -> {
                Fixture fixture = new Fixture(0); fixture.witness(STORE, 2);
                FtpsQueue.Model model = fixture.model(1, true);
                rejectedWithoutPublication(fixture, model, new Protocol(fixture, model), 2, "database older than spool");
            });
            run("database history validation failure", () -> {
                Fixture fixture = new Fixture(0); fixture.witness(STORE, 0);
                FtpsQueue.Model model = fixture.model(0, true); model.boundsRejected = true;
                rejectedWithoutPublication(fixture, model, new Protocol(fixture, model), 0, "history gate rejected");
                check(!model.events.contains("pending"), "Invalid history rejected before pending rows");
            });
            run("database ahead at exact recovery bound", RetirementStoreTest::boundedAdvance);
            run("database ahead missing first generation", () -> missingGeneration(false));
            run("database ahead missing later generation", () -> missingGeneration(true));
            run("database ahead above recovery bound", () -> {
                Fixture fixture = new Fixture(0); fixture.witness(STORE, 0);
                FtpsQueue.Model model = fixture.model(FtpsQueue.MAX_RETIRING + 1, true);
                rejectedWithoutPublication(fixture, model, new Protocol(fixture, model), 0, "unbounded generation recovery");
            });
            for (String kind : new String[] { "absent", "replacement", "nonempty", "ready", "part", "quarantine" })
                run("database ahead marker " + kind, () -> markerRejected(kind));
            run("database ahead malformed binding", RetirementStoreTest::malformedBinding);
            run("equal generation with already-absent marker", RetirementStoreTest::equalGenerationAbsent);
            for (String kind : new String[] { "zero", "split", "missing", "null", "cap" })
                run("pending structure " + kind, () -> pendingRejected(kind));
            run("modeled database proof rejection", () -> {
                Fixture fixture = new Fixture(1); fixture.witness(STORE, 0);
                FtpsQueue.Model model = fixture.model(1, true); fixture.row(model, 0, 1); model.proofRejected = true;
                rejectedWithoutPublication(fixture, model, new Protocol(fixture, model), 0, "proof gate rejected");
                check(Files.isDirectory(fixture.marker(0)), "Failed database proof preserves marker");
            });
            for (Failure failure : new Failure[] { Failure.BEFORE_ROOT_FSYNC, Failure.AFTER_ROOT_FSYNC }) {
                run("initialization interruption " + failure, () -> interruptedAdmission(failure, true));
                run("database ahead interruption " + failure, () -> interruptedAdmission(failure, false));
            }
            for (String kind : new String[] { "main", "malformed-temp", "ahead-temp", "same-temp" })
                run("existing sentinel " + kind, () -> uncertainSentinel(kind));
            run("runtime database failure wrapped for admission", () -> {
                Fixture fixture = new Fixture(0); FtpsQueue.Model model = fixture.model(0, false); model.runtimeFailure = true;
                IOException e = fails("retirement database unavailable", () -> admit(fixture, model, new Protocol(fixture, model)));
                check(e.getCause() instanceof IllegalStateException, "Original runtime failure retained");
                check(model.floorCalls == 0 && RetirementSentinel.read(fixture.root) == null, "Database open failure cannot admit or publish");
            });
            System.out.println("PASS: " + cases + " production RetirementStore reconciliation cases, " + checks
                + " assertions; actual core filesystem code with modeled DB/Android ports; no Android SQLite/runtime, network, or user data");
        } finally { cleanSyntheticRoots(); }
    }
}
