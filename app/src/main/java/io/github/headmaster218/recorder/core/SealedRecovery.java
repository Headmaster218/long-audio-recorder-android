package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Explicit bounded idle-owner recovery pass. Never creates metadata or touches uncertain tails. */
public final class SealedRecovery {
    public static final int MAX_RECOVERIES=32;
    public interface Progress { void recovered(int count); }
    public static final class Result {
        public final int recovered,preserved,remaining;
        Result(int recovered,int preserved,int remaining){this.recovered=recovered;this.preserved=preserved;this.remaining=remaining;}
    }
    private SealedRecovery(){ }
    public static Result run(DirectorySpool store,Progress progress)throws IOException {
        if(store==null||progress==null)throw new IllegalArgumentException();
        final List<String> candidates=new ArrayList<String>();final int[] counts={0,0};
        store.scan(new DirectorySpool.RecoveryListener(){@Override public void found(DirectorySpool.Recovery report){
            if(report.state==DirectorySpool.RecoveryState.PRESERVED_PARTIAL&&report.validatedManifest) {
                if(candidates.size()<MAX_RECOVERIES)candidates.add(report.localObjectId);else counts[1]++;
            } else if(report.state!=DirectorySpool.RecoveryState.FINALIZED_UNCONFIRMED)counts[0]++;
        }});
        int recovered=0;
        for(String id:candidates) {
            // Any recovery error aborts this pass; an uncertain publication is never retried here.
            store.recoverFinalizedPart(id);progress.recovered(++recovered);
        }
        return new Result(recovered,counts[0],counts[1]);
    }
}
