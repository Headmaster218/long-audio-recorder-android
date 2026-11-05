package io.github.headmaster218.recorder.core;
import java.nio.file.*;
import java.io.IOException;
public final class OfflineDayPlanTest {
    static int checks;
    static void yes(boolean b){checks++;if(!b)throw new AssertionError("case "+checks);}
    static void rejected(int duration,long used,long free,int objects) {
        try{OfflineDayPlan.calculate(16000,1,duration,used,free,objects);throw new AssertionError("not rejected");}
        catch(IllegalArgumentException expected){checks++;}
    }
    public static void main(String[]args)throws Exception {
        OfflineDayPlan d=OfflineDayPlan.calculate(16000,1,300,0,Long.MAX_VALUE,0);
        yes(d.segmentCount==288);yes(d.cacheMiB>2700 && d.cacheMiB<2900);
        yes(d.requiredFreeBytes>86400L*32000+32*OfflineDayPlan.MIB);
        yes(OfflineDayPlan.calculate(16000,1,300,0,d.requiredFreeBytes,0).cacheMiB==d.cacheMiB);
        rejected(300,0,d.requiredFreeBytes-1,0);rejected(1,0,Long.MAX_VALUE,0);
        rejected(20,0,Long.MAX_VALUE,0);rejected(300,0,Long.MAX_VALUE,4096);
        rejected(300,Long.MAX_VALUE,Long.MAX_VALUE,0);rejected(300,262144*OfflineDayPlan.MIB,Long.MAX_VALUE,0);
        yes(OfflineDayPlan.calculate(16000,1,300,OfflineDayPlan.MIB,Long.MAX_VALUE,1).cacheMiB==d.cacheMiB+1);
        yes(OfflineDayPlan.calculate(16000,1,300,0,Long.MAX_VALUE,4096-288-4).segmentCount==288);
        rejected(300,0,Long.MAX_VALUE,4096-288-3);
        for(int rate:new int[]{8000,16000,44100,48000,96000})for(int channels:new int[]{1,2})for(int seconds:new int[]{30,60,300,3599,3600}) {
            OfflineDayPlan p=OfflineDayPlan.calculate(rate,channels,seconds,123456,Long.MAX_VALUE,17);
            yes(p.segmentCount*seconds>=86400);
            RecordingCapacity capacity=RecordingCapacity.estimate(p.cacheMiB*OfflineDayPlan.MIB,123456+4096, p.requiredFreeBytes-4096,OfflineDayPlan.FREE_RESERVE,19,(long)rate*channels*2*seconds,seconds,65536);
            yes(capacity.seconds>=86400);
        }
        Path files=Files.createTempDirectory("offline-day-plan-");
        OfflineDayPlan empty=OfflineDayPlan.inspect(files,8000,1,300);
        yes(!Files.exists(files.resolve("audio-spool")));yes(empty.existingBytes==0);
        Path spool=Files.createDirectory(files.resolve("audio-spool"));Path dir=Files.createDirectory(spool.resolve("segment-test"));Path wav=dir.resolve("audio.wav");byte[] data=new byte[]{1,2,3};Files.write(wav,data);Files.write(spool.resolve(".writer.lock"),new byte[]{4});
        OfflineDayPlan existing=OfflineDayPlan.inspect(files,8000,1,300);
        yes(existing.existingBytes==4);yes(existing.existingObjects==2);yes(java.util.Arrays.equals(data,Files.readAllBytes(wav)));
        Path nested=Files.createDirectory(dir.resolve("nested"));
        try{OfflineDayPlan.inspect(files,8000,1,300);throw new AssertionError();}catch(IOException expected){checks++;}
        Files.delete(nested);Path link=spool.resolve("linked");Files.createSymbolicLink(link,files);
        try{OfflineDayPlan.inspect(files,8000,1,300);throw new AssertionError();}catch(IOException expected){checks++;}
        yes(Files.isSymbolicLink(link));yes(Files.exists(wav));
        Files.delete(link);
        try{OfflineDayPlan.inspect(files,8000,1,300,new Runnable(){public void run(){try{Files.write(wav,new byte[]{1,2,3,4});}catch(IOException e){throw new RuntimeException(e);}}});throw new AssertionError();}catch(IOException expected){checks++;}
        yes(Files.size(wav)==4); // read-only planner never rolls a concurrent writer back.
        String id="segment-"+String.join("",java.util.Collections.nCopies(64,"a"))+"-0";
        Files.createDirectory(spool.resolve(id+".ready"));Files.createDirectory(spool.resolve(id+".retired"));
        try{OfflineDayPlan.inspect(files,8000,1,300);throw new AssertionError();}catch(IOException expected){checks++;}
        ReadOnlyPlanGate gate=new ReadOnlyPlanGate();Object first=gate.begin();yes(gate.busy());
        yes(gate.finish(first));yes(!gate.busy()); // Success or caught scan failure both release controls.
        Object paused=gate.begin();gate.cancel();yes(!gate.busy()); // Pause/rotate never waits on I/O.
        Object resumed=gate.begin();yes(!gate.finish(paused));yes(gate.busy());
        yes(gate.finish(resumed));yes(!gate.busy());yes(!gate.finish(resumed));
        System.out.println("PASS offline day planning: "+checks+" assertions; read-only inspection and arithmetic, not device endurance");
    }
}
