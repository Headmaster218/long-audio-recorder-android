package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Finite host safety/journal fixtures; does not execute Android service, callbacks or microphone. */
public final class RecoveryIntegrationTest {
    private static int assertions;
    private static void check(boolean ok) { assertions++; if (!ok) throw new AssertionError("assertion " + assertions); }
    private static CaptureEvidence.Result result(CaptureEvidence.Failure reason,CaptureEvidence.Failure other,
            CaptureEvidence.Release release,boolean producer,boolean writerClosed,boolean spoolClosed,boolean uncertain) {
        CaptureEvidence e = new CaptureEvidence("capture-id","run-id");
        if (release != CaptureEvidence.Release.NO_HANDLE) {
            e.handleObtained();
            if (release == CaptureEvidence.Release.RETURNED) e.releaseReturned();
            if (release == CaptureEvidence.Release.THREW) e.releaseThrew();
        }
        e.openingWriter(); if (writerClosed) e.writerClosed();
        e.openingSpool(); if (spoolClosed) e.spoolClosed();
        if (reason != null) e.failed(reason); if (other != null) e.failed(other);
        PcmReadAccounting a = new PcmReadAccounting();a.readAccepted(10);a.appendConfirmed(uncertain ? 6 : 10);
        return e.finish(a.snapshot(),producer);
    }
    private static void gates() {
        for (CaptureEvidence.Failure f : CaptureEvidence.Failure.values()) {
            CaptureEvidence.Result r = result(f,null,CaptureEvidence.Release.RETURNED,true,true,true,false);
            boolean allowed = f == CaptureEvidence.Failure.POLICY_SILENCED || f == CaptureEvidence.Failure.ROUTE_UNAVAILABLE || f == CaptureEvidence.Failure.DEAD_RECORDER;
            check((RecoveryGate.retryReason(r) != null) == allowed);
            for (CaptureEvidence.Release release : CaptureEvidence.Release.values()) {
                for (boolean producer : new boolean[] {false,true}) {
                    r = result(f,null,release,producer,true,true,true);
                    check(RecoveryGate.ownershipReleased(r) == (producer && (release == CaptureEvidence.Release.RETURNED || release == CaptureEvidence.Release.NO_HANDLE)));
                    if (!RecoveryGate.ownershipReleased(r)) check(RecoveryGate.retryReason(r) == null);
                    check(!RecoveryGate.cleanPauseEligible(r,true));
                    check(r.pcm.unconfirmedSamples == 4 && r.pcm.confirmedSamples == 6 && r.pcm.readSamples == 10);
                }
            }
        }
        for (CaptureEvidence.Failure f : CaptureEvidence.Failure.values()) {
            CaptureEvidence.Result r = result(CaptureEvidence.Failure.ROUTE_UNAVAILABLE,f,CaptureEvidence.Release.RETURNED,true,true,true,true);
            boolean allowed = f == CaptureEvidence.Failure.POLICY_SILENCED || f == CaptureEvidence.Failure.ROUTE_UNAVAILABLE
                || f == CaptureEvidence.Failure.DEAD_RECORDER || f == CaptureEvidence.Failure.PCM_ACCOUNTING;
            check((RecoveryGate.retryReason(r) != null) == allowed);
            check(r.has(f));
        }
        for (boolean writer : new boolean[] {false,true}) for (boolean spool : new boolean[] {false,true}) {
            CaptureEvidence.Result r = result(CaptureEvidence.Failure.DEAD_RECORDER,null,CaptureEvidence.Release.RETURNED,true,writer,spool,true);
            check(RecoveryGate.storageClosed(r) == (writer && spool));
            check((RecoveryGate.retryReason(r) != null) == (writer && spool));
        }
        CaptureEvidence.Result clean = result(null,null,CaptureEvidence.Release.RETURNED,true,true,true,false);
        check(RecoveryGate.cleanPauseEligible(clean,true));check(!RecoveryGate.cleanPauseEligible(clean,false));
        check(!RecoveryGate.cleanPauseEligible(result(null,null,CaptureEvidence.Release.RETURNED,true,true,true,true),true));
        CaptureEvidence e = new CaptureEvidence("c","r");e.failed(CaptureEvidence.Failure.DEAD_RECORDER);
        PcmReadAccounting a = new PcmReadAccounting();a.readAccepted(Long.MAX_VALUE);a.readAccepted(1);
        check(RecoveryGate.retryReason(e.finish(a.snapshot(),true)) == null);
        check(!RecoveryGate.ownershipReleased(null) && !RecoveryGate.storageClosed(null) && RecoveryGate.retryReason(null) == null);
    }
    private static RecoveryJournal journal(Path p,final RecoveryJournal.Step fail) {
        return new RecoveryJournal(p,new DirectorySpool.NioProtocol(),new RecoveryJournal.Faults() {
            @Override public void before(RecoveryJournal.Step step) throws IOException { if (step == fail) throw new IOException("injected " + step); }
        });
    }
    private static void persistence(Path root) throws Exception {
        Path ok = root.resolve("ok");RecoveryJournal j = journal(ok,null);
        j.persist("session",0,"immutable\n");j.persist("session",0,"immutable\n");
        check(new String(Files.readAllBytes(ok.resolve("session-0.ready")),StandardCharsets.UTF_8).equals("immutable\n"));
        try { j.persist("session",0,"different\n");throw new AssertionError(); } catch (IOException expected) { check(true); }
        try { j.persist("../escape",0,"bad");throw new AssertionError(); } catch (IllegalArgumentException expected) { check(true); }
        try { j.persist("session",128,"bad");throw new AssertionError(); } catch (IllegalArgumentException expected) { check(true); }
        char[] large = new char[RecoveryJournal.MAX_RECORD_BYTES+1];java.util.Arrays.fill(large,'x');
        try { j.persist("session",1,new String(large));throw new AssertionError(); } catch (IOException expected) { check(true); }
        for (RecoveryJournal.Step step : RecoveryJournal.Step.values()) {
            Path path = root.resolve(step.name());
            InterruptionRecovery c = new InterruptionRecovery(1,100,100);
            InterruptionRecovery.Lease lease = c.explicitStart(true,true,0);
            c.interrupted(lease,InterruptionRecovery.Reason.ROUTE_UNAVAILABLE,1);
            InterruptionRecovery.GapEvent event = c.peekGap();
            try { journal(path,step).persist("session",0,"evidence\n");throw new AssertionError(); } catch (IOException expected) { check(true); }
            check(c.peekGap() == event); // No acknowledgement on any persistence failure.
            if (step == RecoveryJournal.Step.DIRECTORY_SYNC) {
                journal(path,null).persist("session",0,"evidence\n");check(c.acknowledgeGap(event));
            } else {
                check(Files.exists(path.resolve("session-0.part")));
                try { journal(path,null).persist("session",0,"evidence\n");throw new AssertionError(); } catch (IOException expected) { check(true); }
                check(c.peekGap() == event);
                check(journal(path,null).persist("other-session",0,"new evidence\n"));
                check(Files.exists(path.resolve("session-0.part")) && Files.exists(path.resolve("other-session-0.ready")));
            }
        }
        Path historical = root.resolve("historical");Files.createDirectory(historical);
        Files.write(historical.resolve("old.ready"),new byte[RecoveryJournal.MAX_RECORD_BYTES+1]);
        check(journal(historical,null).persist("session",0,"new\n"));
        check(Files.size(historical.resolve("old.ready")) == RecoveryJournal.MAX_RECORD_BYTES+1);
        Path malformed = root.resolve("malformed");Files.createDirectory(malformed);
        Files.write(malformed.resolve("garbage.ready"),new byte[] {1});
        check(journal(malformed,null).persist("session",0,"new\n"));
        check(Files.exists(malformed.resolve("garbage.ready")));
        Path full = root.resolve("full");Files.createDirectory(full);
        for (int n=0;n<RecoveryJournal.MAX_FILES;n++) Files.write(full.resolve("retained-"+n),new byte[0]);
        try { journal(full,null).persist("session",0,"new\n");throw new AssertionError(); } catch (IOException expected) { check(true); }
        check(Files.exists(full.resolve("retained-0")) && Files.exists(full.resolve("retained-511")));
        check(!Files.exists(full.resolve("session-0.ready")));
        Path link = root.resolve("symlink");Files.createSymbolicLink(link,ok);
        try { journal(link,null).persist("session",1,"new\n");throw new AssertionError(); } catch (IOException expected) { check(true); }
    }
    private static void policyBoundary(Path root) throws Exception {
        InterruptionRecovery c = new InterruptionRecovery(2,100,200);
        InterruptionRecovery.Lease old = c.explicitStart(true,true,0);
        CaptureEvidence.Result r = result(CaptureEvidence.Failure.ROUTE_UNAVAILABLE,null,CaptureEvidence.Release.RETURNED,true,true,true,true);
        check(c.interrupted(old,RecoveryGate.retryReason(r),1));
        InterruptionRecovery.Retry retry = c.snapshot().retry;
        check(c.takeRetry(retry,true,101) == null);
        check(RecoveryGate.ownershipReleased(r) && c.released(old));
        InterruptionRecovery.GapEvent opened = c.peekGap();
        String record = RecoveryJournal.encode("session",opened,r,1,null);
        check(record.contains("unconfirmedUpperBoundSamples=4") && record.contains("physicalGapEnd=unknown") && record.contains("replay=never"));
        journal(root.resolve("policy"),null).persist("session",0,record);
        check(c.acknowledgeGap(opened));
        InterruptionRecovery.Lease next = c.takeRetry(retry,true,101);check(next != null);
        c.stop(102);check(!c.captureConfirmed(next,103));check(c.takeRetry(retry,true,104) == null);
        check(c.released(next));check(!c.released(old));
        // Pause during BACKOFF or pending outcome persistence cannot mint a clean-pause token.
        check(!RecoveryGate.cleanPauseEligible(r,true));
        check(c.peekGap().endMillis == -1 && c.peekGap().end == InterruptionRecovery.End.USER_STOPPED);
    }
    public static void main(String[] args) throws Exception {
        Path parent = java.nio.file.Paths.get("app/build");Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent,"recovery-integration-fixture-").toAbsolutePath();
        gates();persistence(root);policyBoundary(root);
        System.out.println("PASS " + assertions + " finite evidence-gate/journal/policy assertions; no Android runtime qualification");
    }
}
