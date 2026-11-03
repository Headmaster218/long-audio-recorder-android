package io.github.headmaster218.recorder.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import io.github.headmaster218.recorder.core.CachePolicy;
import io.github.headmaster218.recorder.core.DirectorySpool;
import io.github.headmaster218.recorder.core.SealedRecovery;

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
    private SealedRecoveryCoordinator(Context context) {
        this.context=context;journal=context.getSharedPreferences("sealed-recovery",Context.MODE_PRIVATE);
        status=journal.getBoolean("in-flight",false)
            ?"Previous recovery was interrupted. Some sealed files may already be ready. Tap Check / recover to inspect again; uncertain tails are retained."
            :"No recovery requested. This does not resume recording or reconstruct unfinished audio.";
    }
    boolean busy(){return busy;}
    String status(){return status;}
    synchronized void start(final long cacheBytes) {
        if(busy)return;busy=true;status="Waiting for idle storage ownership…";
        try{worker.execute(new Runnable(){@Override public void run(){SealedRecoveryCoordinator.this.run(cacheBytes);}});}
        catch(RuntimeException failed){busy=false;status="Recovery worker unavailable. Nothing was recovered.";}
    }
    private void run(long cacheBytes) {
        final int[] recovered={0};boolean marked=false;
        try(SpoolAdmission.Reservation reservation=SpoolAdmission.beginMaintenance()) {
            if(reservation.owner!=null)throw new IOException("Stop recording before recovery");
            if(!journal.edit().putBoolean("in-flight",true).commit())throw new IOException("Could not save recovery interruption marker");
            marked=true;
            Path root=context.getFilesDir().toPath().toRealPath().resolve("audio-spool");
            SealedRecovery.Result result;
            try(DirectorySpool store=new DirectorySpool(root,new CachePolicy(cacheBytes,RecordingSettings.FREE_RESERVE),
                    new AndroidDirectoryProtocol(),new DirectorySpool.Faults(){@Override public void before(DirectorySpool.Operation op,Path path){}},new RetirementStore(context))) {
                status="Checking sealed recordings. Incomplete or invalid objects will remain untouched.";
                result=SealedRecovery.run(store,new SealedRecovery.Progress(){@Override public void recovered(int count){recovered[0]=count;status="Recovered "+count+" sealed recordings; checking the remainder…";}});
            }
            if(!journal.edit().remove("in-flight").commit())throw new IOException("Could not confirm recovery completion marker");
            marked=false;
            status="Recovered "+result.recovered+" sealed recordings. Preserved "+result.preserved+" uncertain objects. "
                +result.remaining+" sealed recordings remain for another explicit pass. Refresh completed files below. Recording has not started.";
        } catch(IOException|RuntimeException failed) {
            status="Recovery stopped after "+recovered[0]+" confirmed recoveries ("+failed.getMessage()+"). "
                +(marked?"Some publication outcomes may be uncertain; inspect again before assuming failure. ":"")
                +"Original audio was not deleted. Stop recording and wait for export/maintenance before retrying.";
        } finally {busy=false;}
    }
}
