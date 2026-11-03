package io.github.headmaster218.recorder.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.headmaster218.recorder.core.CachePolicy;
import io.github.headmaster218.recorder.core.DirectorySpool;
import io.github.headmaster218.recorder.core.SealedRecovery;
import io.github.headmaster218.recorder.core.VerifiedExport;

/** Explicit single-flight worker. No Activity, microphone start, network or automatic retry. */
final class SealedRecoveryCoordinator {
    private static SealedRecoveryCoordinator instance;
    static synchronized SealedRecoveryCoordinator get(Context context) {
        if(instance==null)instance=new SealedRecoveryCoordinator(context.getApplicationContext());return instance;
    }
    private final Context context;
    private final SharedPreferences journal;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(new ThreadFactory(){
        @Override public Thread newThread(final Runnable work){return new Thread(new Runnable(){@Override public void run(){
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);work.run();
        }},"sealed-recording-recovery");}
    });
    private volatile boolean busy;
    private volatile String status;
    private String cursor;
    private final AtomicBoolean cancellation=new AtomicBoolean();
    private SealedRecoveryCoordinator(Context context) {
        this.context=context;journal=context.getSharedPreferences("sealed-recovery",Context.MODE_PRIVATE);
        try {
            String saved=journal.getString("cursor","");
            cursor=saved!=null&&saved.matches("segment-[0-9a-f]{64}-[0-9]+\\.part")?saved:null;
            status=journal.getBoolean("in-flight",false)
                ?"Previous recovery was interrupted. Some sealed files may already be ready. Tap Check / recover to inspect again; uncertain tails are retained."
                :"No recovery requested. This does not resume recording or reconstruct unfinished audio.";
        } catch(RuntimeException unreadable) {
            cursor=null;status="Saved recovery progress is unreadable. No recovery started; an explicit tap will inspect from the beginning. Audio is unchanged.";
        }
    }
    boolean busy(){return busy;}
    String status(){return status;}
    void cancel(){if(busy){cancellation.set(true);status="Cancellation requested. Pre-publication checks will stop; an in-progress publication must finish safely before storage is released.";}}
    synchronized void start(final long cacheBytes) {
        if(busy)return;cancellation.set(false);busy=true;status="Waiting for idle storage ownership…";
        try{worker.execute(new Runnable(){@Override public void run(){SealedRecoveryCoordinator.this.run(cacheBytes);}});}
        catch(RuntimeException failed){busy=false;status="Recovery worker unavailable. Nothing was recovered.";}
    }
    private void run(long cacheBytes) {
        final int[] recovered={0};boolean marked=false;
        try(SpoolAdmission.Reservation reservation=SpoolAdmission.beginMaintenance()) {
            if(reservation.owner!=null)throw new IOException("Stop recording before recovery");
            if(cancellation.get())throw new IOException("Cancellation requested before storage access");
            if(!journal.edit().putBoolean("in-flight",true).putString("cursor",cursor==null?"":cursor).commit())throw new IOException("Could not save recovery interruption marker");
            marked=true;
            if(cancellation.get())throw new IOException("Cancellation requested before storage access");
            Path root=context.getFilesDir().toPath().toRealPath().resolve("audio-spool");
            SealedRecovery.Result result;
            try(DirectorySpool store=new DirectorySpool(root,new CachePolicy(cacheBytes,RecordingSettings.FREE_RESERVE),
                    new AndroidDirectoryProtocol(),new DirectorySpool.Faults(){@Override public void before(DirectorySpool.Operation op,Path path){}},new RetirementStore(context))) {
                status="Checking up to 32 staged objects. Completed audio payloads are not read; uncertain tails remain untouched.";
                result=SealedRecovery.runPage(store,cursor,new VerifiedExport.Cancellation(){@Override public boolean cancelled(){return cancellation.get();}},
                    new SealedRecovery.Progress(){@Override public void recovered(int count){recovered[0]=count;status="Recovered "+count+" sealed recordings in this page…";}});
            }
            if(!journal.edit().remove("in-flight").putString("cursor",result.nextCursor==null?"":result.nextCursor).commit())throw new IOException("Could not confirm recovery completion marker");
            cursor=result.nextCursor;
            marked=false;
            status="Recovered "+result.recovered+" sealed recordings. Preserved "+result.preserved+" uncertain objects. "
                +(result.remaining>0?result.remaining+" staged objects remain in this sweep; tap again to continue. ":"Sweep finished; the next tap starts a new sweep for earlier or new objects. ")
                +"Refresh completed files below. Recording has not started.";
        } catch(IOException|RuntimeException failed) {
            status="Recovery stopped after "+recovered[0]+" confirmed recoveries ("+(cancellation.get()?"cancellation requested":failed.getMessage())+"). "
                +(marked?"Some publication outcomes may be uncertain; inspect again before assuming failure. ":"")
                +"Original audio was not deleted. Stop recording and wait for export/maintenance before retrying.";
        } finally {busy=false;}
    }
}
