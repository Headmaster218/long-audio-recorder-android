package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Explicit idle-owner recovery helpers. The app uses staged-only pages, never metadata reconstruction. */
public final class SealedRecovery {
    public static final int MAX_RECOVERIES=32;
    public interface Progress { void recovered(int count); }
    public static final class Result {
        public final int recovered,preserved,remaining;
        public final String nextCursor;
        Result(int recovered,int preserved,int remaining){this(recovered,preserved,remaining,null);}
        Result(int recovered,int preserved,int remaining,String cursor){this.recovered=recovered;this.preserved=preserved;this.remaining=remaining;nextCursor=cursor;}
    }
    private SealedRecovery(){ }
    /** At most 32 staged objects, no ready-file hashing. Cursor advances over preserved raw tails too. */
    public static Result runPage(DirectorySpool store,String after,VerifiedExport.Cancellation cancellation,Progress progress)throws IOException {
        if(store==null||cancellation==null||progress==null)throw new IllegalArgumentException();
        DirectorySpool.StagedPage page=store.stagedPage(after,MAX_RECOVERIES,cancellation);
        int recovered=0,preserved=0;
        for(String id:page.objectIds) {
            VerifiedExport.checkCancelled(cancellation);
            DirectorySpool.Recovery report;
            try{report=store.inspectStaged(id,cancellation);}
            catch(IOException|IllegalArgumentException invalid){VerifiedExport.checkCancelled(cancellation);preserved++;continue;}
            if(!report.validatedManifest){preserved++;continue;}
            store.recoverFinalizedPart(id,cancellation);progress.recovered(++recovered);
        }
        VerifiedExport.checkCancelled(cancellation);
        return new Result(recovered,preserved,page.remaining,page.nextCursor);
    }
    /** Legacy full-inventory helper; interactive app callers should use runPage. */
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
