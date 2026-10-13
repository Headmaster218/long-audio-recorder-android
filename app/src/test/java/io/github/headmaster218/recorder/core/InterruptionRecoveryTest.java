package io.github.headmaster218.recorder.core;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import io.github.headmaster218.recorder.core.InterruptionRecovery.End;
import io.github.headmaster218.recorder.core.InterruptionRecovery.EventKind;
import io.github.headmaster218.recorder.core.InterruptionRecovery.GapEvent;
import io.github.headmaster218.recorder.core.InterruptionRecovery.Lease;
import io.github.headmaster218.recorder.core.InterruptionRecovery.Reason;
import io.github.headmaster218.recorder.core.InterruptionRecovery.Retry;
import io.github.headmaster218.recorder.core.InterruptionRecovery.Snapshot;
import io.github.headmaster218.recorder.core.InterruptionRecovery.State;

/** UNEXECUTED draft. Finite host policy tests; no Android, audio, timers or I/O adapters. */
public final class InterruptionRecoveryTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static InterruptionRecovery controller(int retries) { return new InterruptionRecovery(retries,100,250); }
    private static Lease running(InterruptionRecovery c) {
        Lease l = c.explicitStart(true,true,0);
        check(l != null && l.attempt == 0,"one explicit initial lease");
        check(c.captureConfirmed(l,1),"first attributable PCM confirms initial run"); return l;
    }
    private static List<GapEvent> acknowledgeAll(InterruptionRecovery c) {
        List<GapEvent> result = new ArrayList<GapEvent>(); GapEvent e;
        while ((e = c.peekGap()) != null) {
            check(result.size() < 18,"finite evidence bound");
            check(c.peekGap() == e,"peek is nondestructive"); result.add(e);
            check(c.acknowledgeGap(e),"exact event acknowledged");
            check(!c.acknowledgeGap(e),"duplicate ack cannot remove next event");
        }
        check(c.snapshot().unacknowledgedGapEvents == 0,"outbox drained explicitly"); return result;
    }
    private static void optInAndOwnership() {
        InterruptionRecovery c = controller(3);
        check(c.snapshot().state == State.STOPPED && !c.snapshot().recoveryEnabled,"default stopped/opt-out");
        check(c.explicitStart(true,false,0) == null,"flag alone cannot attest OS authority");
        Lease first = c.explicitStart(false,true,0);
        check(first != null && c.snapshot().state == State.STARTING,"explicit start needs confirmation");
        check(c.explicitStart(true,true,0) == null,"duplicate explicit start rejected");
        check(!c.released(first),"live lease cannot be discarded without release request");
        check(c.captureConfirmed(first,1),"initial start confirmed");
        Snapshot before = c.snapshot();
        check(c.interrupted(first,Reason.MICROPHONE_CONTENTION,2),"contention accepted");
        check(before.state == State.RUNNING && !before.releaseRequired,"snapshot remains immutable");
        Snapshot failed = c.snapshot();
        check(failed.state == State.PAUSED && failed.lastEnd == End.OPTED_OUT && failed.retry == null,"no unrequested recovery");
        check(failed.lease == first && failed.releaseRequired,"physical ownership retained");
        check(!c.captureConfirmed(first,3) && !c.interrupted(first,Reason.DEAD_RECORDER,3),"late callback cannot revive failed run");
        check(c.explicitStart(true,true,3) == null,"new start cannot overlap retiring recorder");
        check(c.released(first) && !c.released(first),"release acknowledged once");
        check(c.explicitStart(true,true,3) == null,"unpersisted gap evidence blocks next session");
        List<GapEvent> events = acknowledgeAll(c);
        check(events.size() == 2 && events.get(0).kind == EventKind.OPENED && events.get(1).kind == EventKind.TERMINATED,"paired evidence");
        check(events.get(0).endMillis == -1 && events.get(0).end == null,"open interval is explicitly incomplete");
        check(events.get(1).startMillis == 2 && events.get(1).observedAtMillis == 2
            && events.get(1).endMillis == -1 && events.get(1).end == End.OPTED_OUT,"termination does not invent a zero-length gap");
        Lease newer = c.explicitStart(true,true,3);
        check(newer != null && newer.generation > first.generation,"new explicit generation");
        check(!c.captureConfirmed(first,4) && !c.released(first),"old lease cannot affect new generation");
    }
    private static void retryWhitelisting() {
        for (Reason reason : Reason.values()) {
            InterruptionRecovery c = controller(2); Lease old = running(c);
            check(c.interrupted(old,reason,2),"reason accepted");
            boolean recoverable = reason == Reason.MICROPHONE_CONTENTION || reason == Reason.ROUTE_UNAVAILABLE || reason == Reason.DEAD_RECORDER;
            Snapshot s = c.snapshot();
            check(s.state == (recoverable ? State.BACKOFF : State.PAUSED),"recoverable whitelist only");
            if (!recoverable) {
                check(s.lastEnd == End.NONRECOVERABLE && s.retry == null,"terminal failure never retries");
                continue;
            }
            Retry timer = s.retry;
            check(timer != null && timer.dueMillis == 102,"positive bounded initial delay");
            check(c.takeRetry(timer,true,102) == null && c.snapshot().retriesUsed == 0,"release barrier precedes retry");
            check(c.released(old),"old recorder really released");
            check(c.takeRetry(timer,true,101) == null && c.snapshot().retriesUsed == 0,"early timer consumes nothing");
            Lease next = c.takeRetry(timer,true,102);
            check(next != null && next.attempt == 1,"exact due callback issues one lease");
            check(c.takeRetry(timer,true,102) == null,"duplicate callback cannot issue another");
            check(!c.captureConfirmed(old,103) && !c.released(old),"prior lease rejected");
            check(c.captureConfirmed(next,103),"valid retry confirmation");
            check(!c.captureConfirmed(next,103),"duplicate confirmation rejected");
            List<GapEvent> events = acknowledgeAll(c); GapEvent closed = events.get(1);
            check(closed.kind == EventKind.RECOVERED && closed.end == End.RESUMED
                && closed.startMillis == 2 && closed.endMillis == 103,"observed gap closes at PCM confirmation");
            check(closed.includes(reason) && closed.firstReason == reason && closed.lastReason == reason,"reason retained");
        }
    }
    private static void budgetAndEvidence() {
        for (int budget = 0; budget <= 8; budget++) {
            InterruptionRecovery c = controller(budget); Lease lease = running(c); long now = 2;
            for (int used = 0; used <= budget; used++) {
                check(c.interrupted(lease,Reason.DEAD_RECORDER,now),"finite session interruption");
                Snapshot s = c.snapshot();
                if (used == budget) {
                    check(s.state == State.PAUSED && s.lastEnd == End.RETRIES_EXHAUSTED,"whole-session retry ceiling");
                    check(s.retry == null && s.retriesUsed == budget,"exhaustion has no timer");
                    check(c.released(lease),"final release still possible"); break;
                }
                long expectedDelay = used == 0 ? 100 : used == 1 ? 200 : 250;
                check(s.retry.dueMillis - now == expectedDelay,"exponential backoff capped without overflow");
                check(c.released(lease),"release before every attempt");
                now = s.retry.dueMillis; lease = c.takeRetry(s.retry,true,now);
                check(lease != null && c.snapshot().retriesUsed == used + 1,"retry charged on admission");
                check(c.captureConfirmed(lease,++now),"short success does not reset budget"); now++;
            }
            check(c.snapshot().unacknowledgedGapEvents == 2*(budget+1),"all events retained at maximum bounded occupancy");
            check(c.explicitStart(true,true,now) == null,"no implicit evidence eviction on new Start");
            List<GapEvent> events = acknowledgeAll(c);
            for (int n = 0; n < events.size(); n += 2) {
                check(events.get(n).kind == EventKind.OPENED && events.get(n+1).kind ==
                    (n+2 == events.size() ? EventKind.TERMINATED : EventKind.RECOVERED),"FIFO paired intervals");
                check((events.get(n+1).endMillis == -1) == (n+2 == events.size()),"exhaustion leaves unknown gap end");
                check(events.get(n).gap == n/2 && events.get(n+1).gap == n/2,"unique gap identity");
                check(events.get(n).generation == events.get(n+1).generation,"same explicit-session identity");
            }
            check(c.explicitStart(false,true,now) != null && c.snapshot().retriesUsed == 0,"only explicit new session resets budget");
        }
        InterruptionRecovery c = controller(3); Lease lease = running(c);
        Reason[] reasons = {Reason.MICROPHONE_CONTENTION,Reason.ROUTE_UNAVAILABLE,Reason.DEAD_RECORDER};
        long now = 2;
        for (Reason reason : reasons) {
            check(c.interrupted(lease,reason,now),"retry failure updates same open gap");
            Retry timer = c.snapshot().retry;
            check(c.released(lease),"failed create/use release");
            now = timer.dueMillis; lease = c.takeRetry(timer,true,now); now++;
        }
        check(c.captureConfirmed(lease,now),"finally recovered");
        List<GapEvent> events = acknowledgeAll(c); GapEvent closed = events.get(1);
        check(events.size() == 2 && closed.firstReason == reasons[0] && closed.lastReason == reasons[2],"one uninterrupted gap spans all retries");
        for (Reason reason : reasons) check(closed.includes(reason),"all observed cause flags retained");
        check(closed.retriesUsed == 3,"attempt history bounded and honest");
    }
    private static void cancellation() {
        for (int stage = 0; stage < 6; stage++) for (boolean stop : new boolean[]{false,true}) {
            InterruptionRecovery c = controller(3); Lease initial = c.explicitStart(true,true,0); Lease current = initial;
            if (stage >= 1) check(c.captureConfirmed(initial,1),"cancel fixture running");
            if (stage >= 2) check(c.interrupted(initial,Reason.ROUTE_UNAVAILABLE,2),"cancel fixture waiting");
            Retry oldTimer = c.snapshot().retry;
            if (stage >= 3) { check(c.released(initial),"cancel fixture released"); current = null; }
            if (stage >= 4) current = c.takeRetry(oldTimer,true,102);
            if (stage >= 5) check(c.captureConfirmed(current,103),"cancel fixture recovered");
            if (stop) c.stop(1000); else c.pause(1000);
            check(c.snapshot().state == (stop ? State.STOPPED : State.PAUSED),"explicit control wins");
            check(c.snapshot().retry == null && !c.snapshot().recoveryEnabled,"pending timer revoked");
            check(c.takeRetry(oldTimer,true,1000) == null,"cancelled timer cannot revive");
            check(!c.captureConfirmed(initial,1000) && !c.captureConfirmed(current,1000),"cancelled start callback cannot revive");
            if (current != null) {
                check(c.snapshot().releaseRequired && c.explicitStart(true,true,1000) == null,"control does not pretend physical release");
                check(c.released(current),"stale-generation ownership can finish release");
            }
            acknowledgeAll(c); Lease newer = c.explicitStart(true,true,1000);
            check(newer != null && newer.generation > initial.generation,"new explicit session after release/evidence");
            check(!c.released(initial),"late release cannot retire newer owner");
        }
        InterruptionRecovery c = controller(3); Lease l = running(c);
        c.disableRecovery(2);
        check(c.snapshot().state == State.RUNNING && !c.snapshot().releaseRequired,"opt-out preserves healthy run");
        check(c.interrupted(l,Reason.DEAD_RECORDER,3) && c.snapshot().lastEnd == End.OPTED_OUT,"subsequent failure obeys opt-out");
        c = controller(3); l = c.explicitStart(true,true,0); c.disableRecovery(1);
        check(c.captureConfirmed(l,2),"opt-out preserves initial explicit attempt");
        c = controller(3); l = running(c); c.interrupted(l,Reason.MICROPHONE_CONTENTION,2);
        Retry r = c.snapshot().retry; c.released(l); l = c.takeRetry(r,true,102); c.disableRecovery(103);
        check(c.snapshot().state == State.PAUSED && c.snapshot().releaseRequired,"opt-out cancels in-flight retry");
        check(!c.captureConfirmed(l,104) && c.released(l),"cancelled retry still releases safely");
    }
    private static void authorityTimeAndIdentity() throws Exception {
        InterruptionRecovery c = controller(3); Lease l = running(c);
        c.interrupted(l,Reason.DEAD_RECORDER,2); Retry r = c.snapshot().retry;
        check(c.takeRetry(r,false,3) == null,"fresh OS authority checked before retry");
        check(c.snapshot().state == State.PAUSED && c.snapshot().lastEnd == End.AUTHORITY_LOST,"authority loss is terminal for automatic recovery");
        check(c.released(l),"authority loss does not forget old recorder");
        check(c.takeRetry(r,true,1000) == null,"permission restoration alone never restarts");
        List<GapEvent> admissionGaps = acknowledgeAll(c);
        GapEvent admissionEnd = admissionGaps.get(1);
        check(admissionEnd.includes(Reason.DEAD_RECORDER) && admissionEnd.includes(Reason.SERVICE_AUTHORITY_LOST)
            && admissionEnd.lastReason == Reason.SERVICE_AUTHORITY_LOST,"failed retry admission accumulates authority loss");
        check(admissionEnd.kind == EventKind.TERMINATED && admissionEnd.endMillis == -1,"failed authority check never closes audio gap");
        c = controller(3); l = running(c); c.interrupted(l,Reason.ROUTE_UNAVAILABLE,2); c.authorityLost(3);
        List<GapEvent> backoffGaps = acknowledgeAll(c); GapEvent backoffEnd = backoffGaps.get(1);
        check(backoffGaps.size() == 2 && backoffEnd.includes(Reason.ROUTE_UNAVAILABLE)
            && backoffEnd.includes(Reason.SERVICE_AUTHORITY_LOST) && backoffEnd.lastReason == Reason.SERVICE_AUTHORITY_LOST,
            "explicit authority loss during backoff updates the same gap");
        check(backoffEnd.observedAtMillis == 3 && backoffEnd.endMillis == -1
            && backoffEnd.end == End.AUTHORITY_LOST && c.released(l),"backoff termination and release remain separate");
        c = controller(3); l = running(c); c.authorityLost(2);
        List<GapEvent> gaps = acknowledgeAll(c);
        check(gaps.size() == 2 && gaps.get(1).includes(Reason.SERVICE_AUTHORITY_LOST)
            && gaps.get(1).end == End.AUTHORITY_LOST,"authority interruption evidence retained");
        InterruptionRecovery other = controller(3); Lease foreign = running(other);
        check(!c.released(foreign) && !c.captureConfirmed(foreign,3),"foreign controller lease rejected");
        other.interrupted(foreign,Reason.ROUTE_UNAVAILABLE,2);
        check(c.takeRetry(other.snapshot().retry,true,1000) == null,"foreign timer rejected");
        check(!c.acknowledgeGap(other.peekGap()),"foreign gap ack rejected");
        c = controller(1);
        try { c.explicitStart(true,true,-1); throw new AssertionError("negative clock accepted"); }
        catch (IllegalArgumentException expected) { check(c.snapshot().state == State.STOPPED,"negative time atomic"); }
        l = c.explicitStart(true,true,100); c.captureConfirmed(l,101); c.interrupted(l,Reason.DEAD_RECORDER,102);
        r = c.snapshot().retry;
        try { c.pause(101); throw new AssertionError("clock reversal accepted"); }
        catch (IllegalArgumentException expected) { check(c.snapshot().retry == r && c.snapshot().state == State.BACKOFF,"clock reversal leaves state/evidence intact"); }
        c = controller(1); l = c.explicitStart(true,true,Long.MAX_VALUE-50);
        check(c.interrupted(l,Reason.DEAD_RECORDER,Long.MAX_VALUE-49),"clock-end failure observed");
        check(c.snapshot().lastEnd == End.CLOCK_EXHAUSTED && c.snapshot().retry == null,"deadline arithmetic cannot wrap to immediate retry");
        c = controller(1);
        Field generation = InterruptionRecovery.class.getDeclaredField("generation"); generation.setAccessible(true);
        generation.setLong(c,Long.MAX_VALUE);
        try { c.explicitStart(true,true,0); throw new AssertionError("generation wrapped"); }
        catch (IllegalStateException expected) { check(c.snapshot().lease == null,"generation exhaustion issues no lease"); }
        c.stop(0); check(c.snapshot().generation == Long.MAX_VALUE,"cancellation never wraps generation");
    }
    private static void concurrentStopAndRetry() throws Exception {
        for (int n = 0; n < 16; n++) {
            final InterruptionRecovery c = controller(2); Lease initial = running(c);
            c.interrupted(initial,Reason.DEAD_RECORDER,2); c.released(initial);
            final Retry timer = c.snapshot().retry;
            final CountDownLatch gate = new CountDownLatch(1);
            final AtomicReference<Lease> issued = new AtomicReference<Lease>();
            final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            Thread retry = new Thread(new Runnable() { @Override public void run() {
                try { gate.await(); issued.set(c.takeRetry(timer,true,1000)); }
                catch (Throwable e) { failure.compareAndSet(null,e); }
            } });
            Thread stop = new Thread(new Runnable() { @Override public void run() {
                try { gate.await(); c.stop(1000); }
                catch (Throwable e) { failure.compareAndSet(null,e); }
            } });
            retry.setDaemon(true); stop.setDaemon(true); retry.start(); stop.start(); gate.countDown();
            retry.join(2000); stop.join(2000);
            check(!retry.isAlive() && !stop.isAlive() && failure.get() == null,"bounded racing callbacks finish");
            check(c.snapshot().state == State.STOPPED && c.snapshot().retry == null,"Stop wins regardless of ordering");
            Lease candidate = issued.get();
            check(!c.captureConfirmed(candidate,1000),"racing issued lease cannot start after Stop");
            if (candidate != null) check(c.snapshot().lease == candidate && c.released(candidate),"racing physical create remains fenced through release");
            else check(c.snapshot().lease == null,"cancelled-before-admission issues no recorder");
        }
    }
    private static void invalidConfiguration() {
        long[][] invalid = {{-1,100,100},{9,100,100},{1,99,100},{1,200,100},{1,100,300001}};
        for (long[] values : invalid) {
            try { new InterruptionRecovery((int)values[0],values[1],values[2]); throw new AssertionError("invalid limits accepted"); }
            catch (IllegalArgumentException expected) { check(true,"invalid bounds rejected"); }
        }
    }
    public static void main(String[] args) throws Exception {
        invalidConfiguration(); optInAndOwnership(); retryWhitelisting(); budgetAndEvidence();
        cancellation(); authorityTimeAndIdentity(); concurrentStopAndRetry();
        System.out.println("PASS " + assertions + " finite interruption-policy assertions; no Android/runtime/durability proof");
    }
}
