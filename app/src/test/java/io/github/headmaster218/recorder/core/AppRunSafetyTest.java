package io.github.headmaster218.recorder.core;

/** Deterministic production-helper tests, not Android Activity/AudioRecord runtime simulation. */
public final class AppRunSafetyTest {
    private static int checks;
    private interface Action { void run(); }
    private static void yes(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void eq(long a,long b) { checks++; if (a != b) throw new AssertionError(a + " != " + b); }
    private static void reject(Action action) { checks++; try { action.run(); } catch (IllegalArgumentException | IllegalStateException e) { return; } throw new AssertionError("expected rejection"); }
    private static void pauseLifecycle() {
        CleanPauseGate process = new CleanPauseGate();
        yes(!process.hasCleanPause()); yes(process.consume() == null);
        process.cleanPause("capture-C",1); yes(process.hasCleanPause());
        CleanPauseGate.Resume resume = process.consume();
        yes(resume.captureId.equals("capture-C")); eq(1,resume.nextEpoch);
        yes(!process.hasCleanPause()); yes(process.consume() == null); // Cannot reuse while a resumed run is active.
        // Even if old preferences still say paused=true/capture-C/epoch-1, a new process has no capability.
        CleanPauseGate afterProcessDeath = new CleanPauseGate(); yes(afterProcessDeath.consume() == null);
        process.cleanPause("capture-C",2); CleanPauseGate.Resume second = process.consume(); eq(2,second.nextEpoch);
        process.cleanPause("capture-C",3); process.clear(); yes(process.consume() == null); // Explicit Stop cancels pause.
        process.cleanPause("capture-C",4); process.consume(); process.clear(); yes(process.consume() == null); // Failed start never restores stale permission.
        reject(() -> process.cleanPause("",1)); reject(() -> process.cleanPause("capture",-1));
        yes(process.consume() == null);
    }
    private static void inFlightFailure() {
        PcmReadAccounting accounting = new PcmReadAccounting();
        yes(accounting.readAccepted(2048));
        // The only block was dequeued and append failed. Queue length is zero, but acknowledgement is absent.
        PcmReadAccounting.Snapshot failure = accounting.snapshot();
        eq(2048,failure.readSamples); eq(0,failure.confirmedSamples); eq(2048,failure.unconfirmedSamples);
        yes(failure.hasUnconfirmed()); yes(failure.warning().contains("2048"));
        yes(failure.warning().contains("Some bytes may already be staged"));
        // A partially successful filesystem write still cannot acknowledge the entire failed append.
        eq(2048,accounting.snapshot().unconfirmedSamples);
    }
    private static void heldQueuedAndConfirmed() {
        PcmReadAccounting a = new PcmReadAccounting();
        yes(a.readAccepted(100)); yes(a.appendConfirmed(100));
        yes(a.readAccepted(200)); // Currently dequeued/in-flight.
        yes(a.readAccepted(300)); // Still queued.
        yes(a.readAccepted(40));  // Held by reader when attribution fails, never enqueued.
        PcmReadAccounting.Snapshot s = a.snapshot();
        eq(640,s.readSamples); eq(100,s.confirmedSamples); eq(540,s.unconfirmedSamples);
        yes(s.warning().contains("540"));
        yes(a.appendConfirmed(200)); eq(340,a.snapshot().unconfirmedSamples);
        yes(a.appendConfirmed(300)); yes(a.appendConfirmed(40));
        s = a.snapshot(); eq(640,s.confirmedSamples); eq(0,s.unconfirmedSamples);
        yes(!s.hasUnconfirmed()); yes(s.warning().isEmpty());
        // Later read failure must not retroactively turn acknowledged data into an exact loss claim.
        yes(a.readAccepted(1)); eq(1,a.snapshot().unconfirmedSamples);
        reject(() -> a.appendConfirmed(2)); eq(1,a.snapshot().unconfirmedSamples);
        reject(() -> a.readAccepted(0)); reject(() -> a.readAccepted(-1)); reject(() -> a.appendConfirmed(0));
        yes(a.appendConfirmed(1)); yes(!a.snapshot().hasUnconfirmed());
    }
    private static void failClosedOverflow() {
        PcmReadAccounting a = new PcmReadAccounting();
        yes(a.readAccepted(Long.MAX_VALUE)); yes(a.appendConfirmed(Long.MAX_VALUE));
        yes(!a.readAccepted(1));
        PcmReadAccounting.Snapshot s = a.snapshot();
        yes(s.counterOverflow); yes(s.hasUnconfirmed()); yes(s.warning().contains("size is unknown"));
        // Saturation can make arithmetic difference zero; the overflow latch must still warn.
        eq(0,s.unconfirmedSamples); yes(!s.warning().isEmpty());
        yes(!a.appendConfirmed(1)); yes(a.snapshot().hasUnconfirmed());
    }
    public static void main(String[] args) {
        pauseLifecycle(); inFlightFailure(); heldQueuedAndConfirmed(); failClosedOverflow();
        System.out.println("PASS: " + checks + " app-run safety helper assertions; no Android runtime/device execution");
    }
}
