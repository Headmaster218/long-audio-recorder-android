package io.github.headmaster218.recorder.core;

/** Deterministic production helper checks; no Android, filesystem, credentials or network. */
public final class UploadThresholdTest {
    private static int checks;
    private static final TransferPolicy.Network WIFI = new TransferPolicy.Network(true,true,false,false,false,false);
    private interface Action { void run(); }
    private static void yes(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void rejected(Action action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid value accepted at " + checks);
    }
    private static TransferPolicy.Block evaluate(long threshold,long pending,boolean now,boolean charging,TransferPolicy.Network network) {
        return UploadThreshold.policy(threshold).evaluate(pending,0,false,now,charging,network);
    }
    public static void main(String[] args) {
        yes(UploadThreshold.parse("9600000") == UploadThreshold.DEFAULT_BYTES);
        yes(UploadThreshold.parse("1") == 1);
        yes(UploadThreshold.parse("0001") == 1);
        yes(UploadThreshold.parse("9223372036854775807") == Long.MAX_VALUE);
        for (final String invalid : new String[]{null,"","0","-1","+1"," 1","1 ","1.0","1e6","1,000","１","9223372036854775808","10000000000000000000"})
            rejected(() -> UploadThreshold.parse(invalid));
        rejected(() -> UploadThreshold.validate(0));
        rejected(() -> UploadThreshold.policy(-1));
        for (long threshold : new long[]{1,42,UploadThreshold.DEFAULT_BYTES,Long.MAX_VALUE}) {
            yes(evaluate(threshold,0,true,true,WIFI) == TransferPolicy.Block.NO_PENDING_DATA);
            if (threshold > 1) yes(evaluate(threshold,threshold-1,false,true,WIFI) == TransferPolicy.Block.NOT_TRIGGERED);
            yes(evaluate(threshold,threshold,false,true,WIFI) == TransferPolicy.Block.NONE);
            if (threshold < Long.MAX_VALUE) yes(evaluate(threshold,threshold+1,false,true,WIFI) == TransferPolicy.Block.NONE);
            yes(evaluate(threshold,1,true,true,WIFI) == TransferPolicy.Block.NONE);
            yes(UploadThreshold.policy(threshold).evaluate(1,Long.MAX_VALUE,false,false,true,WIFI)
                == (threshold == 1 ? TransferPolicy.Block.NONE : TransferPolicy.Block.NOT_TRIGGERED));
            for (boolean now : new boolean[]{false,true}) {
                yes(evaluate(threshold,threshold,now,false,WIFI) == TransferPolicy.Block.NOT_CHARGING);
                yes(evaluate(threshold,threshold,now,true,new TransferPolicy.Network(false,false,false,false,false,false)) == TransferPolicy.Block.OFFLINE);
                yes(evaluate(threshold,threshold,now,true,new TransferPolicy.Network(true,true,false,false,false,true)) == TransferPolicy.Block.AMBIGUOUS_ROUTE);
                yes(evaluate(threshold,threshold,now,true,new TransferPolicy.Network(true,false,true,false,false,false)) == TransferPolicy.Block.WIFI_REQUIRED);
                yes(evaluate(threshold,threshold,now,true,new TransferPolicy.Network(true,true,false,true,false,false)) == TransferPolicy.Block.METERED_DISABLED);
                yes(evaluate(threshold,threshold,now,true,new TransferPolicy.Network(true,true,false,false,true,false)) == TransferPolicy.Block.ROAMING_DISABLED);
            }
        }
        // State accounting is deliberately global, not current-profile-only; row identity stays elsewhere.
        long pending = UploadThreshold.addPending(0,"QUEUED",41);
        pending = UploadThreshold.addPending(pending,"RECONCILE",1);
        for (String state : new String[]{"RUNNING","BLOCKED","NEEDS_CREDENTIALS","VERIFIED_AT_TIME"})
            pending = UploadThreshold.addPending(pending,state,Long.MAX_VALUE);
        yes(pending == 42);
        yes(evaluate(42,pending,false,true,WIFI) == TransferPolicy.Block.NONE);
        yes(UploadThreshold.addPending(7,"QUEUED",0) == 7);
        yes(UploadThreshold.addPending(Long.MAX_VALUE-1,"QUEUED",1) == Long.MAX_VALUE);
        yes(UploadThreshold.addPending(Long.MAX_VALUE-1,"RECONCILE",2) == Long.MAX_VALUE);
        yes(UploadThreshold.addPending(Long.MAX_VALUE,"QUEUED",Long.MAX_VALUE) == Long.MAX_VALUE);
        rejected(() -> UploadThreshold.addPending(-1,"QUEUED",1));
        rejected(() -> UploadThreshold.addPending(0,"QUEUED",-1));
        rejected(() -> UploadThreshold.addPending(0,null,1));
        // Legacy configuration remains behaviorally equivalent for all route flags and triggers.
        for (int flags=0;flags<64;flags++) {
            TransferPolicy.Network network = new TransferPolicy.Network((flags&1)!=0,(flags&2)!=0,(flags&4)!=0,(flags&8)!=0,(flags&16)!=0,(flags&32)!=0);
            for (boolean now : new boolean[]{false,true}) for (boolean charging : new boolean[]{false,true})
                for (long bytes : new long[]{0,1,9599999,9600000,9600001,Long.MAX_VALUE})
                    yes(evaluate(UploadThreshold.DEFAULT_BYTES,bytes,now,charging,network)
                        == TransferPolicy.DEFAULT.evaluate(bytes,0,false,now,charging,network));
        }
        System.out.println("PASS: " + checks + " upload threshold helper assertions; no Android/runtime or transfer guarantee");
    }
}
