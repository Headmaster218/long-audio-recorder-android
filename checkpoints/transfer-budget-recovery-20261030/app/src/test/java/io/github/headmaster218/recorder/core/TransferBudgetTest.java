package io.github.headmaster218.recorder.core;

/** Pure host behavior only. Persistence, Android scheduling and network wiring need separate tests. */
public final class TransferBudgetTest {
    private static int checks;
    private interface Action { void run(); }
    private static void check(boolean condition) {
        checks++; if (!condition) throw new AssertionError("check " + checks);
    }
    private static void rejected(Class<? extends RuntimeException> type, Action action) {
        try { action.run(); throw new AssertionError("Expected " + type.getSimpleName()); }
        catch (RuntimeException failure) { check(type.isInstance(failure)); }
    }
    private static TransferBudget.State recovered(TransferBudget.State state) {
        return new TransferBudget.State(state.enabled,state.limitBytes,state.chargedBytes,state.revision);
    }
    private static void same(TransferBudget.State a, TransferBudget.State b) {
        check(a.enabled==b.enabled && a.limitBytes==b.limitBytes
            && a.chargedBytes==b.chargedBytes && a.revision==b.revision);
    }
    private static void estimateBounds() {
        check(TransferBudget.estimate(44,32)==3L*(44+32+512+1024)+6L*32768);
        check(TransferBudget.estimate(9600000,8192)==3L*(9600000+8192+512+1024)+6L*32768);
        long maxFiles=(Long.MAX_VALUE-TransferBudget.READ_OVERRUN_ALLOWANCE_BYTES)/3;
        long maxWav=maxFiles-1-TransferBudget.TRANSPORT_ENVELOPE_BYTES-TransferBudget.MARKER_ALLOWANCE_BYTES;
        long estimate=TransferBudget.estimate(maxWav,1);
        check(estimate>0 && estimate<=Long.MAX_VALUE);
        rejected(IllegalArgumentException.class,()->TransferBudget.estimate(maxWav+1,1));
        rejected(IllegalArgumentException.class,()->TransferBudget.estimate(Long.MAX_VALUE,8192));
        rejected(IllegalArgumentException.class,()->TransferBudget.estimate(0,32));
        rejected(IllegalArgumentException.class,()->TransferBudget.estimate(-1,32));
        rejected(IllegalArgumentException.class,()->TransferBudget.estimate(1,0));
        rejected(IllegalArgumentException.class,()->TransferBudget.estimate(1,8193));
        rejected(IllegalArgumentException.class,()->TransferBudget.estimate(1,Long.MAX_VALUE));
    }
    private static void stateValidation() {
        rejected(IllegalArgumentException.class,()->new TransferBudget.State(true,0,0,0));
        rejected(IllegalArgumentException.class,()->new TransferBudget.State(false,-1,0,0));
        rejected(IllegalArgumentException.class,()->new TransferBudget.State(false,0,-1,0));
        rejected(IllegalArgumentException.class,()->new TransferBudget.State(false,0,0,-1));
        rejected(IllegalArgumentException.class,()->TransferBudget.available(null));
        check(TransferBudget.available(new TransferBudget.State(true,10,Long.MAX_VALUE,0))==0);
        check(TransferBudget.available(new TransferBudget.State(true,Long.MAX_VALUE,0,0))==Long.MAX_VALUE);
    }
    private static void defaultAndManualCycle() {
        TransferBudget.State initial=TransferBudget.DEFAULT_STATE;
        check(!initial.enabled && initial.limitBytes==0 && initial.chargedBytes==0 && initial.revision==0);
        check(TransferBudget.reserve(initial,Long.MAX_VALUE,0)==initial);
        rejected(IllegalStateException.class,()->TransferBudget.reserve(initial,1,1));
        rejected(IllegalArgumentException.class,()->TransferBudget.reserve(initial,0,0));
        TransferBudget.State enabled=TransferBudget.configure(initial,true,100,0,false);
        check(enabled.enabled && enabled.limitBytes==100 && enabled.chargedBytes==0 && enabled.revision==1);
        TransferBudget.State first=TransferBudget.reserve(enabled,40,1);
        check(first.chargedBytes==40 && first.revision==2 && TransferBudget.available(first)==60);
        // The original immutable input is unchanged until the caller persists the returned state.
        check(enabled.chargedBytes==0 && enabled.revision==1);
        // A failed/unknown first attempt has no refund. A reconstructed process sees the full charge.
        TransferBudget.State restart=recovered(first); same(restart,first);
        TransferBudget.State retry=TransferBudget.reserve(restart,40,2);
        check(retry.chargedBytes==80 && retry.revision==3);
        check(TransferBudget.reserve(retry,40,3)==null);
        check(retry.chargedBytes==80 && retry.revision==3);
        TransferBudget.State exact=TransferBudget.reserve(retry,20,3);
        check(exact.chargedBytes==100 && TransferBudget.available(exact)==0 && exact.revision==4);
        check(TransferBudget.reserve(exact,1,4)==null);
        rejected(IllegalStateException.class,()->TransferBudget.reset(exact,4,true));
        rejected(IllegalStateException.class,()->TransferBudget.reset(exact,3,false));
        rejected(IllegalStateException.class,()->TransferBudget.configure(exact,false,0,4,true));
        TransferBudget.State disabled=TransferBudget.configure(exact,false,0,4,false);
        check(!disabled.enabled && disabled.chargedBytes==100 && disabled.revision==5);
        check(TransferBudget.reserve(disabled,Long.MAX_VALUE,5)==disabled);
        TransferBudget.State reenabled=TransferBudget.configure(disabled,true,50,5,false);
        check(reenabled.chargedBytes==100 && TransferBudget.available(reenabled)==0);
        check(TransferBudget.reserve(reenabled,1,6)==null);
        TransferBudget.State reset=TransferBudget.reset(reenabled,6,false);
        check(reset.enabled && reset.limitBytes==50 && reset.chargedBytes==0 && reset.revision==7);
        TransferBudget.State last=TransferBudget.reserve(reset,50,7);
        check(last.chargedBytes==50 && last.revision==8);
        rejected(IllegalStateException.class,()->TransferBudget.configure(last,false,0,7,false));
        // Explicit reset while disabled is permitted but remains visible as a separate revision.
        TransferBudget.State offAgain=TransferBudget.configure(last,false,0,8,false);
        TransferBudget.State offReset=TransferBudget.reset(offAgain,9,false);
        check(!offReset.enabled && offReset.chargedBytes==0 && offReset.revision==10);
    }
    private static void overflowAndStaleness() {
        TransferBudget.State max=new TransferBudget.State(true,Long.MAX_VALUE,Long.MAX_VALUE-5,7);
        check(TransferBudget.reserve(max,6,7)==null);
        TransferBudget.State full=TransferBudget.reserve(max,5,7);
        check(full.chargedBytes==Long.MAX_VALUE && full.revision==8);
        rejected(IllegalStateException.class,()->TransferBudget.reserve(max,1,6));
        rejected(IllegalStateException.class,()->TransferBudget.configure(max,true,9,-1,false));
        rejected(IllegalArgumentException.class,()->TransferBudget.configure(max,true,0,7,false));
        rejected(IllegalArgumentException.class,()->TransferBudget.configure(max,false,-1,7,false));
        TransferBudget.State exhausted=new TransferBudget.State(true,10,0,Long.MAX_VALUE);
        rejected(IllegalStateException.class,()->TransferBudget.reserve(exhausted,1,Long.MAX_VALUE));
        rejected(IllegalStateException.class,()->TransferBudget.reset(exhausted,Long.MAX_VALUE,false));
        rejected(IllegalStateException.class,()->TransferBudget.configure(exhausted,false,0,Long.MAX_VALUE,false));
        TransferBudget.State off=new TransferBudget.State(false,0,Long.MAX_VALUE,Long.MAX_VALUE);
        check(TransferBudget.reserve(off,1,Long.MAX_VALUE)==off);
    }
    private static void hardGatesUnchanged() {
        TransferPolicy.Network safe=new TransferPolicy.Network(true,true,false,false,false,false);
        TransferPolicy.Network[] unsafe={
            new TransferPolicy.Network(false,true,false,false,false,false),
            new TransferPolicy.Network(true,false,true,true,false,false),
            new TransferPolicy.Network(true,true,false,true,false,false),
            new TransferPolicy.Network(true,true,false,false,true,false),
            new TransferPolicy.Network(true,true,false,false,false,true),
            new TransferPolicy.Network(true,true,true,false,false,false)};
        TransferBudget.State[] budgets={TransferBudget.DEFAULT_STATE,
            new TransferBudget.State(true,100,0,0),new TransferBudget.State(true,100,100,0)};
        for (TransferBudget.State budget:budgets) {
            TransferBudget.State before=recovered(budget);
            // Correct composition is conjunctive. This model makes no claim about Android adapters.
            for(boolean uploadNow:new boolean[]{false,true}) {
                check(TransferPolicy.DEFAULT.evaluate(9600000,0,false,uploadNow,false,safe)
                    ==TransferPolicy.Block.NOT_CHARGING);
                for(TransferPolicy.Network network:unsafe)
                    check(TransferPolicy.DEFAULT.evaluate(9600000,0,false,uploadNow,true,network)
                        !=TransferPolicy.Block.NONE);
            }
            same(before,budget);
            check(TransferPolicy.DEFAULT.evaluate(9600000,0,false,false,true,safe)==TransferPolicy.Block.NONE);
            TransferBudget.State admitted=TransferBudget.reserve(budget,1,budget.revision);
            check((admitted!=null)==(!budget.enabled || budget.chargedBytes<budget.limitBytes));
        }
    }
    public static void main(String[] args) {
        estimateBounds(); stateValidation(); defaultAndManualCycle(); overflowAndStaleness(); hardGatesUnchanged();
        System.out.println("TransferBudgetTest: " + checks + " host assertions passed; no Android/persistence/network proof");
    }
    private TransferBudgetTest() { }
}
