package io.github.headmaster218.recorder.core;

public final class RecordingCapacityTest {
    private static int checks;
    private static void yes(boolean value){checks++;if(!value)throw new AssertionError("case "+checks);}
    private static RecordingCapacity estimate(long max,long used,long free,int objects,long pcm,int duration){
        return RecordingCapacity.estimate(max,used,free,32L*1024*1024,objects,pcm,duration,65536);
    }
    public static void main(String[] args) {
        long mib=1024L*1024, pcm=16000L*2*300;
        RecordingCapacity normal=estimate(512*mib,0,10*1024*mib,0,pcm,300);
        yes(normal.wholeSegments==55);yes(normal.seconds==16500); // 4 h 35, not a 24 h claim.
        yes(normal.writableBytes==512*mib-2*DirectorySpool.FINALIZATION_RESERVE-65536);
        RecordingCapacity disk=estimate(512*mib,0,40*mib,0,pcm,300);
        yes(disk.wholeSegments==0);yes(disk.writableBytes<8*mib);
        yes(estimate(512*mib,512*mib,10*1024*mib,0,pcm,300).writableBytes==0);
        yes(estimate(512*mib,513*mib,10*1024*mib,0,pcm,300).seconds==0);
        yes(estimate(512*mib,0,31*mib,0,pcm,300).seconds==0);
        yes(estimate(512*mib,0,32*mib,0,pcm,300).seconds==0);
        yes(estimate(512*mib,0,10*1024*mib,4095,pcm,300).seconds==0);
        yes(estimate(512*mib,0,10*1024*mib,4094,pcm,300).wholeSegments==1);
        yes(estimate(512*mib,0,10*1024*mib,4096,pcm,300).wholeSegments==0);
        yes(estimate(512*mib,0,10*1024*mib,Integer.MAX_VALUE,pcm,300).seconds==0);
        yes(estimate(Long.MAX_VALUE,0,Long.MAX_VALUE,0,Long.MAX_VALUE,300).seconds==0);
        long overhead=2L*SegmentMetadata.MAX_BYTES+WavHeader.BYTES;
        long reserve=2*DirectorySpool.FINALIZATION_RESERVE+65536;
        yes(estimate(reserve+pcm+overhead,0,Long.MAX_VALUE,0,pcm,300).wholeSegments==1);
        yes(estimate(reserve+pcm+overhead-1,0,Long.MAX_VALUE,0,pcm,300).wholeSegments==0);
        yes(estimate(512*mib,0,Long.MAX_VALUE,0,96000L*4*300,300).seconds<normal.seconds);
        for(long used=0;used<512*mib;used+=mib){
            RecordingCapacity c=estimate(512*mib,used,Long.MAX_VALUE,0,pcm,300);
            yes(c.wholeSegments*(pcm+overhead)<=c.writableBytes);
            yes(c.seconds==c.wholeSegments*300);
        }
        try {estimate(1,-1,1,0,1,1);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PASS recording capacity: "+checks+" assertions; arithmetic only, no device timing guarantee");
    }
}
