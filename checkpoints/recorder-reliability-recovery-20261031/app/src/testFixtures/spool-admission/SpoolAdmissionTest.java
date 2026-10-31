package io.github.headmaster218.recorder.android;
import io.github.headmaster218.recorder.core.*;
import java.io.IOException;
import java.nio.file.*;
/** Production admission state with an export-busy model, not Android lifecycle. */
public final class SpoolAdmissionTest {
    private static int checks;
    interface Action { void run() throws Exception; }
    private static void yes(boolean value) { checks++;if(!value)throw new AssertionError("check "+checks); }
    private static void fails(Action action)throws Exception { checks++;try{action.run();}catch(IOException|IllegalStateException e){return;}throw new AssertionError("expected denial"); }
    private static CaptureEvidence.Result busyOutcome()throws Exception {
        CaptureEvidence e=new CaptureEvidence("capture","run");
        try {SpoolAdmission.beginCapture();throw new AssertionError("busy expected");}
        catch(SpoolAdmission.Busy busy){e.failed(CaptureEvidence.Failure.INITIALIZATION);}
        CaptureEvidence.Result result=e.finish(new PcmReadAccounting().snapshot(),true);
        yes(result.spool==CaptureEvidence.Closure.NOT_OPENED && result.writer==CaptureEvidence.Closure.NOT_OPENED);
        yes(RecoveryGate.ownershipReleased(result) && RecoveryGate.storageClosed(result));
        yes(RecoveryGate.retryReason(result)==null);return result;
    }
    private static void busyRecovery()throws Exception {
        SpoolAdmission.Reservation maintenance=SpoolAdmission.beginMaintenance();busyOutcome();maintenance.close();
        Object token=SpoolAdmission.beginCapture();busyOutcome();SpoolAdmission.abandonOpening(token);
        Path path=Files.createTempDirectory(Paths.get("app/build"),"busy-retry-");
        token=SpoolAdmission.beginCapture();
        DirectorySpool spool=new DirectorySpool(path,new CachePolicy(1024*1024,0),new DirectorySpool.NioProtocol(),(op,p)->{});
        SpoolAdmission.opened(token,spool);busyOutcome();spool.close();SpoolAdmission.captureClosed(spool);
        // Real constructor failure: cleanup must release its lock, but evidence remains fail-closed.
        CaptureEvidence partial=new CaptureEvidence("capture","run");token=SpoolAdmission.beginCapture();partial.openingSpool();
        try {
            new DirectorySpool(path,new CachePolicy(1024*1024,0),new DirectorySpool.NioProtocol(),(op,p)->{},(p,protocol)->{throw new IOException("injected initializer failure");});
            throw new AssertionError("constructor failure expected");
        }catch(IOException expected){SpoolAdmission.abandonOpening(token);partial.failed(CaptureEvidence.Failure.STORAGE);}
        yes(!RecoveryGate.storageClosed(partial.finish(new PcmReadAccounting().snapshot(),true)));
        token=SpoolAdmission.beginCapture();spool=new DirectorySpool(path,new CachePolicy(1024*1024,0),new DirectorySpool.NioProtocol(),(op,p)->{});
        SpoolAdmission.opened(token,spool);spool.close();SpoolAdmission.captureClosed(spool);
        yes(!SpoolAdmission.maintenanceActive());
    }
    public static void main(String[] args)throws Exception {
        busyRecovery();
        Object first=SpoolAdmission.beginCapture();fails(SpoolAdmission::beginCapture);fails(SpoolAdmission::beginMaintenance);
        fails(()->SpoolAdmission.abandonOpening(new Object()));SpoolAdmission.abandonOpening(first);
        SpoolAdmission.Reservation idle=SpoolAdmission.beginMaintenance();yes(idle.owner==null);yes(SpoolAdmission.maintenanceActive());
        fails(SpoolAdmission::beginCapture);fails(SpoolAdmission::beginMaintenance);idle.close();fails(idle::close);
        SpoolAdmission.Reservation second=SpoolAdmission.beginMaintenance();fails(idle::close);yes(SpoolAdmission.maintenanceActive());second.close();
        ExportCoordinator.busy=true;fails(SpoolAdmission::beginMaintenance);ExportCoordinator.busy=false;
        Object token=SpoolAdmission.beginCapture();Path root=Files.createTempDirectory(Paths.get("app/build"),"admission-synthetic-");
        DirectorySpool spool=new DirectorySpool(root,new CachePolicy(1024*1024,0),new DirectorySpool.NioProtocol(),(op,path)->{});
        fails(()->SpoolAdmission.opened(first,spool));SpoolAdmission.opened(token,spool);fails(()->SpoolAdmission.abandonOpening(token));
        SpoolAdmission.Reservation shared=SpoolAdmission.beginMaintenance();yes(shared.owner==spool);fails(SpoolAdmission::beginCapture);
        synchronized(SpoolAdmission.LOCK){yes(SpoolAdmission.maintenanceActive());}
        shared.close();spool.close();SpoolAdmission.captureClosed(spool);fails(()->SpoolAdmission.captureClosed(spool));
        Object next=SpoolAdmission.beginCapture();fails(()->SpoolAdmission.abandonOpening(token));SpoolAdmission.abandonOpening(next);yes(!SpoolAdmission.maintenanceActive());
        System.out.println("PASS: "+checks+" production admission assertions with export-busy model; no Android runtime");
    }
}
final class ExportCoordinator { static boolean busy; static boolean cleanupBlocked(){return busy;} }
