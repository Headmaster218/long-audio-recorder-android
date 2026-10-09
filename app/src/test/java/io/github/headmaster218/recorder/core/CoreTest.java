package io.github.headmaster218.recorder.core;

import java.util.Random;
import io.github.headmaster218.recorder.core.CaptureTimeline.Close;
import io.github.headmaster218.recorder.core.CaptureTimeline.Epoch;
import io.github.headmaster218.recorder.core.CaptureTimeline.Gap;
import io.github.headmaster218.recorder.core.CaptureTimeline.SegmentManifest;
import io.github.headmaster218.recorder.core.DeletionGate.Receipt;
import io.github.headmaster218.recorder.core.DeletionGate.ObjectBinding;
import io.github.headmaster218.recorder.core.DeletionGate.Guard;
import io.github.headmaster218.recorder.core.DeletionGate.State;
import io.github.headmaster218.recorder.core.DeletionGate.Verification;
import io.github.headmaster218.recorder.core.TransferPolicy.Block;
import io.github.headmaster218.recorder.core.TransferPolicy.Network;

/** Dependency-free deterministic tests. Run with scripts/test-core.sh. */
public final class CoreTest {
    private static int checks;
    private static final String A = repeat('a'), B = repeat('b'), C = repeat('c');
    private interface Action { void run(); }
    private static String repeat(char c) { StringBuilder b = new StringBuilder(); for (int i = 0; i < 64; i++) b.append(c); return b.toString(); }
    private static void yes(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void eq(long expected, long actual) { checks++; if (expected != actual) throw new AssertionError(expected + " != " + actual + " at " + checks); }
    private static void eq(Object expected, Object actual) { checks++; if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual + " at " + checks); }
    private static void fails(Action action) { checks++; try { action.run(); } catch (IllegalArgumentException e) { return; } catch (IllegalStateException e) { return; } throw new AssertionError("expected rejection at " + checks); }
    private static Epoch epoch(String run, long n, PcmFormat format, Gap gap, String route) { return new Epoch("capture", run, n, format, "phone", route, gap); }
    private static CaptureTimeline timeline(long target) { return new CaptureTimeline(epoch("run", 0, PcmFormat.DEFAULT, Gap.START, "phone"), target, 0); }
    private static ObjectBinding binding() { return new ObjectBinding("local-object-0", "local-generation-1", "remote-payload-1", "remote-metadata-1", "remote-commit-1"); }
    private static Guard guard() { return new Guard(receipt(A), "fenced-operation-1"); }
    private static Receipt receipt(String hash) { return new Receipt("capture/0", "nas-A", "payload.pcm", "manifest.json", "committed.json", 100, hash, B, C, binding()); }
    private static DeletionGate verified(boolean deletion, boolean strict, boolean durable) {
        DeletionGate g = new DeletionGate(receipt(A), deletion, strict); g.beginUpload();
        g.payloadVerified(100, A, Verification.AUTHENTICATED_FULL_READBACK);
        g.committed(receipt(A), Verification.TRUSTED_SERVER_SHA256, durable); return g;
    }
    private static void formats() {
        eq(32000, PcmFormat.DEFAULT.bytesForFrames(16000));
        eq(9600000, PcmFormat.DEFAULT.bytesForFrames(PcmFormat.DEFAULT.framesForSeconds(300)));
        eq(2764800000L, PcmFormat.DEFAULT.bytesForFrames(PcmFormat.DEFAULT.framesForSeconds(86400)));
        eq(192000, new PcmFormat(48000, 2).bytesForFrames(48000));
        yes(PcmFormat.DEFAULT.equals(new PcmFormat(16000, 1)));
        yes(!PcmFormat.DEFAULT.equals(new PcmFormat(48000, 1)));
        fails(() -> new PcmFormat(0, 1)); fails(() -> new PcmFormat(16000, 0));
        fails(() -> new PcmFormat(16000, 33)); fails(() -> PcmFormat.DEFAULT.bytesForFrames(-1));
        fails(() -> PcmFormat.DEFAULT.bytesForFrames(Long.MAX_VALUE));
        fails(() -> PcmFormat.DEFAULT.framesForSeconds(Long.MAX_VALUE));
    }
    private static void capture() {
        CaptureTimeline t = timeline(10);
        fails(() -> t.seal(A, Close.STOP)); fails(() -> t.appendFrames(0));
        t.appendFrames(6); eq(4, t.framesUntilBoundary());
        fails(() -> t.appendFrames(5)); fails(() -> t.seal(A, Close.ROTATION));
        t.appendFrames(4); fails(() -> t.seal("bad", Close.ROTATION));
        SegmentManifest first = t.seal(A, Close.ROTATION);
        eq(0, first.sequence); eq(0, first.frameStart); eq(10, first.frameCount); eq(20, first.payloadBytes);
        yes(!first.trustedPreviousSeam); eq("run", first.epoch.runId);
        t.appendFrames(10); SegmentManifest second = t.seal(B, Close.ROTATION);
        eq(1, second.sequence); eq(10, second.frameStart); yes(second.trustedPreviousSeam);
        t.appendFrames(3);
        fails(() -> t.beginEpoch(epoch("run", 1, PcmFormat.DEFAULT, Gap.ROUTE_CHANGE_UNCERTAIN, "headset")));
        SegmentManifest tail = t.seal(C, Close.INTERRUPTION); eq(20, tail.frameStart); eq(3, tail.frameCount);
        fails(() -> t.appendFrames(1));
        fails(() -> t.beginEpoch(epoch("run", 0, PcmFormat.DEFAULT, Gap.UNKNOWN, "headset")));
        fails(() -> t.beginEpoch(epoch("run", 1, PcmFormat.DEFAULT, Gap.START, "headset")));
        fails(() -> t.beginEpoch(epoch("run", 1, PcmFormat.DEFAULT, Gap.RESTART, "headset")));
        fails(() -> t.beginEpoch(epoch("new", 1, PcmFormat.DEFAULT, Gap.UNKNOWN, "headset")));
        t.beginEpoch(epoch("run", 1, PcmFormat.DEFAULT, Gap.ROUTE_CHANGE_UNCERTAIN, "headset"));
        t.appendFrames(10); SegmentManifest route = t.seal(A, Close.ROTATION);
        eq(3, route.sequence); eq(0, route.frameStart); yes(!route.trustedPreviousSeam);
        eq("phone", route.epoch.requestedInput); eq("headset", route.epoch.actualInput);
        t.beginEpoch(epoch("new", 2, new PcmFormat(48000, 2), Gap.RESTART, "headset"));
        t.appendFrames(2); SegmentManifest recovered = t.seal(A, Close.RECOVERED_TAIL);
        eq(8, recovered.payloadBytes); eq(4, recovered.sequence); yes(!recovered.trustedPreviousSeam);
        fails(() -> t.appendFrames(1));
        CaptureTimeline recoveredSeam = timeline(1);
        recoveredSeam.appendFrames(1); recoveredSeam.seal(A, Close.ROTATION);
        recoveredSeam.appendFrames(1); yes(!recoveredSeam.seal(A, Close.RECOVERED_TAIL).trustedPreviousSeam);
        CaptureTimeline overflow = new CaptureTimeline(epoch("run", 0, PcmFormat.DEFAULT, Gap.START, "phone"), 1, Long.MAX_VALUE);
        overflow.appendFrames(1); fails(() -> overflow.seal(A, Close.ROTATION)); eq(0, overflow.framesUntilBoundary());
        fails(() -> new CaptureTimeline(epoch("run", 0, PcmFormat.DEFAULT, Gap.START, "phone"), Long.MAX_VALUE, 0));
        // Variable input reads are split exactly at output boundaries; no clock/date/upload ordering involved.
        CaptureTimeline split = timeline(37); Random random = new Random(81203);
        long total = 0, nextStart = 0, sequence = 0;
        for (int i = 0; i < 1000; i++) {
            long remainingRead = random.nextInt(100) + 1;
            while (remainingRead > 0) {
                long n = Math.min(remainingRead, split.framesUntilBoundary());
                split.appendFrames(n); remainingRead -= n; total += n;
                if (split.framesUntilBoundary() == 0) {
                    SegmentManifest m = split.seal(A, Close.ROTATION);
                    eq(nextStart, m.frameStart); eq(sequence, m.sequence); eq(37, m.frameCount);
                    yes(m.trustedPreviousSeam == (sequence > 0)); eq("run", m.epoch.runId);
                    nextStart += 37; sequence++;
                }
            }
        }
        if (split.framesUntilBoundary() < 37) { SegmentManifest m = split.seal(A, Close.STOP); eq(nextStart, m.frameStart); nextStart += m.frameCount; }
        eq(total, nextStart);
    }
    private static void cache() {
        CachePolicy c = new CachePolicy(1000, 100);
        eq(CachePolicy.Decision.CONTINUE, c.beforeWrite(900, 200, 100));
        eq(CachePolicy.Decision.STOP_AND_ALERT, c.beforeWrite(900, 200, 101));
        eq(CachePolicy.Decision.STOP_AND_ALERT, c.beforeWrite(900, 199, 100));
        eq(CachePolicy.Decision.STOP_AND_ALERT, c.beforeWrite(1001, 1000, 0));
        eq(CachePolicy.Decision.STOP_AND_ALERT, c.beforeWrite(0, 99, 0));
        eq(CachePolicy.Decision.STOP_AND_ALERT, c.beforeWrite(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
        fails(() -> c.beforeWrite(-1, 1000, 1)); fails(() -> new CachePolicy(0, 0));
    }
    private static void policy() {
        Network wifi = new Network(true, true, false, false, false, false);
        Network metered = new Network(true, true, false, true, false, false);
        Network cell = new Network(true, false, true, true, false, false);
        TransferPolicy p = TransferPolicy.DEFAULT;
        eq(Block.NONE, p.evaluate(9600000, 0, false, false, true, wifi));
        eq(Block.NO_PENDING_DATA, p.evaluate(0, 0, true, true, true, wifi));
        eq(Block.NOT_TRIGGERED, p.evaluate(1, 0, false, false, true, wifi));
        eq(Block.NONE, p.evaluate(1, 0, false, true, true, wifi));
        eq(Block.NONE, p.evaluate(1, 0, true, false, true, wifi));
        eq(Block.NOT_CHARGING, p.evaluate(1, 0, true, true, false, wifi));
        eq(Block.METERED_DISABLED, p.evaluate(1, 0, true, true, true, metered));
        eq(Block.WIFI_REQUIRED, p.evaluate(1, 0, true, true, true, cell));
        eq(Block.OFFLINE, p.evaluate(1, 0, true, true, true, new Network(false, true, false, false, false, false)));
        eq(Block.AMBIGUOUS_ROUTE, p.evaluate(1, 0, true, true, true, new Network(true, true, false, false, false, true)));
        eq(Block.AMBIGUOUS_ROUTE, p.evaluate(1, 0, true, true, true, new Network(true, true, true, false, false, false)));
        TransferPolicy flexible = new TransferPolicy(false, false, true, true, false, 100, 1000);
        eq(Block.NOT_TRIGGERED, flexible.evaluate(1, 999, false, false, false, cell));
        eq(Block.NONE, flexible.evaluate(1, 1000, false, false, false, cell));
        eq(Block.ROAMING_DISABLED, flexible.evaluate(100, 0, false, false, false, new Network(true, false, true, true, true, false)));
        eq(Block.AMBIGUOUS_ROUTE, flexible.evaluate(100, 0, false, false, false, new Network(true, false, false, false, false, false)));
        TransferPolicy noCell = new TransferPolicy(false, false, false, true, false, 1, -1);
        eq(Block.CELLULAR_DISABLED, noCell.evaluate(1, 0, false, false, false, cell));
        TransferPolicy all = new TransferPolicy(true, true, false, false, false, 100, 1000,
            TransferPolicy.AutomaticTriggers.ALL);
        for (int bits = 0; bits < 16; bits++) {
            Block got = all.evaluate((bits & 1) != 0 ? 100 : 1, (bits & 2) != 0 ? 1000 : 0,
                (bits & 4) != 0, (bits & 8) != 0, true, wifi);
            boolean triggered = (bits & 3) == 3 || (bits & 12) != 0;
            eq(triggered ? Block.NONE : Block.NOT_TRIGGERED, got);
        }
        eq(Block.NOT_CHARGING, all.evaluate(1, 0, false, true, false, wifi));
        eq(Block.METERED_DISABLED, all.evaluate(1, 0, true, false, true, metered));
        TransferPolicy noAge = new TransferPolicy(false, true, false, false, false, 100, -1,
            TransferPolicy.AutomaticTriggers.ALL);
        eq(Block.NONE, noAge.evaluate(100, 0, false, false, true, wifi));
        eq(Block.NOT_TRIGGERED, noAge.evaluate(99, 1000000, false, false, true, wifi));
        fails(() -> p.evaluate(-1, 0, false, false, true, wifi));
        fails(() -> new TransferPolicy(false, false, true, true, true, 0, -1));
    }
    private static void quota() {
        QuotaLedger q = new QuotaLedger(100, 150, 20261009, 202610, 0, 0);
        eq(100, q.availableBytes()); yes(q.reserve(101) == null);
        QuotaLedger.Reservation a = q.reserve(60), b = q.reserve(40);
        eq(0, q.availableBytes()); yes(q.reserve(1) == null);
        yes(q.recordBytes(a, 20)); eq(40, a.remainingBytes()); eq(0, q.availableBytes());
        QuotaLedger.Snapshot snapshot = q.snapshot(); eq(100, snapshot.dailyCharged); eq(100, snapshot.monthlyCharged);
        QuotaLedger recovered = new QuotaLedger(100, 150, snapshot.day, snapshot.month, snapshot.dailyCharged, snapshot.monthlyCharged);
        eq(0, recovered.availableBytes()); // Crash must not refund unknown reserved traffic.
        fails(() -> q.advanceWindow(20261010, 202610));
        q.finish(a); eq(40, q.availableBytes()); fails(() -> q.recordBytes(a, 1)); fails(() -> q.finish(a));
        fails(() -> recovered.recordBytes(b, 1));
        yes(q.recordBytes(b, 40)); q.finish(b); eq(40, q.availableBytes());
        QuotaLedger.Reservation retry = q.reserve(40); yes(q.recordBytes(retry, 30)); q.finish(retry);
        eq(10, q.availableBytes()); eq(90, q.snapshot().dailyCharged); // Failed/retried transfers remain charged.
        fails(() -> q.advanceWindow(20261008, 202610)); fails(() -> q.advanceWindow(20261009, 202611));
        q.advanceWindow(20261009, 202610); eq(10, q.availableBytes());
        q.advanceWindow(20261010, 202610); eq(60, q.availableBytes());
        q.advanceWindow(20261101, 202611); eq(100, q.availableBytes());
        QuotaLedger.Reservation overrun = q.reserve(10); yes(!q.recordBytes(overrun, 101));
        eq(101, q.snapshot().dailyCharged); eq(0, q.availableBytes()); q.finish(overrun);
        fails(() -> q.reserve(0)); fails(() -> q.reserve(-1));
        QuotaLedger exact = new QuotaLedger(Long.MAX_VALUE, Long.MAX_VALUE, 0, 0, 0, 0);
        QuotaLedger.Reservation huge = exact.reserve(Long.MAX_VALUE); eq(0, exact.availableBytes());
        yes(exact.recordBytes(huge, Long.MAX_VALUE)); exact.finish(huge); eq(Long.MAX_VALUE, exact.snapshot().dailyCharged);
        eq(0, new QuotaLedger(10, 20, 0, 0, 11, 11).availableBytes());
        QuotaLedger pendingOverflow = new QuotaLedger(Long.MAX_VALUE, Long.MAX_VALUE, 0, 0, 0, 0);
        QuotaLedger.Reservation tiny = pendingOverflow.reserve(10);
        yes(pendingOverflow.reserve(Long.MAX_VALUE - 10) != null);
        yes(!pendingOverflow.recordBytes(tiny, 11));
        eq(Long.MAX_VALUE, pendingOverflow.snapshot().dailyCharged);
        eq(Long.MAX_VALUE, pendingOverflow.snapshot().monthlyCharged); eq(0, pendingOverflow.availableBytes());
        QuotaLedger spentOverflow = new QuotaLedger(Long.MAX_VALUE, Long.MAX_VALUE, 0, 0,
            Long.MAX_VALUE - 100, Long.MAX_VALUE - 100);
        QuotaLedger.Reservation small = spentOverflow.reserve(10);
        yes(!spentOverflow.recordBytes(small, 101)); eq(0, spentOverflow.availableBytes());
        spentOverflow.finish(small); eq(Long.MAX_VALUE, spentOverflow.snapshot().dailyCharged);
        yes(spentOverflow.reserve(1) == null);
        QuotaLedger inFlight = new QuotaLedger(Long.MAX_VALUE, Long.MAX_VALUE, 0, 0, 0, 0);
        QuotaLedger.Reservation firstAttempt = inFlight.reserve(1);
        QuotaLedger.Reservation alreadyInFlight = inFlight.reserve(Long.MAX_VALUE - 1);
        yes(!inFlight.recordBytes(firstAttempt, 2));
        // Reconciliation of another reserved attempt must not report success after cumulative overflow.
        yes(!inFlight.recordBytes(alreadyInFlight, Long.MAX_VALUE - 1));
        eq(0, inFlight.availableBytes()); eq(Long.MAX_VALUE, inFlight.snapshot().dailyCharged);
        eq(Long.MAX_VALUE, inFlight.snapshot().monthlyCharged);
        inFlight.finish(firstAttempt); inFlight.finish(alreadyInFlight);
        yes(inFlight.reserve(1) == null);
    }
    private static void deletion() {
        DeletionGate g = new DeletionGate(receipt(A), true, true);
        Guard operation = guard();
        eq(State.READY, g.state()); fails(() -> g.localDeletionAcknowledged(operation));
        fails(() -> g.payloadVerified(100, A, Verification.TRUSTED_SERVER_SHA256));
        g.beginUpload(); fails(() -> g.payloadVerified(99, A, Verification.TRUSTED_SERVER_SHA256));
        fails(() -> g.payloadVerified(100, B, Verification.TRUSTED_SERVER_SHA256)); // Same size, wrong bytes.
        fails(() -> g.payloadVerified(100, A, null)); eq(State.UPLOADING, g.state());
        g.uploadFailed(); eq(State.READY, g.state()); g.beginUpload();
        g.payloadVerified(100, A, Verification.AUTHENTICATED_FULL_READBACK);
        fails(() -> g.localDeletionAcknowledged(operation));
        fails(() -> g.committed(receipt(B), Verification.TRUSTED_SERVER_SHA256, true));
        g.committed(receipt(A), Verification.TRUSTED_SERVER_SHA256, true);
        yes(!g.finalObjectsRechecked(receipt(A), Verification.TRUSTED_SERVER_SHA256, operation));
        fails(() -> g.receiptPersisted(receipt(B))); fails(() -> g.localDeletionAcknowledged(operation));
        g.receiptPersisted(receipt(A)); yes(!g.finalObjectsRechecked(receipt(B), Verification.TRUSTED_SERVER_SHA256, operation));
        yes(g.finalObjectsRechecked(receipt(A), Verification.AUTHENTICATED_FULL_READBACK, operation));
        eq(State.LOCAL_DELETE_ELIGIBLE, g.state());
        yes(!g.finalObjectsRechecked(null, Verification.TRUSTED_SERVER_SHA256, operation));
        eq(State.REMOTE_COMMITTED, g.state()); fails(() -> g.localDeletionAcknowledged(operation));
        yes(g.finalObjectsRechecked(receipt(A), Verification.TRUSTED_SERVER_SHA256, operation));
        fails(() -> g.localDeletionAcknowledged(guard()));
        g.localDeletionAcknowledged(operation); yes(!operation.isActive()); eq(State.LOCAL_DELETED, g.state()); fails(() -> g.beginUpload());
        DeletionGate strict = verified(true, true, false); strict.receiptPersisted(receipt(A));
        yes(!strict.finalObjectsRechecked(receipt(A), Verification.TRUSTED_SERVER_SHA256, guard()));
        DeletionGate retain = verified(false, false, true); retain.receiptPersisted(receipt(A));
        yes(!retain.finalObjectsRechecked(receipt(A), Verification.TRUSTED_SERVER_SHA256, guard()));
        DeletionGate contentOnly = verified(true, false, false); contentOnly.receiptPersisted(receipt(A));
        yes(contentOnly.finalObjectsRechecked(receipt(A), Verification.TRUSTED_SERVER_SHA256, guard()));
        Receipt otherDestination = new Receipt("capture/0", "nas-B", "payload.pcm", "manifest.json", "committed.json", 100, A, B, C, binding());
        DeletionGate binding = verified(true, false, true); fails(() -> binding.receiptPersisted(otherDestination));
        fails(() -> new Receipt("capture/0", "nas-A", "same", "same", "marker", 100, A, B, C, binding()));
        fails(() -> new Receipt("capture/0", "nas-A", "payload", "metadata", "marker", 0, A, B, C, binding()));
        DeletionGate guarded = verified(true, true, true); guarded.receiptPersisted(receipt(A));
        Guard held = guard();
        yes(!guarded.finalObjectsRechecked(receipt(A), Verification.TRUSTED_SERVER_SHA256, new Guard(otherDestination, "wrong-destination-operation")));
        yes(!guarded.finalObjectsRechecked(receipt(A), Verification.TRUSTED_SERVER_SHA256, null));
        ObjectBinding stale = new ObjectBinding("replacement", "local-generation-1", "remote-payload-1", "remote-metadata-1", "remote-commit-1");
        yes(!guarded.finalObjectsRechecked(receipt(A), Verification.TRUSTED_SERVER_SHA256, new Guard(new Receipt("capture/0", "nas-A", "payload.pcm", "manifest.json", "committed.json", 100, A, B, C, stale), "op")));
        yes(guarded.finalObjectsRechecked(receipt(A), Verification.TRUSTED_SERVER_SHA256, held));
        held.invalidate(); eq(State.REMOTE_COMMITTED, guarded.state());
        fails(() -> guarded.localDeletionAcknowledged(held));
        yes(!guarded.finalObjectsRechecked(receipt(A), Verification.TRUSTED_SERVER_SHA256, held));
        for (int i = 0; i < 5; i++) {
            String[] versions = {"local-object-0", "local-generation-1", "remote-payload-1", "remote-metadata-1", "remote-commit-1"};
            versions[i] = "substituted";
            ObjectBinding wrong = new ObjectBinding(versions[0], versions[1], versions[2], versions[3], versions[4]);
            Receipt changed = new Receipt("capture/0", "nas-A", "payload.pcm", "manifest.json", "committed.json", 100, A, B, C, wrong);
            yes(!guarded.finalObjectsRechecked(changed, Verification.TRUSTED_SERVER_SHA256, guard()));
            fails(() -> guarded.receiptPersisted(changed));
        }
        // Fresh process defaults to retain; it cannot infer persisted receipt or remote success.
        DeletionGate crash = new DeletionGate(receipt(A), true, true);
        fails(() -> crash.localDeletionAcknowledged(operation));
    }
    public static void main(String[] args) {
        formats(); capture(); cache(); policy(); quota(); deletion();
        System.out.println("PASS: " + checks + " deterministic assertions; Java policy core only");
    }
}
