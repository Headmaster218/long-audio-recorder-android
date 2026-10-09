package io.github.headmaster218.recorder.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Independent helper tests, not Android lifecycle/permission/AudioRecord simulation. */
public final class AdversarialAppRunSafetyTest {
    private interface Case { void run() throws Exception; }
    private static int passed, failed;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void reject(Case c) throws Exception {
        try { c.run(); } catch (IllegalArgumentException | IllegalStateException e) { return; }
        throw new AssertionError("expected rejection");
    }
    private static void run(String label, Case c) {
        try { c.run(); passed++; System.out.println("PASS " + label); }
        catch (Exception | AssertionError e) { failed++; System.out.println("FAIL " + label + ": " + e); }
    }
    private static void processBoundaryAndConsumption() {
        CleanPauseGate current = new CleanPauseGate(); current.cleanPause("capture-A", 12);
        CleanPauseGate.Resume resume = current.consume();
        check(resume != null && resume.captureId.equals("capture-A") && resume.nextEpoch == 12, "same-process clean pause identity");
        check(current.consume() == null && !current.hasCleanPause(), "a failed subsequent start cannot reuse its consumed token");
        CleanPauseGate replacementProcess = new CleanPauseGate();
        check(replacementProcess.consume() == null, "new process cannot reconstruct authority from stale preferences");
    }
    private static void revocationAndInvalidInput() throws Exception {
        CleanPauseGate gate = new CleanPauseGate(); gate.cleanPause("capture-A", 2);
        reject(() -> gate.cleanPause("", 3)); reject(() -> gate.cleanPause("capture-B", -1));
        check(gate.consume().captureId.equals("capture-A"), "invalid transition does not substitute another identity");
        gate.cleanPause("capture-B", 9); gate.clear(); check(gate.consume() == null, "stop/error revokes pause authority");
    }
    private static void concurrentConsumption() throws Exception {
        CleanPauseGate gate = new CleanPauseGate(); gate.cleanPause("capture", 1);
        CountDownLatch start = new CountDownLatch(1); AtomicInteger consumed = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Runnable action = () -> {
            try { start.await(); if (gate.consume() != null) consumed.incrementAndGet(); }
            catch (Throwable t) { error.set(t); }
        };
        Thread one = new Thread(action), two = new Thread(action); one.start(); two.start(); start.countDown();
        one.join(1000); two.join(1000);
        check(!one.isAlive() && !two.isAlive() && error.get() == null, "bounded concurrent token calls complete");
        check(consumed.get() == 1, "only one caller receives the resume capability");
    }
    private static void emptyQueueInFlightAndHeld() {
        PcmReadAccounting a = new PcmReadAccounting();
        check(a.readAccepted(2048), "read recorded before append failure");
        PcmReadAccounting.Snapshot onlyDequeued = a.snapshot();
        check(onlyDequeued.hasUnconfirmed() && onlyDequeued.unconfirmedSamples == 2048, "empty queue does not hide the in-flight block");
        check(onlyDequeued.warning().contains("up to 2048") && onlyDequeued.warning().contains("may already be staged"), "warning is conservative, not measured loss");
        a.readAccepted(3); // Includes a partial stereo frame that fails attribution before enqueue.
        check(a.snapshot().unconfirmedSamples == 2051, "held and odd interleaved sample counts are retained");
        a.appendConfirmed(2048);
        check(a.snapshot().unconfirmedSamples == 3, "only successful full append acknowledgements reduce uncertainty");
    }
    private static void atomicValidationAndSuccessfulDrain() throws Exception {
        PcmReadAccounting a = new PcmReadAccounting(); a.readAccepted(7);
        reject(() -> a.appendConfirmed(8)); reject(() -> a.readAccepted(0)); reject(() -> a.appendConfirmed(-1));
        check(a.snapshot().readSamples == 7 && a.snapshot().confirmedSamples == 0, "invalid input preserves accounting");
        a.appendConfirmed(3); a.appendConfirmed(4);
        check(!a.snapshot().hasUnconfirmed() && a.snapshot().warning().isEmpty(), "complete acknowledged drain has no RAM-loss claim");
    }
    private static void saturationCannotHideUncertainty() {
        PcmReadAccounting a = new PcmReadAccounting();
        check(a.readAccepted(Long.MAX_VALUE) && a.appendConfirmed(Long.MAX_VALUE), "exact numeric boundary is supported");
        check(!a.readAccepted(1), "observed read overflow stops further permission");
        check(!a.appendConfirmed(1), "overflow remains latched even during reconciliation");
        PcmReadAccounting.Snapshot s = a.snapshot();
        check(s.readSamples == Long.MAX_VALUE && s.confirmedSamples == Long.MAX_VALUE, "numeric fields saturate without wrapping");
        check(s.unconfirmedSamples == 0 && s.hasUnconfirmed() && s.warning().contains("unknown"), "zero saturated difference never erases uncertainty");
    }
    private static void synchronizedAccounting() throws Exception {
        PcmReadAccounting a = new PcmReadAccounting(); AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Runnable action = () -> {
            try { for (int i = 0; i < 100; i++) { a.readAccepted(7); a.appendConfirmed(7); } }
            catch (Throwable t) { error.set(t); }
        };
        Thread one = new Thread(action), two = new Thread(action); one.start(); two.start(); one.join(1000); two.join(1000);
        check(!one.isAlive() && !two.isAlive() && error.get() == null, "bounded concurrent accounting completes");
        check(a.snapshot().readSamples == 1400 && a.snapshot().confirmedSamples == 1400 && !a.snapshot().hasUnconfirmed(), "no lost read/ack updates");
    }
    public static void main(String[] args) {
        run("process boundary and one-shot resume consumption", AdversarialAppRunSafetyTest::processBoundaryAndConsumption);
        run("stop/error revocation and invalid token input", AdversarialAppRunSafetyTest::revocationAndInvalidInput);
        run("concurrent callers cannot consume one pause twice", AdversarialAppRunSafetyTest::concurrentConsumption);
        run("empty-queue in-flight failure plus held partial frame", AdversarialAppRunSafetyTest::emptyQueueInFlightAndHeld);
        run("accounting rejection is atomic and full drain clears warning", AdversarialAppRunSafetyTest::atomicValidationAndSuccessfulDrain);
        run("counter saturation cannot erase unconfirmed-data warning", AdversarialAppRunSafetyTest::saturationCannotHideUncertainty);
        run("read/ack accounting has no lost concurrent updates", AdversarialAppRunSafetyTest::synchronizedAccounting);
        System.out.println("Independent app-run review: " + passed + " cases passed, " + failed + " cases failed; no Android runtime/device execution");
        if (failed != 0) throw new AssertionError("app-run review failures: " + failed);
    }
}
