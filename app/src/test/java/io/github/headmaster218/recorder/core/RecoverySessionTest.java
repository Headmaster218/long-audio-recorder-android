package io.github.headmaster218.recorder.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Deterministic queued callbacks against the production coordinator, not an Android emulation. */
public final class RecoverySessionTest {
    private static int assertions;
    private static void check(boolean ok) { assertions++; if (!ok) throw new AssertionError("assertion " + assertions); }
    private static final class Harness implements RecoverySession.Host, RecoverySession.Engines, RecoverySession.Journal {
        long now;
        boolean authority = true, failStart, failCreate, invalidIdentity, failJournal, failAuthority;
        int finished;
        boolean released, cleanPause;
        final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
        final List<Runnable> timers = new ArrayList<Runnable>();
        final List<Attempt> attempts = new ArrayList<Attempt>();
        final List<Write> writes = new ArrayList<Write>();
        final RecoverySession session = new RecoverySession("capture",0,false,this,this,this);
        String status;
        @Override public long nowMillis() { return now; }
        @Override public boolean authorized() { if(failAuthority)throw new SecurityException("authority lookup failed");return authority; }
        @Override public void post(Runnable work) { queue.add(work); }
        @Override public void postDelayed(Runnable work,long delay) { check(delay >= 0); timers.add(work); }
        @Override public void remove(Runnable work) { timers.remove(work); }
        @Override public void status(String text) { status = text; }
        @Override public void finished(String text,boolean paused,long epoch,boolean admissionReleased) {
            finished++; status = text; cleanPause = paused; released = admissionReleased;
        }
        @Override public RecoverySession.Attempt create(String capture,long epoch,boolean resumed,RecoverySession.Events events) {
            if(failCreate)throw new IllegalStateException("create failed");
            Attempt a = new Attempt(invalidIdentity?"foreign":capture,"run-"+attempts.size(),epoch,resumed,events,this);attempts.add(a);return a;
        }
        @Override public void persist(String session,int ordinal,String record,RecoverySession.JournalCompletion completion) {
            if(failJournal)throw new IllegalStateException("journal dispatch failed");
            writes.add(new Write(ordinal,record,completion));
        }
        void drain() { int limit=100;while (!queue.isEmpty()) { check(--limit>0);queue.remove().run(); } }
        void acknowledgeAll() { int limit=100;while (true) { drain();Write next=null;for (Write w:writes) if (!w.done) {next=w;break;}if(next==null)break;check(--limit>0);next.done=true;next.callback.complete(false,null); }drain(); }
        void fire() { check(timers.size()==1);Runnable timer=timers.remove(0);now+=30000;timer.run();drain(); }
        Attempt latest() { return attempts.get(attempts.size()-1); }
    }
    private static final class Write {
        final int ordinal; final String record; final RecoverySession.JournalCompletion callback;boolean done;
        Write(int ordinal,String record,RecoverySession.JournalCompletion callback) { this.ordinal=ordinal;this.record=record;this.callback=callback; }
    }
    private static final class Attempt implements RecoverySession.Attempt {
        final String capture,run;final long epoch;final boolean resumed;final RecoverySession.Events events;final Harness host;
        int starts,stops;RecoverySession.Stop lastStop;
        Attempt(String capture,String run,long epoch,boolean resumed,RecoverySession.Events events,Harness host) { this.capture=capture;this.run=run;this.epoch=epoch;this.resumed=resumed;this.events=events;this.host=host; }
        @Override public String captureId() { return capture; }
        @Override public String runId() { return run; }
        @Override public void start() { if(host.failStart)throw new IllegalStateException("not started");starts++; }
        @Override public void stop(RecoverySession.Stop reason) { stops++;lastStop=reason; }
        void first() {
            CaptureEvidence e=new CaptureEvidence(capture,run);
            events.firstPcm(this,e.appended(2,1,true,0,1,2,3));
        }
        void finish(CaptureEvidence.Failure failure,CaptureEvidence.Release release,boolean terminated,boolean closed,boolean uncertain,boolean paused) {
            CaptureEvidence e=new CaptureEvidence(capture,run);
            if(release!=CaptureEvidence.Release.NO_HANDLE) { e.handleObtained();if(release==CaptureEvidence.Release.RETURNED)e.releaseReturned();if(release==CaptureEvidence.Release.THREW)e.releaseThrew(); }
            e.openingSpool();e.openingWriter();if(closed){e.spoolClosed();e.writerClosed();}
            if(failure!=null)e.failed(failure);
            PcmReadAccounting a=new PcmReadAccounting();a.readAccepted(8);a.appendConfirmed(uncertain?4:8);
            events.finished(this,e.finish(a.snapshot(),terminated),"observed result",paused,epoch+1);
        }
        void interrupted() { finish(CaptureEvidence.Failure.ROUTE_UNAVAILABLE,CaptureEvidence.Release.RETURNED,true,true,true,false); }
    }
    private static Harness waiting() {
        Harness h=new Harness();check(h.session.start(true));h.latest().interrupted();h.drain();
        check(h.writes.size()==1 && h.timers.isEmpty());h.acknowledgeAll();check(h.timers.size()==1);return h;
    }
    private static void optOutAndCleanPause() {
        Harness h=new Harness();check(h.session.start(false));h.latest().interrupted();h.acknowledgeAll();
        check(h.attempts.size()==1 && h.timers.isEmpty() && h.finished==1 && h.released);
        h=new Harness();check(h.session.start(true));h.session.pause();h.latest().finish(null,CaptureEvidence.Release.RETURNED,true,true,false,true);h.acknowledgeAll();
        check(h.cleanPause && h.released && h.finished==1);
        check(!h.session.start(true));
    }
    private static void cancellationOrders() {
        for(boolean pause:new boolean[]{false,true}) {
            Harness h=waiting();Runnable stale=h.timers.get(0);if(pause)h.session.pause();else h.session.stop();
            stale.run();h.acknowledgeAll();check(h.attempts.size()==1 && h.timers.isEmpty() && h.released && !h.cleanPause);
            h=new Harness();check(h.session.start(true));h.latest().interrupted();h.drain();
            if(pause)h.session.pause();else h.session.stop();h.acknowledgeAll();check(h.attempts.size()==1 && h.released && !h.cleanPause);
            h=waiting();h.fire();Attempt second=h.latest();if(pause)h.session.pause();else h.session.stop();
            second.first();h.drain();check(h.session.snapshot().state!=InterruptionRecovery.State.RUNNING);
            second.finish(null,CaptureEvidence.Release.RETURNED,true,true,false,pause);h.acknowledgeAll();check(h.released);
        }
    }
    private static void journalCallbacks() {
        Harness h=new Harness();check(h.session.start(true));h.latest().interrupted();h.drain();Write old=h.writes.get(0);
        old.done=true;old.callback.complete(false,null);old.callback.complete(false,null);h.drain();
        check(h.writes.size()==2 && h.writes.get(1).ordinal==1 && h.finished==0);
        old.callback.complete(false,"stale failure");h.drain();check(h.finished==0);h.acknowledgeAll();check(h.timers.size()==1);
        h=new Harness();check(h.session.start(true));h.latest().interrupted();h.drain();h.writes.get(0).callback.complete(false,"disk full");h.drain();
        check(h.finished==1 && !h.released && h.timers.isEmpty() && h.status.contains("durably saved"));
        h=new Harness();check(h.session.start(true));h.latest().interrupted();h.drain();Write warning=h.writes.get(0);warning.done=true;warning.callback.complete(true,null);h.acknowledgeAll();
        check(h.status.contains("past gap coverage is incomplete"));
    }
    private static void timerAndDuplicateFinal() {
        Harness h=waiting();Runnable retiredTimer=h.timers.get(0);h.fire();Attempt second=h.latest();
        second.interrupted();h.acknowledgeAll();check(h.timers.size()==1 && h.attempts.size()==2);
        retiredTimer.run();h.drain();check(h.timers.size()==1 && h.attempts.size()==2);
        h.fire();check(h.attempts.size()==3);
        h.session.stop();h.latest().finish(null,CaptureEvidence.Release.RETURNED,true,true,false,false);h.acknowledgeAll();
        int terminal=h.finished;h.latest().finish(null,CaptureEvidence.Release.RETURNED,true,true,false,false);h.drain();check(h.finished==terminal);
        h=waiting();Runnable early=h.timers.remove(0);early.run();h.drain();check(h.attempts.size()==1 && h.timers.size()==1);
        h.session.stop();h.acknowledgeAll();check(h.released);
    }
    private static void preAdmissionAbort() {
        Harness h=new Harness();check(h.session.abortBeforeStart());check(!h.session.start(true));
        check(h.attempts.isEmpty() && h.writes.isEmpty() && h.timers.isEmpty());
        h=new Harness();h.failAuthority=true;
        try { h.session.start(true);check(false); } catch(SecurityException expected) { check(true); }
        check(h.session.abortBeforeStart());check(!h.session.abortBeforeStart());
        check(h.attempts.isEmpty() && h.writes.isEmpty() && h.timers.isEmpty());
        h=new Harness();h.failCreate=true;check(h.session.start(true));check(!h.session.abortBeforeStart());check(!h.released);
        h=new Harness();check(h.session.start(true));check(!h.session.abortBeforeStart());check(h.latest().starts==1);
    }
    private static void dispatchFailuresAndDestroyOrders() {
        Harness h=new Harness();h.failCreate=true;check(h.session.start(true));h.drain();
        check(h.attempts.isEmpty() && h.finished==1 && !h.released);
        h=new Harness();h.invalidIdentity=true;check(h.session.start(true));h.drain();
        check(h.latest().starts==0 && h.finished==1 && !h.released);
        h=new Harness();h.failJournal=true;check(h.session.start(true));h.latest().interrupted();h.drain();
        check(h.writes.isEmpty() && h.finished==1 && !h.released && h.timers.isEmpty());
        h=new Harness();check(h.session.start(true));h.session.pause();h.latest().finish(null,CaptureEvidence.Release.RETURNED,true,true,false,true);h.drain();
        check(h.writes.size()==1);h.session.stop();h.acknowledgeAll();check(h.released && !h.cleanPause);
        h=new Harness();check(h.session.start(true));h.latest().interrupted();h.drain();h.authority=false;h.session.destroy();h.acknowledgeAll();
        check(h.released && h.attempts.size()==1 && h.timers.isEmpty());
        h=waiting();Runnable stale=h.timers.get(0);h.authority=false;h.session.destroy();stale.run();h.acknowledgeAll();
        check(h.released && h.attempts.size()==1 && h.timers.isEmpty());
        h=waiting();h.fire();Attempt second=h.latest();second.first();h.drain();
        h.authority=false;h.session.destroy();check(second.lastStop==RecoverySession.Stop.INTERRUPTED);
        second.finish(null,CaptureEvidence.Release.RETURNED,true,true,false,false);h.acknowledgeAll();
        check(h.released && h.attempts.size()==2 && h.timers.isEmpty());
    }
    private static void ownershipAndStorage() {
        for(CaptureEvidence.Release release:new CaptureEvidence.Release[]{CaptureEvidence.Release.THREW,CaptureEvidence.Release.UNCONFIRMED}) {
            Harness h=new Harness();check(h.session.start(true));h.latest().finish(CaptureEvidence.Failure.DEAD_RECORDER,release,true,true,false,false);h.acknowledgeAll();
            check(h.finished==1 && !h.released && h.timers.isEmpty());
        }
        Harness h=new Harness();check(h.session.start(true));h.latest().finish(CaptureEvidence.Failure.ROUTE_UNAVAILABLE,CaptureEvidence.Release.NO_HANDLE,false,true,false,false);h.acknowledgeAll();
        check(h.finished==0 && h.timers.isEmpty() && h.attempts.size()==1);
        h=new Harness();check(h.session.start(true));h.latest().finish(CaptureEvidence.Failure.ROUTE_UNAVAILABLE,CaptureEvidence.Release.RETURNED,true,false,false,false);h.acknowledgeAll();
        check(h.finished==1 && !h.released && h.timers.isEmpty());
        h=waiting();check(h.writes.get(0).record.contains("unconfirmedUpperBoundSamples=4") && h.writes.get(0).record.contains("replay=never"));
        h.fire();check(h.attempts.size()==2 && h.latest().resumed && h.latest().epoch==1 && h.latest().capture.equals("capture"));
    }
    private static void staleEngineAndAuthority() {
        Harness h=waiting();Attempt first=h.latest();h.fire();Attempt second=h.latest();int before=h.writes.size();
        first.first();first.interrupted();h.drain();check(h.attempts.size()==2 && h.writes.size()==before && second.stops==0);
        second.first();h.drain();check(h.session.snapshot().state==InterruptionRecovery.State.RUNNING);
        h.session.stop();check(second.lastStop==RecoverySession.Stop.STOP);second.finish(null,CaptureEvidence.Release.RETURNED,true,true,false,false);h.acknowledgeAll();
        check(h.finished==1 && h.released);
        h=waiting();h.authority=false;h.fire();h.acknowledgeAll();check(h.attempts.size()==1 && h.released);
        h=new Harness();check(h.session.start(true));h.authority=false;h.session.destroy();h.latest().first();h.drain();
        check(h.latest().lastStop==RecoverySession.Stop.INTERRUPTED && h.session.snapshot().state!=InterruptionRecovery.State.RUNNING);
        h.latest().finish(null,CaptureEvidence.Release.RETURNED,true,true,false,false);h.acknowledgeAll();check(h.released);
        h=new Harness();h.failStart=true;check(h.session.start(true));h.acknowledgeAll();check(h.latest().starts==0 && h.released && h.timers.isEmpty());
    }
    public static void main(String[] args) {
        preAdmissionAbort();optOutAndCleanPause();cancellationOrders();journalCallbacks();timerAndDuplicateFinal();dispatchFailuresAndDestroyOrders();ownershipAndStorage();staleEngineAndAuthority();
        System.out.println("PASS " + assertions + " production recovery-session ordering assertions; deterministic host ports, no Android runtime");
    }
}
