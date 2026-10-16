package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public final class RetirementSentinelTest {
    private static final String STORE = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String OTHER = "fedcba98-7654-3210-fedc-ba9876543210";
    private static final DirectorySpool.DirectoryProtocol NORMAL = new DirectorySpool.NioProtocol();
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void yes(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    private static void fails(Action action) throws Exception {
        checks++; try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("expected IOException at check " + checks);
    }
    private static void badState(Action action) throws Exception {
        checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("expected invalid state at check " + checks);
    }
    private static Path fresh() throws IOException { return Files.createTempDirectory(Paths.get("app/build/retirement-sentinel-tests"), "synthetic-").toAbsolutePath(); }
    private static Path main(Path root) { return root.resolve(RetirementSentinel.FILE_NAME); }
    private static Path temp(Path root) { return root.resolve(RetirementSentinel.TEMP_FILE_NAME); }
    private static RetirementSentinel.State state(long generation) { return new RetirementSentinel.State(STORE, generation); }
    private static void write(Path root, long generation) throws IOException { RetirementSentinel.write(root, state(generation), NORMAL); }
    private static byte[] encoded(long generation) throws IOException {
        Path root = fresh(); write(root, generation); return Files.readAllBytes(main(root));
    }
    private static void checksum(byte[] bytes) {
        java.security.MessageDigest digest = PcmSegmentWriter.sha256(); digest.update(bytes, 0, bytes.length - 32);
        System.arraycopy(digest.digest(), 0, bytes, bytes.length - 32, 32);
    }
    private static RetirementSentinel.Faults stopAt(RetirementSentinel.Operation expected) {
        return (operation, path) -> { if (operation == expected) throw new IOException("synthetic interruption at " + operation); };
    }
    private static final class CountingProtocol implements DirectorySpool.DirectoryProtocol {
        int syncs;
        boolean failBefore, failAfter;
        @Override public void syncDirectory(Path root) throws IOException {
            syncs++;
            if (failBefore) throw new IOException("synthetic failure before root fsync");
            NORMAL.syncDirectory(root);
            if (failAfter) throw new IOException("synthetic failure after root fsync");
        }
        @Override public void atomicPublish(Path from, Path to) { throw new AssertionError("Sentinel must use atomic replacement, not no-replace publication"); }
    }

    private static void stateAndRoundtrip() throws Exception {
        badState(() -> new RetirementSentinel.State(null, 0));
        badState(() -> new RetirementSentinel.State(STORE, -1));
        badState(() -> new RetirementSentinel.State("1-1-1-1-1", 0));
        badState(() -> new RetirementSentinel.State(STORE.toUpperCase(java.util.Locale.ROOT), 0));
        yes(state(7).equals(state(7))); yes(state(7).hashCode() == state(7).hashCode());
        yes(!state(7).equals(state(8))); yes(!state(7).equals(null)); yes(!state(7).equals(STORE));
        Path root = fresh(); CountingProtocol protocol = new CountingProtocol();
        yes(RetirementSentinel.read(root) == null);
        RetirementSentinel.write(root, state(0), protocol);
        yes(state(0).equals(RetirementSentinel.read(root))); yes(protocol.syncs == 1);
        yes(Files.size(main(root)) <= RetirementSentinel.MAX_FILE_BYTES);
        yes(!Files.exists(temp(root), LinkOption.NOFOLLOW_LINKS));
        RetirementSentinel.write(root, state(1), protocol);
        yes(state(1).equals(RetirementSentinel.read(root))); yes(protocol.syncs == 2);
        byte[] before = Files.readAllBytes(main(root));
        RetirementSentinel.write(root, state(1), protocol);
        yes(protocol.syncs == 3); yes(Arrays.equals(before, Files.readAllBytes(main(root))));
        fails(() -> write(root, 0));
        fails(() -> RetirementSentinel.write(root, new RetirementSentinel.State(OTHER, 2), NORMAL));
        yes(Arrays.equals(before, Files.readAllBytes(main(root))));
        yes(!Files.exists(temp(root), LinkOption.NOFOLLOW_LINKS));
        write(root, Long.MAX_VALUE); yes(state(Long.MAX_VALUE).equals(RetirementSentinel.read(root)));
        fails(() -> write(root, Long.MAX_VALUE - 1));
    }

    private static void malformedAndLinks() throws Exception {
        byte[] good = encoded(5);
        byte[][] malformed = new byte[9][];
        malformed[0] = new byte[0];
        malformed[1] = Arrays.copyOf(good, good.length - 1);
        malformed[2] = Arrays.copyOf(good, good.length + 1);
        malformed[3] = new byte[RetirementSentinel.MAX_FILE_BYTES + 1];
        malformed[4] = good.clone(); malformed[4][malformed[4].length - 1] ^= 1;
        malformed[5] = good.clone(); malformed[5][8] = 'g'; checksum(malformed[5]);
        malformed[6] = good.clone(); ByteBuffer.wrap(malformed[6]).putLong(44, -1); checksum(malformed[6]);
        malformed[7] = good.clone(); ByteBuffer.wrap(malformed[7]).putInt(4, 2); checksum(malformed[7]);
        malformed[8] = good.clone(); malformed[8][0] ^= 1; checksum(malformed[8]);
        for (byte[] broken : malformed) {
            Path root = fresh(); Files.write(main(root), broken);
            fails(() -> RetirementSentinel.read(root)); fails(() -> write(root, 6));
            yes(Arrays.equals(broken, Files.readAllBytes(main(root))));
            yes(!Files.exists(temp(root), LinkOption.NOFOLLOW_LINKS));
        }
        Path directory = fresh(); Files.createDirectory(main(directory));
        fails(() -> RetirementSentinel.read(directory)); fails(() -> write(directory, 0));
        Path target = fresh(); write(target, 5); byte[] unchanged = Files.readAllBytes(main(target));
        Path link = fresh(); Files.createSymbolicLink(main(link), main(target));
        fails(() -> RetirementSentinel.read(link)); fails(() -> write(link, 5));
        yes(Files.isSymbolicLink(main(link))); yes(Arrays.equals(unchanged, Files.readAllBytes(main(target))));
        Path scratchLink = fresh(); Files.createSymbolicLink(temp(scratchLink), main(target));
        fails(() -> write(scratchLink, 5)); yes(Files.isSymbolicLink(temp(scratchLink)));
        yes(!Files.exists(main(scratchLink), LinkOption.NOFOLLOW_LINKS));
        yes(Arrays.equals(unchanged, Files.readAllBytes(main(target))));
        Path dangling = fresh(); Files.createSymbolicLink(main(dangling), dangling.resolve("absent"));
        fails(() -> RetirementSentinel.read(dangling)); fails(() -> write(dangling, 0));
        Path rootLinkParent = fresh(), rootLink = rootLinkParent.resolve("root-link");
        Files.createSymbolicLink(rootLink, target);
        fails(() -> RetirementSentinel.read(rootLink)); fails(() -> write(rootLink, 6));
        yes(Arrays.equals(unchanged, Files.readAllBytes(main(target))));
    }

    private static void interruptedPublication() throws Exception {
        for (RetirementSentinel.Operation point : RetirementSentinel.Operation.values()) {
            Path root = fresh(); write(root, 7); CountingProtocol protocol = new CountingProtocol();
            fails(() -> RetirementSentinel.write(root, state(8), protocol, stopAt(point)));
            if (point == RetirementSentinel.Operation.WRITE_TEMP) {
                yes(state(7).equals(RetirementSentinel.read(root)));
                yes(!Files.exists(temp(root), LinkOption.NOFOLLOW_LINKS));
            } else if (point == RetirementSentinel.Operation.SYNC_ROOT) {
                yes(state(8).equals(RetirementSentinel.read(root)));
                yes(!Files.exists(temp(root), LinkOption.NOFOLLOW_LINKS));
            } else {
                yes(state(7).equals(RetirementSentinel.read(root)));
                yes(Arrays.equals(encoded(8), Files.readAllBytes(temp(root))));
            }
            RetirementSentinel.write(root, state(8), protocol);
            yes(state(8).equals(RetirementSentinel.read(root))); yes(protocol.syncs == 1);
            yes(!Files.exists(temp(root), LinkOption.NOFOLLOW_LINKS));
        }
        for (boolean after : new boolean[] { false, true }) {
            Path root = fresh(); write(root, 2); CountingProtocol protocol = new CountingProtocol();
            protocol.failBefore = !after; protocol.failAfter = after;
            fails(() -> RetirementSentinel.write(root, state(3), protocol));
            yes(state(3).equals(RetirementSentinel.read(root)));
            yes(!Files.exists(temp(root), LinkOption.NOFOLLOW_LINKS));
            // Same-state replay must still force the published file and invoke root fsync.
            fails(() -> RetirementSentinel.write(root, state(3), protocol, stopAt(RetirementSentinel.Operation.SYNC_FILE)));
            yes(protocol.syncs == 1);
            fails(() -> RetirementSentinel.write(root, state(3), protocol)); yes(protocol.syncs == 2);
            protocol.failBefore = false; protocol.failAfter = false;
            RetirementSentinel.write(root, state(3), protocol); yes(protocol.syncs == 3);
            yes(state(3).equals(RetirementSentinel.read(root)));
        }
    }

    private static void scratchUncertainty() throws Exception {
        Path freshReplay = fresh(); byte[] pending = encoded(4); Files.write(temp(freshReplay), pending);
        yes(RetirementSentinel.read(freshReplay) == null);
        fails(() -> write(freshReplay, 5)); yes(Arrays.equals(pending, Files.readAllBytes(temp(freshReplay))));
        fails(() -> RetirementSentinel.write(freshReplay, state(4), NORMAL, stopAt(RetirementSentinel.Operation.SYNC_FILE)));
        yes(!Files.exists(main(freshReplay), LinkOption.NOFOLLOW_LINKS));
        yes(Arrays.equals(pending, Files.readAllBytes(temp(freshReplay))));
        write(freshReplay, 4); yes(state(4).equals(RetirementSentinel.read(freshReplay)));
        yes(!Files.exists(temp(freshReplay), LinkOption.NOFOLLOW_LINKS));

        Path exact = fresh(); write(exact, 4); Files.write(temp(exact), pending);
        write(exact, 4); yes(state(4).equals(RetirementSentinel.read(exact)));
        yes(!Files.exists(temp(exact), LinkOption.NOFOLLOW_LINKS));

        Path conflict = fresh(); write(conflict, 3); Files.write(temp(conflict), pending);
        byte[] old = Files.readAllBytes(main(conflict));
        fails(() -> write(conflict, 3)); fails(() -> write(conflict, 5));
        yes(Arrays.equals(old, Files.readAllBytes(main(conflict))));
        yes(Arrays.equals(pending, Files.readAllBytes(temp(conflict))));
        write(conflict, 4); yes(state(4).equals(RetirementSentinel.read(conflict)));

        Path stale = fresh(); write(stale, 5); Files.write(temp(stale), pending);
        byte[] newer = Files.readAllBytes(main(stale));
        fails(() -> write(stale, 5)); fails(() -> write(stale, 4)); fails(() -> write(stale, 6));
        yes(Arrays.equals(newer, Files.readAllBytes(main(stale))));
        yes(Arrays.equals(pending, Files.readAllBytes(temp(stale))));

        Path foreign = fresh(); RetirementSentinel.write(foreign, new RetirementSentinel.State(OTHER, 4), NORMAL);
        Path wrongStore = fresh(); write(wrongStore, 3); byte[] wrong = Files.readAllBytes(main(foreign));
        Files.write(temp(wrongStore), wrong); fails(() -> write(wrongStore, 4));
        yes(state(3).equals(RetirementSentinel.read(wrongStore))); yes(Arrays.equals(wrong, Files.readAllBytes(temp(wrongStore))));

        Path malformed = fresh(); write(malformed, 3); byte[] partial = Arrays.copyOf(pending, 17);
        Files.write(temp(malformed), partial); fails(() -> write(malformed, 3)); fails(() -> write(malformed, 4));
        yes(state(3).equals(RetirementSentinel.read(malformed))); yes(Arrays.equals(partial, Files.readAllBytes(temp(malformed))));
        Path badMain = fresh(); Files.write(main(badMain), partial); Files.write(temp(badMain), pending);
        fails(() -> write(badMain, 4)); yes(Arrays.equals(partial, Files.readAllBytes(main(badMain))));
        yes(Arrays.equals(pending, Files.readAllBytes(temp(badMain))));
        Path directory = fresh(); Files.createDirectory(temp(directory)); fails(() -> write(directory, 0));
        yes(Files.isDirectory(temp(directory), LinkOption.NOFOLLOW_LINKS));
    }

    public static void main(String[] args) throws Exception {
        stateAndRoundtrip(); malformedAndLinks(); interruptedPublication(); scratchUncertainty();
        System.out.println("PASS: " + checks + " retirement-sentinel assertions; synthetic only, no Android runtime");
    }
}
