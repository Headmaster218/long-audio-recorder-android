package io.github.headmaster218.recorder.android;
import io.github.headmaster218.recorder.core.RecordingCapacity;
public final class RecordingCapacityDisplayTest {
    private static int checks;
    static void yes(boolean b){checks++;if(!b)throw new AssertionError("case "+checks);}
    public static void main(String[] args)throws Exception {
        RecordingCapacityDisplay.clear();yes(RecordingCapacityDisplay.display(true).contains("unavailable"));
        RecordingCapacity c=RecordingCapacity.estimate(512L*1024*1024,0,Long.MAX_VALUE,32L*1024*1024,0,9600000,300,65536);
        RecordingCapacityDisplay.update(c);
        yes(RecordingCapacityDisplay.display(true).contains("55 whole-segment equivalents"));
        yes(RecordingCapacityDisplay.display(true).contains("4 h 35 min"));
        yes(RecordingCapacityDisplay.display(true).contains("no future upload/cleanup credit"));
        yes(RecordingCapacityDisplay.display(false).contains("only during an active run"));
        java.lang.reflect.Field sampled=RecordingCapacityDisplay.class.getDeclaredField("sampledAt");sampled.setAccessible(true);sampled.setLong(null,System.nanoTime()-6000000000L);
        yes(RecordingCapacityDisplay.display(true).contains("stale"));
        RecordingCapacityDisplay.update(c);yes(!RecordingCapacityDisplay.display(true).contains("stale"));
        RecordingCapacityDisplay.clear();yes(RecordingCapacityDisplay.display(true).contains("unavailable"));
        yes(TransferProgressDisplay.describe(0,0,0).contains("unavailable"));
        yes(!TransferProgressDisplay.describe(0,0,0).contains("0 not remotely verified"));
        yes(TransferProgressDisplay.describe(9600000,0,0).contains("0 not remotely verified; 0 verified"));
        yes(TransferProgressDisplay.describe(9600000,5,2).contains("5 not remotely verified; 2 verified"));
        System.out.println("PASS capacity display: "+checks+" assertions; host text/state only");
    }
}
