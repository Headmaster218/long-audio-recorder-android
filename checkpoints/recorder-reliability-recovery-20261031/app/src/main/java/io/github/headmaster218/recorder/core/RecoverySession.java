package io.github.headmaster218.recorder.core;

import java.util.UUID;

/** Main-lane coordinator used by the Android service and finite host ordering tests. No Android/I/O. */
public final class RecoverySession {
    public enum Stop { STOP, PAUSE, INTERRUPTED }
    public interface Host {
        long nowMillis();
        boolean authorized();
        void post(Runnable work);
        void postDelayed(Runnable work,long delayMillis);
        void remove(Runnable work);
        void status(String text);
        void finished(String text,boolean cleanPause,long nextEpoch,boolean admissionReleased);
    }
    public interface Attempt {
        String captureId();
        String runId();
        /** A RuntimeException means this fresh worker did not start and acquired no recorder. */
        void start();
        void stop(Stop reason);
    }
    public interface Events {
        void firstPcm(Attempt attempt,CaptureEvidence.FirstPcm first);
        void finished(Attempt attempt,CaptureEvidence.Result result,String text,boolean paused,long nextEpoch);
    }
    public interface Engines { Attempt create(String captureId,long epoch,boolean resumed,Events events); }
    public interface JournalCompletion { void complete(boolean historicalUncertainty,String failure); }
    public interface Journal { void persist(String sessionId,int ordinal,String record,JournalCompletion completion); }

    private final String id = UUID.randomUUID().toString(), captureId;
    private final Host host;
    private final Engines engines;
    private final Journal journal;
    private final InterruptionRecovery policy = new InterruptionRecovery(8,1000,30000);
    private InterruptionRecovery.Lease lease;
    private Attempt active;
    private CaptureEvidence.Result lastEvidence, pendingOutcome;
    private CaptureEvidence.FirstPcm first;
    private long nextEpoch;
    private int ordinal;
    private boolean resumed, blocked, journalFailed, userPaused, historicalUncertainty, cleanPauseEligible;
    private boolean started, destroyed, terminalNotified, notificationAuthorityLost, explicitStop;
    private String status = "Starting visible microphone service";
    private Runnable timer;
    private Write writing;
    private static final class Write {
        final CaptureEvidence.Result outcome;
        final InterruptionRecovery.GapEvent event;
        Write(CaptureEvidence.Result outcome,InterruptionRecovery.GapEvent event) { this.outcome=outcome;this.event=event; }
    }
    public RecoverySession(String captureId,long epoch,boolean resumed,Host host,Engines engines,Journal journal) {
        if (captureId==null || captureId.isEmpty() || epoch<0 || host==null || engines==null || journal==null) throw new IllegalArgumentException();
        this.captureId=captureId;nextEpoch=epoch;this.resumed=resumed;this.host=host;this.engines=engines;this.journal=journal;
    }
    private boolean authority() {
        if (destroyed || notificationAuthorityLost) return false;
        try { return host.authorized(); }
        catch (RuntimeException failure) {
            // Preserve pre-admission failure/abort semantics. Once admitted, an
            // unavailable authority query must cancel through the normal evidence
            // and ownership gates, never escape a main-lane callback or grant a retry.
            if (!started) throw failure;
            return false;
        }
    }
    private long now() { return host.nowMillis(); }
    public InterruptionRecovery.Snapshot snapshot() { return policy.snapshot(); }
    /** Caller must already hold the process admission slot and have an authorized visible microphone FGS. */
    public boolean start(boolean optIn) {
        if (started || destroyed || terminalNotified) return false;
        lease=policy.explicitStart(optIn,authority(),now());if(lease==null)return false;
        started=true;launch(lease);return true;
    }
    /** Returns proof that no attempt was admitted; caller must release its slot and finish its UI. */
    public boolean abortBeforeStart() {
        if (started || terminalNotified) return false;
        terminalNotified=true;return true;
    }
    public void pause() { cancel(true); }
    public void stop() { cancel(false); }
    /** Main-lane notification failure: revoke this session, without inventing cleanup evidence. */
    public void authorityUnavailable() {
        if (!started || destroyed || terminalNotified || notificationAuthorityLost) return;
        notificationAuthorityLost=true;userPaused=false;
        if(policy.snapshot().lastEnd!=InterruptionRecovery.End.AUTHORITY_LOST)loseAuthority();
    }
    private void cancel(boolean pause) {
        if (!started || terminalNotified) return;
        // Explicit Stop is final for this session, including queued Pause/completion callbacks.
        if(pause && explicitStop)return;
        if(!pause){explicitStop=true;cleanPauseEligible=false;}
        userPaused=pause;if(pause)policy.pause(now());else policy.stop(now());cancelTimer();
        if(active!=null)active.stop(pause?Stop.PAUSE:Stop.STOP);
        status="Stopping microphone and preserving queued PCM";show();pump();
    }
    public void destroy() {
        if (destroyed || terminalNotified) return;
        destroyed=true;userPaused=false;cleanPauseEligible=false;policy.authorityLost(now());cancelTimer();
        if(active!=null)active.stop(Stop.INTERRUPTED);pump();
    }
    private void launch(final InterruptionRecovery.Lease admitted) {
        if(active!=null || blocked || policy.snapshot().lease!=admitted || !authority()) {
            policy.authorityLost(now());policy.released(admitted);pump();return;
        }
        final Attempt created;
        try {
            created=engines.create(captureId,nextEpoch,resumed,new Events() {
                @Override public void firstPcm(final Attempt attempt,final CaptureEvidence.FirstPcm observation) {
                    host.post(new Runnable() { @Override public void run() { first(admitted,attempt,observation); } });
                }
                @Override public void finished(final Attempt attempt,final CaptureEvidence.Result result,final String text,final boolean paused,final long epoch) {
                    host.post(new Runnable() { @Override public void run() { completed(admitted,attempt,result,text,paused,epoch); } });
                }
            });
            if(created==null || !captureId.equals(created.captureId()) || created.runId()==null || created.runId().isEmpty())
                throw new IllegalStateException("Invalid capture attempt identity");
        } catch(RuntimeException failure) {
            blocked=true;policy.stop(now());status="Start failed; session ownership needs inspection: "+failure.getClass().getSimpleName();show();pump();return;
        }
        active=created;
        try { created.start(); }
        catch(RuntimeException failure) {
            CaptureEvidence evidence=new CaptureEvidence(created.captureId(),created.runId());
            evidence.failed(CaptureEvidence.Failure.INITIALIZATION);
            completed(admitted,created,evidence.finish(new PcmReadAccounting().snapshot(),true),"Storage worker could not start",false,nextEpoch);
        }
    }
    private void first(InterruptionRecovery.Lease admitted,Attempt attempt,CaptureEvidence.FirstPcm observation) {
        if(terminalNotified || active!=attempt || lease!=admitted || destroyed || observation==null
            || !attempt.captureId().equals(observation.captureId) || !attempt.runId().equals(observation.runId))return;
        if(!authority()){loseAuthority();return;}
        if(policy.captureConfirmed(admitted,now())) { first=observation;status="Recording visibly; recovery boundaries remain uncertain";show();pump(); }
    }
    private void completed(InterruptionRecovery.Lease admitted,Attempt attempt,CaptureEvidence.Result result,String text,boolean paused,long epoch) {
        if(terminalNotified || active!=attempt || lease!=admitted || result==null
            || !attempt.captureId().equals(result.captureId) || !attempt.runId().equals(result.runId))return;
        cleanPauseEligible=!explicitStop && RecoveryGate.cleanPauseEligible(result,paused);
        lastEvidence=result;pendingOutcome=result;first=null;nextEpoch=epoch;status=result.pcm.warning()+text;
        InterruptionRecovery.Snapshot state=policy.snapshot();
        if(state.state==InterruptionRecovery.State.STARTING || state.state==InterruptionRecovery.State.RUNNING) {
            InterruptionRecovery.Reason reason=RecoveryGate.retryReason(result);
            policy.interrupted(admitted,reason==null?InterruptionRecovery.Reason.UNKNOWN_FAILURE:reason,now());
        }
        if(RecoveryGate.ownershipReleased(result)){policy.released(admitted);active=null;}
        else { blocked=true;status="Physical microphone release is unconfirmed; no replacement recorder allowed. "+status;
            if(result.producerTerminated)active=null;
        }
        if(!RecoveryGate.storageClosed(result)){blocked=true;status="Storage closure is unconfirmed; files retained and restart blocked. "+status;}
        if(blocked || epoch==Long.MAX_VALUE){policy.stop(now());if(epoch==Long.MAX_VALUE){userPaused=false;status="Capture epoch exhausted; explicit Start must create a new capture. "+status;}}
        if(!authority()){userPaused=false;policy.authorityLost(now());}
        userPaused=userPaused && paused;show();pump();
    }
    private void cancelTimer(){if(timer!=null){host.remove(timer);timer=null;}}
    private void loseAuthority(){userPaused=false;policy.authorityLost(now());cancelTimer();if(active!=null)active.stop(Stop.INTERRUPTED);
        status="Recovery stopped: foreground microphone authority or notification permission is unavailable.";show();pump();}
    private void pump() {
        if(terminalNotified || writing!=null)return;
        if(journalFailed){terminal();return;}
        final CaptureEvidence.Result outcome=pendingOutcome;
        final InterruptionRecovery.GapEvent event=outcome==null?policy.peekGap():null;
        if(outcome!=null || event!=null){
            final String record=RecoveryJournal.encode(id,event,outcome==null?lastEvidence:outcome,nextEpoch,first);
            final Write operation=new Write(outcome,event);writing=operation;
            try { journal.persist(id,ordinal,record,new JournalCompletion(){
                @Override public void complete(final boolean historical,final String failure){host.post(new Runnable(){
                    @Override public void run(){written(operation,historical,failure);}
                });}
            }); } catch(RuntimeException failure){if(writing==operation){writing=null;journalFailed("Journal worker could not start");}}
            return;
        }
        if(active!=null)return;
        InterruptionRecovery.Snapshot state=policy.snapshot();
        if(!blocked && state.state==InterruptionRecovery.State.BACKOFF && state.retry!=null){
            if(!authority()){loseAuthority();return;}if(timer!=null)return;
            final InterruptionRecovery.Retry retry=state.retry;
            status="Microphone interrupted. Waiting for bounded retry; prior PCM may have an uncertain gap. Stop/Pause cancels.";show();
            timer=new Runnable(){@Override public void run(){
                if(timer!=this || terminalNotified)return;timer=null;
                if(blocked || writing!=null || pendingOutcome!=null || policy.peekGap()!=null)return;
                InterruptionRecovery.Lease next=policy.takeRetry(retry,authority(),now());
                if(next==null){pump();return;}lease=next;resumed=true;launch(next);
            }};
            host.postDelayed(timer,Math.max(0,retry.dueMillis-now()));return;
        }
        terminal();
    }
    private void written(Write operation,boolean historical,String failure){
        if(terminalNotified || writing!=operation)return; // Exact async operation identity fences duplicate/stale completion.
        writing=null;if(failure!=null){journalFailed(failure);return;}
        historicalUncertainty|=historical;ordinal++;
        if(operation.outcome!=null && pendingOutcome==operation.outcome)pendingOutcome=null;
        if(operation.event!=null && !policy.acknowledgeGap(operation.event)){journalFailed("Gap acknowledgement identity mismatch");return;}
        pump();
    }
    private void journalFailed(String error){
        journalFailed=true;blocked=true;policy.stop(now());cancelTimer();
        status="Gap evidence could not be durably saved; recovery stopped and existing files retained. "+bounded(error);
        if(active!=null)active.stop(Stop.INTERRUPTED);show();terminal();
    }
    private String visibleStatus(){return bounded((historicalUncertainty?"Historical partial/unknown gap records remain; past gap coverage is incomplete. ":"")+status);}
    private void show(){host.status(visibleStatus());}
    private void terminal(){
        if(terminalNotified || active!=null || writing!=null)return;cancelTimer();
        boolean releaseAdmission=!blocked && !journalFailed && pendingOutcome==null && policy.peekGap()==null;
        terminalNotified=true;host.finished(visibleStatus(),!destroyed && !explicitStop && userPaused && cleanPauseEligible,nextEpoch,releaseAdmission);
    }
    private static String bounded(String s){return s==null?"Unknown interruption":s.substring(0,Math.min(s.length(),1200));}
}
