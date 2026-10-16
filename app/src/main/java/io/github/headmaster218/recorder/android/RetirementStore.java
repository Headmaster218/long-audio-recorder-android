package io.github.headmaster218.recorder.android;

import android.content.Context;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import io.github.headmaster218.recorder.core.*;

/** Standard SQLite/atomic-file binding. Called under physical spool ownership, never on PCM append. */
final class RetirementStore implements DirectorySpool.Initializer {
    private final Context context;
    RetirementStore(Context context){this.context=context.getApplicationContext();}
    @Override public Map<String,Long> initialize(Path root,DirectorySpool.DirectoryProtocol protocol)throws IOException {
        try(FtpsQueue queue=new FtpsQueue(context)){synchronize(queue,root,protocol);return queue.floors();}
        catch(RuntimeException e){throw new IOException("retirement database unavailable; preserve spool",e);}
    }
    static Map<String,FtpsQueue.Item> pending(FtpsQueue queue)throws IOException {
        Map<String,FtpsQueue.Item> result=new HashMap<String,FtpsQueue.Item>();
        Map<String,Long> generations=new HashMap<String,Long>();
        for(FtpsQueue.Item row:queue.retiring()) {
            if(row.retirementGeneration<=0)throw new IOException("invalid pending generation");
            Long old=generations.put(row.source,row.retirementGeneration);
            if(old!=null&&old!=row.retirementGeneration)throw new IOException("split retirement intent");
            if(row.cleanupState.equals("REMOVED")&&row.cleanupBinding!=null)result.put(row.source,row);
        }
        if(result.size()!=generations.size()||result.size()>FtpsQueue.MAX_RETIRING)throw new IOException("missing/bounded retirement proof");
        for(FtpsQueue.Item proof:result.values())queue.validateRetirement(proof);
        return result;
    }
    static void synchronize(FtpsQueue queue,Path root,DirectorySpool.DirectoryProtocol protocol)throws IOException {
        RetirementSentinel.State disk=RetirementSentinel.read(root);
        FtpsQueue.HistoryMeta meta=queue.historyMeta(true);
        if(disk!=null&&!disk.storeUuid.equals(meta.store))throw new IOException("retirement store UUID mismatch; no automatic reset");
        queue.validateHistoryBounds();
        if(!meta.ready) {
            if(meta.generation!=0||!queue.emptyRetirement()||(disk!=null&&disk.generation!=0))throw new IOException("ambiguous retirement initialization");
            RetirementSentinel.write(root,new RetirementSentinel.State(meta.store,0),protocol);queue.historyReady();return;
        }
        if(disk==null||disk.generation>meta.generation)throw new IOException("missing sentinel or database older than spool");
        Map<String,FtpsQueue.Item> pending=pending(queue);
        if(meta.generation>disk.generation) {
            if(meta.generation-disk.generation>FtpsQueue.MAX_RETIRING)throw new IOException("unbounded generation recovery");
            for(long generation=disk.generation+1;;generation++) {
                boolean found=false;
                for(FtpsQueue.Item row:pending.values())if(row.retirementGeneration==generation) {
                    VerifiedLocalCleanup.Prepared proof=VerifiedLocalCleanup.decode(row.cleanupBinding,new AndroidCleanupIdentity());
                    VerifiedLocalCleanup.verifyRetired(root,proof,false);found=true;
                }
                if(!found)throw new IOException("missing committed generation evidence");
                if(generation==meta.generation)break;
            }
        }
        // Equal-state replay also completes an interrupted rename/root-fsync before pruning.
        RetirementSentinel.write(root,new RetirementSentinel.State(meta.store,meta.generation),protocol);
    }
}
