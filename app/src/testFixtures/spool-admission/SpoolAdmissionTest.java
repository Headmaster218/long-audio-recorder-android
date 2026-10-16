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
    public static void main(String[] args)throws Exception {
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
