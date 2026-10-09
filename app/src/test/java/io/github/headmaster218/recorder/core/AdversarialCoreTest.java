package io.github.headmaster218.recorder.core;

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
import io.github.headmaster218.recorder.core.TransferPolicy.AutomaticTriggers;
import io.github.headmaster218.recorder.core.TransferPolicy.Network;

/** Independent review cases; run separately with scripts/test-core-adversarial.sh. */
public final class AdversarialCoreTest {
    private interface Case { void run(); }
    private static int passed, failed;
    private static final String A = hash('a'), B = hash('b'), C = hash('c'), D = hash('d');
    private static String hash(char c) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 64; i++) s.append(c);
        return s.toString();
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(long expected, long actual, String message) {
        check(expected == actual, message + ": expected " + expected + ", actual " + actual);
    }
    private static void rejected(Case c) {
        try { c.run(); } catch (IllegalArgumentException e) { return; }
        catch (IllegalStateException e) { return; }
        throw new AssertionError("expected rejection");
    }
    private static void run(String name, Case c) {
        try {
            c.run(); passed++; System.out.println("PASS " + name);
        } catch (RuntimeException e) {
            failed++; System.out.println("FAIL " + name + ": " + e);
        } catch (AssertionError e) {
            failed++; System.out.println("FAIL " + name + ": " + e.getMessage());
        }
    }
    private static Epoch epoch(long number, PcmFormat format, Gap gap) {
        return new Epoch("capture", "run", number, format, "phone", "phone", gap);
    }
    private static void pcmLimits() {
        PcmFormat widest = new PcmFormat(Integer.MAX_VALUE, 32);
        long maxFrames = Long.MAX_VALUE / 64;
        equal(maxFrames * 64, widest.bytesForFrames(maxFrames), "maximum safe payload");
        rejected(() -> widest.bytesForFrames(maxFrames + 1));
        long seconds = Long.MAX_VALUE / Integer.MAX_VALUE;
        equal(seconds * Integer.MAX_VALUE, widest.framesForSeconds(seconds), "maximum safe duration");
        rejected(() -> widest.framesForSeconds(seconds + 1));
        equal(0, widest.bytesForFrames(0), "empty sizing");
    }
    private static void epochCounterOverflow() {
        long half = Long.MAX_VALUE / 2;
        CaptureTimeline t = new CaptureTimeline(epoch(0, PcmFormat.DEFAULT, Gap.START), half, 7);
        t.appendFrames(half); t.seal(A, Close.ROTATION);
        t.appendFrames(half); t.seal(A, Close.ROTATION);
        t.appendFrames(1);
        rejected(() -> t.appendFrames(1));
        equal(half - 1, t.framesUntilBoundary(), "failed append preserves open frame count");
        SegmentManifest tail = t.seal(A, Close.STOP);
        equal(Long.MAX_VALUE - 1, tail.frameStart, "frame start after rejected overflow");
        equal(1, tail.frameCount, "tail remains sealable");
        equal(9, tail.sequence, "failed append does not advance sequence");
        check(tail.trustedPreviousSeam, "overflow rejection does not invent a gap");
    }
    private static void invalidEpochIsAtomic() {
        long half = Long.MAX_VALUE / 2;
        Epoch initial = epoch(0, PcmFormat.DEFAULT, Gap.START);
        CaptureTimeline t = new CaptureTimeline(initial, half, 0);
        t.appendFrames(half); t.seal(A, Close.ROTATION);
        rejected(() -> t.beginEpoch(epoch(1, new PcmFormat(48000, 2), Gap.FORMAT_CHANGE)));
        t.appendFrames(1); SegmentManifest tail = t.seal(A, Close.INTERRUPTION);
        check(tail.epoch == initial, "rejected format transition preserves old epoch");
        equal(half, tail.frameStart, "old counter remains intact");
        rejected(() -> t.appendFrames(1));
        t.beginEpoch(epoch(1, PcmFormat.DEFAULT, Gap.UNKNOWN));
        t.appendFrames(1); SegmentManifest resumed = t.seal(A, Close.STOP);
        check(!resumed.trustedPreviousSeam, "new epoch never inherits trusted seam");
        equal(0, resumed.frameStart, "new epoch counter starts at zero");
    }
    private static void cacheArithmetic() {
        CachePolicy p = new CachePolicy(Long.MAX_VALUE, Long.MAX_VALUE - 10);
        check(p.beforeWrite(Long.MAX_VALUE - 10, Long.MAX_VALUE, 10) == CachePolicy.Decision.CONTINUE,
            "exact maximum and free reserve are allowed");
        check(p.beforeWrite(Long.MAX_VALUE - 10, Long.MAX_VALUE, 11) == CachePolicy.Decision.STOP_AND_ALERT,
            "one byte too much must stop without eviction");
        check(p.beforeWrite(0, Long.MAX_VALUE - 11, 0) == CachePolicy.Decision.STOP_AND_ALERT,
            "reserve violation stops even with no new payload");
        rejected(() -> p.beforeWrite(0, 0, -1));
    }
    private static void triggerTruthTable() {
        TransferPolicy p = new TransferPolicy(false, true, false, false, false, 10, 100);
        Network wifi = new Network(true, true, false, false, false, false);
        for (int bits = 0; bits < 16; bits++) {
            Block got = p.evaluate((bits & 1) != 0 ? 10 : 1, (bits & 2) != 0 ? 100 : 0,
                (bits & 4) != 0, (bits & 8) != 0, false, wifi);
            check(got == (bits == 0 ? Block.NOT_TRIGGERED : Block.NONE), "ANY triggers: mask " + bits);
        }
    }
    private static void constraintTruthTable() {
        // 32 configurations x 64 network states x 2 charging states. Upload now is always set.
        for (int c = 0; c < 32; c++) for (int n = 0; n < 64; n++) for (int power = 0; power < 2; power++) {
            boolean chargeRequired = (c & 1) != 0, wifiOnly = (c & 2) != 0;
            boolean allowCell = (c & 4) != 0, allowMetered = (c & 8) != 0, allowRoaming = (c & 16) != 0;
            Network network = new Network((n & 1) != 0, (n & 2) != 0, (n & 4) != 0,
                (n & 8) != 0, (n & 16) != 0, (n & 32) != 0);
            TransferPolicy p = new TransferPolicy(chargeRequired, wifiOnly, allowCell,
                allowMetered, allowRoaming, 100, -1);
            boolean expected = (!chargeRequired || power == 1) && network.connected && !network.ambiguous
                && (network.wifi != network.cellular) && (!wifiOnly || network.wifi)
                && (allowCell || !network.cellular) && (allowMetered || !network.metered)
                && (allowRoaming || !network.roaming);
            check((p.evaluate(1, 0, false, true, power == 1, network) == Block.NONE) == expected,
                "hard gate mismatch: config=" + c + ", network=" + n + ", charging=" + power);
        }
    }
    private static void automaticTriggerComposition() {
        Network wifi = new Network(true, true, false, false, false, false);
        for (AutomaticTriggers strategy : AutomaticTriggers.values()) {
            for (int ageEnabled = 0; ageEnabled < 2; ageEnabled++) for (int bits = 0; bits < 16; bits++) {
                boolean bytes = (bits & 1) != 0, age = (bits & 2) != 0;
                boolean stopped = (bits & 4) != 0, manual = (bits & 8) != 0;
                TransferPolicy policy = new TransferPolicy(true, true, false, false, false,
                    10, ageEnabled == 1 ? 100 : -1, strategy);
                boolean automatic = strategy == AutomaticTriggers.ANY
                    ? bytes || (ageEnabled == 1 && age) : bytes && (ageEnabled == 0 || age);
                Block got = policy.evaluate(bytes ? 10 : 1, age ? 100 : 0, stopped, manual, true, wifi);
                check(got == (automatic || stopped || manual ? Block.NONE : Block.NOT_TRIGGERED),
                    "automatic strategy=" + strategy + ", age enabled=" + ageEnabled + ", mask=" + bits);
                check(policy.evaluate(bytes ? 10 : 1, age ? 100 : 0, stopped, true, false, wifi)
                    == Block.NOT_CHARGING, "manual trigger never bypasses charging");
            }
        }
    }
    private static void reservationRecovery() {
        QuotaLedger q = new QuotaLedger(100, 200, 10, 1, 0, 0);
        QuotaLedger.Reservation a = q.reserve(40), b = q.reserve(60);
        check(q.recordBytes(a, 5), "bounded attempt is permitted");
        QuotaLedger.Snapshot crash = q.snapshot();
        equal(100, crash.dailyCharged, "snapshot includes both pending reservations");
        QuotaLedger restore = new QuotaLedger(100, 200, crash.day, crash.month,
            crash.dailyCharged, crash.monthlyCharged);
        equal(0, restore.availableBytes(), "recovery never refunds unknown attempts");
        rejected(() -> restore.finish(a));
        rejected(() -> q.advanceWindow(11, 1));
        equal(10, q.snapshot().day, "active transfer blocks rollover atomically");
        q.finish(a); q.finish(b);
        rejected(() -> q.recordBytes(b, 1));
        equal(95, q.availableBytes(), "only unused bytes released");
        q.advanceWindow(11, 1);
        equal(5, q.snapshot().monthlyCharged, "day reset preserves month");
        rejected(() -> q.advanceWindow(10, 1));
    }
    private static void overrunSnapshotRemainsPersistable() {
        QuotaLedger q = new QuotaLedger(Long.MAX_VALUE, Long.MAX_VALUE, 0, 0, 0, 0);
        QuotaLedger.Reservation a = q.reserve(10);
        check(q.reserve(Long.MAX_VALUE - 10) != null, "second reservation fills quota");
        check(!q.recordBytes(a, 11), "observed overrun must be rejected");
        QuotaLedger.Snapshot s = q.snapshot();
        // Saturation or an explicit persistent exhausted state must preserve fail-closed recovery.
        check(s.dailyCharged >= Long.MAX_VALUE && s.monthlyCharged >= Long.MAX_VALUE,
            "overrun with pending reservations must remain durably exhausted");
        QuotaLedger recovered = new QuotaLedger(Long.MAX_VALUE, Long.MAX_VALUE, s.day, s.month,
            s.dailyCharged, s.monthlyCharged);
        equal(0, recovered.availableBytes(), "saturated snapshot restores exhausted budget");
    }
    private static void observedCounterOverflowFailsClosed() {
        QuotaLedger q = new QuotaLedger(Long.MAX_VALUE, Long.MAX_VALUE, 0, 0,
            Long.MAX_VALUE - 100, Long.MAX_VALUE - 100);
        QuotaLedger.Reservation a = q.reserve(10);
        try {
            check(!q.recordBytes(a, 101), "actual traffic beyond numeric maximum must not be accepted");
        } catch (IllegalArgumentException e) {
            // An exception is acceptable only if the ledger has latched closed for future I/O.
        }
        equal(0, q.availableBytes(), "already-observed overflowing traffic must fail closed");
        check(q.reserve(1) == null, "no further reservation after unaccountable observed traffic");
        q.snapshot();
    }
    private static void reconciledCounterOverflowReturnsFalse() {
        QuotaLedger q = new QuotaLedger(Long.MAX_VALUE, Long.MAX_VALUE, 0, 0, 0, 0);
        QuotaLedger.Reservation a = q.reserve(1), b = q.reserve(Long.MAX_VALUE - 1);
        check(!q.recordBytes(a, 2), "first overrun reports stop");
        // These bytes can already be in flight. Stopping emission does not remove the need to account them.
        check(!q.recordBytes(b, Long.MAX_VALUE - 1), "reconciling observed overflow must also return false");
        equal(Long.MAX_VALUE, q.snapshot().dailyCharged, "reconciliation saturation stays persistable");
        equal(0, q.availableBytes(), "reconciliation cannot reauthorize I/O");
    }
    private static Receipt receipt(int changed) {
        return new Receipt(changed == 1 ? "other-fragment" : "capture/0",
            changed == 2 ? "other-destination" : "nas", changed == 3 ? "other-payload" : "payload",
            changed == 4 ? "other-metadata" : "metadata", changed == 5 ? "other-commit" : "commit",
            changed == 6 ? 101 : 100, changed == 7 ? D : A, changed == 8 ? D : B, changed == 9 ? D : C,
            new ObjectBinding(changed == 10 ? "other-local" : "local-object",
                changed == 11 ? "other-local-version" : "local-version",
                changed == 12 ? "other-payload-version" : "payload-version",
                changed == 13 ? "other-metadata-version" : "metadata-version",
                changed == 14 ? "other-commit-version" : "commit-version"));
    }
    private static DeletionGate verified() {
        DeletionGate gate = new DeletionGate(receipt(0), true, true);
        gate.beginUpload(); gate.payloadVerified(100, A, Verification.AUTHENTICATED_FULL_READBACK);
        return gate;
    }
    private static void receiptIdentityBinding() {
        for (int field = 1; field <= 14; field++) {
            final Receipt wrong = receipt(field);
            DeletionGate gate = verified();
            Guard guard = new Guard(receipt(0), "operation-" + field);
            rejected(() -> gate.committed(wrong, Verification.TRUSTED_SERVER_SHA256, true));
            check(gate.state() == State.REMOTE_VERIFIED, "wrong receipt preserves verified-only state");
            gate.committed(receipt(0), Verification.TRUSTED_SERVER_SHA256, true);
            rejected(() -> gate.receiptPersisted(wrong));
            check(!gate.finalObjectsRechecked(receipt(0), Verification.TRUSTED_SERVER_SHA256, guard),
                "wrong persisted receipt never enables delete");
            gate.receiptPersisted(receipt(0));
            check(!gate.finalObjectsRechecked(wrong, Verification.TRUSTED_SERVER_SHA256, guard),
                "wrong final object identity never enables delete");
        }
    }
    private static void finalRecheckRevokesPermission() {
        DeletionGate gate = verified();
        Guard guard = new Guard(receipt(0), "operation");
        gate.committed(receipt(0), Verification.TRUSTED_SERVER_SHA256, true);
        gate.receiptPersisted(receipt(0));
        check(gate.finalObjectsRechecked(receipt(0), Verification.TRUSTED_SERVER_SHA256, guard), "valid receipt eligible");
        check(!gate.finalObjectsRechecked(receipt(7), Verification.TRUSTED_SERVER_SHA256, guard), "changed content revokes");
        rejected(() -> gate.localDeletionAcknowledged(guard));
        check(gate.finalObjectsRechecked(receipt(0), Verification.TRUSTED_SERVER_SHA256, guard), "restored evidence eligible");
        check(!gate.finalObjectsRechecked(receipt(0), null, guard), "missing verification method revokes");
        rejected(() -> gate.localDeletionAcknowledged(guard));
    }
    private static void guardedDeletionLifetime() {
        DeletionGate gate = verified();
        gate.committed(receipt(0), Verification.TRUSTED_SERVER_SHA256, true);
        gate.receiptPersisted(receipt(0));
        check(!gate.finalObjectsRechecked(receipt(0), Verification.TRUSTED_SERVER_SHA256, null), "missing guard blocks");
        Guard stale = new Guard(receipt(0), "first-operation");
        check(gate.finalObjectsRechecked(receipt(0), Verification.TRUSTED_SERVER_SHA256, stale), "live guard permits");
        stale.invalidate();
        check(gate.state() == State.REMOTE_COMMITTED, "lease loss revokes eligibility");
        rejected(() -> gate.localDeletionAcknowledged(stale));
        check(!gate.finalObjectsRechecked(receipt(0), Verification.TRUSTED_SERVER_SHA256, stale), "invalid guard cannot revive");
        for (int field = 1; field <= 14; field++) {
            check(!gate.finalObjectsRechecked(receipt(0), Verification.TRUSTED_SERVER_SHA256,
                new Guard(receipt(field), "wrong-binding")), "guard identity mismatch blocks: " + field);
        }
        Guard first = new Guard(receipt(0), "same-label"), replacement = new Guard(receipt(0), "same-label");
        check(gate.finalObjectsRechecked(receipt(0), Verification.TRUSTED_SERVER_SHA256, first), "first guard eligible");
        check(gate.finalObjectsRechecked(receipt(0), Verification.TRUSTED_SERVER_SHA256, replacement), "new check replaces guard");
        rejected(() -> gate.localDeletionAcknowledged(first));
        gate.localDeletionAcknowledged(replacement);
        check(gate.state() == State.LOCAL_DELETED, "exact held guard acknowledges delete");
        check(!replacement.isActive(), "successful deletion consumes guard");
        rejected(() -> gate.localDeletionAcknowledged(replacement));
    }
    public static void main(String[] args) {
        run("PCM exact arithmetic boundaries", AdversarialCoreTest::pcmLimits);
        run("epoch counter overflow preserves pending tail", AdversarialCoreTest::epochCounterOverflow);
        run("invalid format transition is atomic", AdversarialCoreTest::invalidEpochIsAtomic);
        run("cache limits cannot overflow or evict", AdversarialCoreTest::cacheArithmetic);
        run("16 trigger combinations", AdversarialCoreTest::triggerTruthTable);
        run("4096 hard-gate combinations, including Upload now", AdversarialCoreTest::constraintTruthTable);
        run("64 ANY/ALL, age-enable and manual/stop trigger combinations", AdversarialCoreTest::automaticTriggerComposition);
        run("concurrent reservation accounting and crash restoration", AdversarialCoreTest::reservationRecovery);
        run("overrun snapshot remains persistable", AdversarialCoreTest::overrunSnapshotRemainsPersistable);
        run("observed counter overflow fails closed", AdversarialCoreTest::observedCounterOverflowFailsClosed);
        run("already-in-flight overflow reconciliation returns false", AdversarialCoreTest::reconciledCounterOverflowReturnsFalse);
        run("all fourteen receipt identity/version fields reject substitutions", AdversarialCoreTest::receiptIdentityBinding);
        run("failed final recheck revokes delete eligibility", AdversarialCoreTest::finalRecheckRevokesPermission);
        run("guard loss, identity mismatch, replacement and consumption", AdversarialCoreTest::guardedDeletionLifetime);
        System.out.println("Independent review: " + passed + " cases passed, " + failed + " cases failed");
        if (failed != 0) throw new AssertionError("independent review failures: " + failed);
    }
}
