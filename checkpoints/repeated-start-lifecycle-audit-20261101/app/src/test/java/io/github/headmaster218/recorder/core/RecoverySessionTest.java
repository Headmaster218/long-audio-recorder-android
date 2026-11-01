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
        int authorityCalls, failAuthorityAt;
        int finished;
        boolean released, cleanPause;
        final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
        final List<Runnable> timers = new ArrayList<Runnable>();
        final List<Attempt> attempts = new ArrayList<Attempt>();
        final List<Write> writes = new ArrayList<Write>();
        final RecoverySession session = new RecoverySession("capture",0,false,this,this,this);
        String status;
        @Override public long nowMillis() { return now; }
        @Override public boolean authorized() {
            authorityCalls++;
            if(failAuthority || authorityCalls==failAuthorityAt)throw new SecurityException("authority lookup failed");
            return authority;
        }
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
    /** Repeated explicit Start must not reopen a coordinator during cleanup or a pending journal. */
    private static void repeatedStartDuringStopCleanup() {
        int before=assertions;
        for(boolean storageClosed:new boolean[]{true,false}) {
            Harness h=new Harness();check(h.session.start(true));Attempt original=h.latest();
            check(!h.session.start(true));check(h.attempts.size()==1 && original.starts==1);
            h.session.stop();
            check(!h.session.start(true));check(h.finished==0 && original.lastStop==RecoverySession.Stop.STOP);
            // Delayed PCM and Pause callbacks do not undo Stop while physical cleanup is pending.
            original.first();h.session.pause();h.drain();
            check(h.session.snapshot().state!=InterruptionRecovery.State.RUNNING);
            check(!h.session.start(true));check(h.attempts.size()==1 && h.timers.isEmpty());
            original.finish(null,CaptureEvidence.Release.RETURNED,true,storageClosed,false,true);h.drain();
            check(h.finished==0 && !h.writes.isEmpty()); // Physical release alone is not journal proof.
            check(!h.session.start(true));check(h.attempts.size()==1 && h.timers.isEmpty());
            h.acknowledgeAll();
            check(h.finished==1 && h.released==storageClosed && !h.cleanPause);
            check(!h.session.start(true));
            original.first();original.finish(null,CaptureEvidence.Release.RETURNED,true,true,false,true);
            h.session.pause();h.session.stop();h.acknowledgeAll();
            check(h.finished==1 && h.released==storageClosed && !h.cleanPause);
            check(h.attempts.size()==1 && h.timers.isEmpty());
        }
        System.out.println("PASS "+(assertions-before)+" repeated-Start/delayed-callback cleanup assertions; uncertain storage remains blocked");
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
    private static void authorityFailureAtFirstPcm() {
        Harness h=new Harness();check(h.session.start(true));Attempt attempt=h.latest();
        h.failAuthority=true;attempt.first();h.drain();
        check(attempt.stops==1 && attempt.lastStop==RecoverySession.Stop.INTERRUPTED);
        check(h.session.snapshot().state!=InterruptionRecovery.State.RUNNING);
        check(h.finished==0 && h.timers.isEmpty() && h.attempts.size()==1);
        h.acknowledgeAll();check(h.finished==0); // A query error is not release evidence.
        attempt.finish(null,CaptureEvidence.Release.RETURNED,true,true,false,false);h.acknowledgeAll();
        check(h.finished==1 && h.released && !h.cleanPause && h.attempts.size()==1 && h.timers.isEmpty());
        attempt.first();h.drain();check(h.finished==1 && h.attempts.size()==1);
    }
    private static void authorityFailureAtCompletion() {
        Harness h=new Harness();check(h.session.start(true));Attempt attempt=h.latest();
        h.failAuthority=true;attempt.interrupted();h.drain();
        check(h.finished==0 && h.timers.isEmpty() && h.attempts.size()==1);
        h.acknowledgeAll();
        check(h.finished==1 && h.released && !h.cleanPause && h.timers.isEmpty() && h.attempts.size()==1);
        attempt.interrupted();h.drain();check(h.finished==1);
    }
    private static void authorityFailureAtRetryTimer() {
        Harness h=waiting();Runnable stale=h.timers.get(0);h.failAuthority=true;h.fire();
        check(h.attempts.size()==1 && h.timers.isEmpty() && h.finished==0);
        h.acknowledgeAll();
        check(h.finished==1 && h.released && !h.cleanPause && h.attempts.size()==1 && h.timers.isEmpty());
        stale.run();h.drain();check(h.finished==1 && h.attempts.size()==1);
    }
    private static void authorityFailureBeforeCreate() {
        Harness h=new Harness();h.failAuthorityAt=2;check(h.session.start(true));
        check(h.authorityCalls==2 && h.attempts.isEmpty() && h.timers.isEmpty());
        check(!h.session.abortBeforeStart());h.acknowledgeAll();
        check(h.finished==1 && h.released && !h.cleanPause && h.attempts.isEmpty());
        check(!h.session.start(true)); // Recovery of the query itself grants no restart authority.
    }
    private static void authorityFailureCase(String name) {
        if ("authority-first-pcm".equals(name)) authorityFailureAtFirstPcm();
        else if ("authority-completion".equals(name)) authorityFailureAtCompletion();
        else if ("authority-retry-timer".equals(name)) authorityFailureAtRetryTimer();
        else if ("authority-before-create".equals(name)) authorityFailureBeforeCreate();
        else if ("authority-after-journal".equals(name)) authorityFailureAfterJournal();
        else if ("authority-retry-launch".equals(name)) authorityFailureAtRetryLaunch();
        else if ("authority-uncertain-cleanup".equals(name)) authorityFailureWithUncertainCleanup();
        else if ("authority-restored".equals(name)) restoredAuthorityDoesNotRestart();
        else if ("notification-authority-loss".equals(name)) notificationAuthorityLoss();
        else throw new IllegalArgumentException("Unknown finite case: "+name);
    }
    private static boolean authorityLostRecorded(Harness h) {
        for(Write w:h.writes)if(w.record.contains("\nend=AUTHORITY_LOST\n"))return true;
        return false;
    }
    private static void authorityFailureAfterJournal() {
        Harness h=new Harness();check(h.session.start(true));h.latest().interrupted();h.drain();
        check(h.writes.size()==1 && h.timers.isEmpty());h.failAuthority=true;h.acknowledgeAll();
        check(h.finished==1 && h.released && h.attempts.size()==1 && h.timers.isEmpty());
        check(authorityLostRecorded(h));
    }
    private static void authorityFailureAtRetryLaunch() {
        Harness h=waiting();h.failAuthorityAt=h.authorityCalls+2;h.fire();
        check(h.authorityCalls==h.failAuthorityAt && h.attempts.size()==1 && h.timers.isEmpty());
        h.acknowledgeAll();check(h.finished==1 && h.released && !h.cleanPause && authorityLostRecorded(h));
    }
    private static void authorityFailureWithUncertainCleanup() {
        for(int uncertainty=0;uncertainty<3;uncertainty++) {
            Harness h=new Harness();check(h.session.start(true));h.failAuthority=true;
            h.failJournal=uncertainty==2;
            h.latest().finish(CaptureEvidence.Failure.ROUTE_UNAVAILABLE,
                uncertainty==0?CaptureEvidence.Release.UNCONFIRMED:CaptureEvidence.Release.RETURNED,
                true,uncertainty!=1,false,false);
            h.acknowledgeAll();
            check(h.finished==1 && !h.released && !h.cleanPause && h.attempts.size()==1 && h.timers.isEmpty());
        }
    }
    private static void restoredAuthorityDoesNotRestart() {
        Harness h=new Harness();check(h.session.start(true));Attempt attempt=h.latest();
        h.failAuthority=true;attempt.first();h.drain();check(attempt.lastStop==RecoverySession.Stop.INTERRUPTED);
        h.failAuthority=false;h.authority=true;attempt.first();h.drain();
        h.acknowledgeAll();check(h.finished==0 && h.attempts.size()==1 && h.timers.isEmpty());
        attempt.finish(null,CaptureEvidence.Release.RETURNED,true,true,false,false);h.acknowledgeAll();
        check(h.finished==1 && h.released && !h.cleanPause && h.attempts.size()==1 && h.timers.isEmpty());
        check(authorityLostRecorded(h) && !h.session.start(true));
    }
    private static void notificationAuthorityLoss() {
        Harness h=new Harness();h.session.authorityUnavailable();check(h.session.start(true));
        Attempt attempt=h.latest();h.session.authorityUnavailable();h.session.authorityUnavailable();
        check(attempt.stops==1 && attempt.lastStop==RecoverySession.Stop.INTERRUPTED);
        h.acknowledgeAll();check(h.finished==0 && h.timers.isEmpty());
        attempt.finish(null,CaptureEvidence.Release.RETURNED,true,true,false,false);h.acknowledgeAll();
        check(h.finished==1 && h.released && !h.cleanPause && authorityLostRecorded(h));
        h.session.authorityUnavailable();check(h.finished==1 && attempt.stops==1);
        h=new Harness();check(h.session.start(true));attempt=h.latest();h.session.pause();
        h.session.authorityUnavailable();attempt.finish(null,CaptureEvidence.Release.RETURNED,true,true,false,true);h.acknowledgeAll();
        check(h.finished==1 && h.released && !h.cleanPause);
        // A query failure already revoked authority; the queued notification callback is idempotent.
        h=new Harness();check(h.session.start(true));attempt=h.latest();h.failAuthority=true;attempt.first();h.drain();
        int stops=attempt.stops;h.session.authorityUnavailable();h.session.authorityUnavailable();check(attempt.stops==stops);
        attempt.finish(null,CaptureEvidence.Release.UNCONFIRMED,true,true,false,false);h.acknowledgeAll();
        check(h.finished==1 && !h.released && !h.cleanPause && h.attempts.size()==1);
        h=new Harness();check(h.session.start(true));h.session.pause();h.failAuthority=true;
        h.latest().finish(null,CaptureEvidence.Release.RETURNED,true,true,false,true);h.acknowledgeAll();
        check(h.finished==1 && h.released && !h.cleanPause);
    }
    private static void explicitStopDominance() {
        int cases=0;
        for(boolean journalPending:new boolean[]{false,true}) for(int length=1;length<=5;length++) for(int mask=0;mask<(1<<length);mask++) {
            Harness h=new Harness();check(h.session.start(true));Attempt a=h.latest();
            h.session.pause();
            if(journalPending){a.finish(null,CaptureEvidence.Release.RETURNED,true,true,false,true);h.drain();}
            boolean stopped=false;
            for(int i=0;i<length;i++) {
                if((mask&(1<<i))!=0){stopped=true;h.session.stop();}else h.session.pause();
            }
            if(!journalPending)a.finish(null,CaptureEvidence.Release.RETURNED,true,true,false,true);
            h.acknowledgeAll();
            check(h.finished==1 && h.released && h.attempts.size()==1 && h.timers.isEmpty());
            if(h.cleanPause==stopped)throw new AssertionError("Stop dominance: pending="+journalPending+" length="+length+" mask="+mask+" cleanPause="+h.cleanPause);
            h.session.pause();h.session.stop();a.finish(null,CaptureEvidence.Release.RETURNED,true,true,false,true);h.acknowledgeAll();
            check(h.finished==1 && h.cleanPause!=stopped);cases++;
        }
        System.out.println("Stop dominance: "+cases+" fresh action/order cases; ordinary Pause preserved, no replacement recorder");
    }
    private static void resourceFreeBusyReleasesAdmission() {
        Harness h=new Harness();check(h.session.start(true));Attempt a=h.latest();
        CaptureEvidence e=new CaptureEvidence(a.capture,a.run);e.failed(CaptureEvidence.Failure.INITIALIZATION);
        a.events.finished(a,e.finish(new PcmReadAccounting().snapshot(),true),"pre-resource admission busy",false,1);
        h.acknowledgeAll();check(h.finished==1 && h.released && !h.cleanPause && h.timers.isEmpty() && h.attempts.size()==1);
    }
    public static void main(String[] args) {
        if (args.length>0) {
            for (String name:args) authorityFailureCase(name);
            System.out.println("PASS " + assertions + " selected authority-failure assertions; deterministic host ports, no Android runtime");
            return;
        }
        resourceFreeBusyReleasesAdmission();explicitStopDominance();
        preAdmissionAbort();optOutAndCleanPause();cancellationOrders();repeatedStartDuringStopCleanup();journalCallbacks();timerAndDuplicateFinal();dispatchFailuresAndDestroyOrders();ownershipAndStorage();staleEngineAndAuthority();
        authorityFailureAtFirstPcm();authorityFailureAtCompletion();authorityFailureAtRetryTimer();authorityFailureBeforeCreate();
        authorityFailureAfterJournal();authorityFailureAtRetryLaunch();authorityFailureWithUncertainCleanup();restoredAuthorityDoesNotRestart();
        notificationAuthorityLoss();
        System.out.println("PASS " + assertions + " production recovery-session ordering assertions; deterministic host ports, no Android runtime");
    }
}
