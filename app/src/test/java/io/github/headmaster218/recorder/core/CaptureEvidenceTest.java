package io.github.headmaster218.recorder.core;

/** Finite source fixtures. UNEXECUTED at this checkpoint; no Android/hardware simulation claim. */
public final class CaptureEvidenceTest {
    private static int assertions;
    private CaptureEvidenceTest() { }
    private interface Action { void run(); }
    private static void check(boolean value,String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }
    private static void rejects(Action action,String name) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        check(rejected,name);
    }
    private static CaptureEvidence evidence() { return new CaptureEvidence("capture-fixture","run-fixture"); }
    private static PcmReadAccounting pcm(long read,long confirmed) {
        PcmReadAccounting result = new PcmReadAccounting();
        if (read > 0) check(result.readAccepted(read),"read accepted");
        if (confirmed > 0) check(result.appendConfirmed(confirmed),"append accepted");
        return result;
    }
    public static void main(String[] arguments) {
        noHandleAndPendingRelease(); releaseFailureAfterEveryCause(); returnedReleaseDoesNotEraseFailure();
        storageAndAccounting(); firstAppendedObservation(); terminalAndValidation();
        System.out.println("CaptureEvidence finite host assertions: " + assertions);
    }
    private static void noHandleAndPendingRelease() {
        CaptureEvidence unused = evidence();
        CaptureEvidence.Result empty = unused.finish(pcm(0,0).snapshot(),true);
        check(empty.release == CaptureEvidence.Release.NO_HANDLE,"no returned handle");
        check(empty.firstPcm == null && empty.firstFailure == null && empty.failureMask == 0,"no fabricated event");
        check(empty.writer == CaptureEvidence.Closure.NOT_OPENED && empty.spool == CaptureEvidence.Closure.NOT_OPENED,"no fabricated closure");
        check(empty.allReadSamplesConfirmed(),"zero read accounting, not recording proof");

        CaptureEvidence pending = evidence(); pending.handleObtained();
        CaptureEvidence.Result result = pending.finish(pcm(0,0).snapshot(),true);
        check(result.release == CaptureEvidence.Release.UNCONFIRMED,"thread termination is not release");
        CaptureEvidence busy = evidence(); busy.handleObtained(); busy.releaseReturned();
        CaptureEvidence.Result notJoined = busy.finish(pcm(2,2).snapshot(),false);
        check(!notJoined.producerTerminated && !notJoined.allReadSamplesConfirmed(),"no final accounting before join");
    }
    private static void releaseFailureAfterEveryCause() {
        // In particular: DEAD_RECORDER, policy silence or route loss must not mask failed cleanup.
        for (CaptureEvidence.Failure primary : CaptureEvidence.Failure.values()) {
            CaptureEvidence run = evidence(); run.handleObtained(); run.failed(primary); run.releaseThrew();
            CaptureEvidence.Result result = run.finish(pcm(0,0).snapshot(),true);
            check(result.firstFailure == primary,"first cause preserved " + primary);
            check(result.has(primary),"primary flag " + primary);
            check(result.has(CaptureEvidence.Failure.RELEASE_FAILED),"late release flag " + primary);
            check(result.release == CaptureEvidence.Release.THREW,"throw does not become successful release");
        }
        // Initialization validation can fail before createRecord returns, but the handle still existed.
        CaptureEvidence initialization = evidence(); initialization.handleObtained();
        initialization.failed(CaptureEvidence.Failure.CLIENT_FORMAT); initialization.releaseThrew();
        CaptureEvidence.Result failed = initialization.finish(pcm(0,0).snapshot(),true);
        check(failed.release == CaptureEvidence.Release.THREW && failed.firstFailure == CaptureEvidence.Failure.CLIENT_FORMAT,"unreturned init handle retained");
    }
    private static void returnedReleaseDoesNotEraseFailure() {
        CaptureEvidence run = evidence(); run.handleObtained();
        run.failed(CaptureEvidence.Failure.DEAD_RECORDER);
        run.failed(CaptureEvidence.Failure.STOP_FAILED);
        run.failed(CaptureEvidence.Failure.CALLBACK_CLEANUP);
        run.releaseReturned();
        CaptureEvidence.Result result = run.finish(pcm(8,8).snapshot(),true);
        check(result.release == CaptureEvidence.Release.RETURNED,"only actual normal return acknowledged");
        check(result.has(CaptureEvidence.Failure.STOP_FAILED) && result.has(CaptureEvidence.Failure.CALLBACK_CLEANUP),"other cleanup failures survive");
        check(result.firstFailure == CaptureEvidence.Failure.DEAD_RECORDER,"primary not replaced by later failure");
        // Thread.start failure: the storage worker releases the handle; no producer completion implied.
        CaptureEvidence notStarted = evidence(); notStarted.handleObtained();
        notStarted.failed(CaptureEvidence.Failure.INITIALIZATION); notStarted.releaseReturned();
        check(notStarted.finish(pcm(0,0).snapshot(),true).release == CaptureEvidence.Release.RETURNED,"not-started producer release");
    }
    private static void storageAndAccounting() {
        CaptureEvidence run = evidence(); run.handleObtained(); run.openingSpool(); run.openingWriter();
        run.failed(CaptureEvidence.Failure.POLICY_SILENCED); run.failed(CaptureEvidence.Failure.STORAGE);
        run.releaseReturned(); run.spoolClosed();
        CaptureEvidence.Result uncertain = run.finish(pcm(10,4).snapshot(),true);
        check(uncertain.firstFailure == CaptureEvidence.Failure.POLICY_SILENCED,"earliest cause separate from gating facts");
        check(uncertain.has(CaptureEvidence.Failure.STORAGE) && uncertain.has(CaptureEvidence.Failure.PCM_ACCOUNTING),"late storage/accounting not lost");
        check(uncertain.writer == CaptureEvidence.Closure.UNCONFIRMED && uncertain.spool == CaptureEvidence.Closure.CLOSED,"spool closure is not writer publication");
        check(uncertain.pcm.readSamples == 10 && uncertain.pcm.confirmedSamples == 4 && uncertain.pcm.unconfirmedSamples == 6,"exact conservative sample counters");
        check(!uncertain.allReadSamplesConfirmed(),"unconfirmed prefix not silently replayed");

        CaptureEvidence closeFailed = evidence(); closeFailed.openingSpool(); closeFailed.openingWriter();
        closeFailed.writerClosed(); closeFailed.failed(CaptureEvidence.Failure.STORAGE);
        CaptureEvidence.Result failed = closeFailed.finish(pcm(4,4).snapshot(),true);
        check(failed.writer == CaptureEvidence.Closure.CLOSED && failed.spool == CaptureEvidence.Closure.UNCONFIRMED,"spool close failure visible separately");
        check(failed.allReadSamplesConfirmed(),"read accounting does not claim durable storage");

        CaptureEvidence clean = evidence(); clean.openingSpool(); clean.openingWriter(); clean.handleObtained();
        clean.writerClosed(); clean.releaseReturned(); clean.spoolClosed();
        CaptureEvidence.Result result = clean.finish(pcm(4,4).snapshot(),true);
        check(result.writer == CaptureEvidence.Closure.CLOSED && result.spool == CaptureEvidence.Closure.CLOSED,"both closes returned");
        check(result.allReadSamplesConfirmed() && result.failureMask == 0,"clean explicit stop/pause evidence");
        PcmReadAccounting overflowing = new PcmReadAccounting(); overflowing.readAccepted(Long.MAX_VALUE);
        overflowing.readAccepted(1);
        CaptureEvidence.Result overflow = evidence().finish(overflowing.snapshot(),true);
        check(overflow.pcm.counterOverflow && overflow.has(CaptureEvidence.Failure.PCM_ACCOUNTING),"overflow not exact loss");
        check(!overflow.allReadSamplesConfirmed(),"overflow never clear");
    }
    private static void firstAppendedObservation() {
        CaptureEvidence run = evidence();
        check(run.appended(4,2,false,0,10,20,30) == null,"silenced/route-uncertain block has no eligible event");
        check(run.appended(3,2,true,1,30,40,50) == null,"incomplete interleaved frame excluded");
        CaptureEvidence.FirstPcm first = run.appended(4,2,true,2,50,60,70);
        check(first != null && first.samples == 4 && first.channels == 2,"first eligible append");
        check(first.captureId.equals("capture-fixture") && first.runId.equals("run-fixture"),"exact run identity");
        check(first.readOrdinal == 2 && first.readBeforeNanos == 50 && first.readAfterNanos == 60 && first.appendedAtNanos == 70,"read interval versus append observation");
        check(run.appended(4,2,true,3,70,80,90) == null,"at most one event");
        run.failed(CaptureEvidence.Failure.DEAD_RECORDER);
        CaptureEvidence.Result result = run.finish(pcm(11,11).snapshot(),true);
        check(result.firstPcm == first && result.has(CaptureEvidence.Failure.DEAD_RECORDER),"earlier valid observation is not recovery success");
        CaptureEvidence mono = evidence();
        check(mono.appended(1,1,true,0,0,0,0) != null,"one full mono frame eligible");
    }
    private static void terminalAndValidation() {
        final CaptureEvidence run = evidence();
        rejects(new Action() { public void run() { run.releaseReturned(); } },"no phantom release");
        rejects(new Action() { public void run() { run.writerClosed(); } },"no phantom writer close");
        rejects(new Action() { public void run() { run.spoolClosed(); } },"no phantom spool close");
        run.handleObtained();
        rejects(new Action() { public void run() { run.handleObtained(); } },"one recorder handle");
        run.releaseThrew();
        rejects(new Action() { public void run() { run.releaseReturned(); } },"no upgrade of uncertain failed release");
        rejects(new Action() { public void run() { run.appended(0,1,true,0,0,1,2); } },"zero samples invalid");
        rejects(new Action() { public void run() { run.appended(1,3,true,0,0,1,2); } },"unsupported channels");
        rejects(new Action() { public void run() { run.appended(1,1,true,-1,0,1,2); } },"negative ordinal");
        rejects(new Action() { public void run() { run.appended(1,1,true,0,2,1,3); } },"regressed read clock");
        rejects(new Action() { public void run() { run.appended(1,1,true,0,0,2,1); } },"append before read end");
        rejects(new Action() { public void run() { run.failed(null); } },"null cause");
        rejects(new Action() { public void run() { run.finish(null,true); } },"null accounting");
        run.finish(pcm(0,0).snapshot(),true);
        rejects(new Action() { public void run() { run.failed(CaptureEvidence.Failure.UNKNOWN); } },"no post-completion mutation");
        rejects(new Action() { public void run() { run.appended(1,1,true,0,0,1,2); } },"no post-completion event");
        rejects(new Action() { public void run() { run.finish(pcm(0,0).snapshot(),true); } },"one completion freeze");
    }
}
