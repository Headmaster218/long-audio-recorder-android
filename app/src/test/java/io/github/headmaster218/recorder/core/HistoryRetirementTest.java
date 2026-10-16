package io.github.headmaster218.recorder.core;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HistoryRetirementTest {
    private static int checks;
    interface Action {void run()throws Exception;}
    static void yes(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
    static void fails(Action a)throws Exception{checks++;try{a.run();}catch(IOException e){return;}throw new AssertionError("expected failure");}
    static final DirectorySpool.DirectoryProtocol NORMAL=new DirectorySpool.NioProtocol();
    static final DirectorySpool.Faults NONE=(op,path)->{};
    static final CachePolicy CACHE=new CachePolicy(1024*1024,0);
    static final String CAPTURE="synthetic-retirement",HASH=SegmentIdentity.capture(CAPTURE),STORE="00000000-0000-0000-0000-000000000001";
    static final class Fixture {
        final Path root;final String source;final VerifiedLocalCleanup.Prepared binding;
        Fixture()throws Exception {
            root=Files.createTempDirectory(Paths.get("app/build"),"history-synthetic-").toAbsolutePath();
            try(DirectorySpool spool=new DirectorySpool(root,CACHE,NORMAL,NONE)) {
                CaptureTimeline.Epoch epoch=new CaptureTimeline.Epoch(CAPTURE,"run",0,new PcmFormat(16000,1),"mic","mic",CaptureTimeline.Gap.START);
                PcmSegmentWriter writer=new PcmSegmentWriter(spool,epoch,8,0,p->{});writer.append(new byte[16],0,16);writer.stop();
            }
            CommittedSegments catalog=new CommittedSegments(root);CommittedSegments.Entry entry=catalog.page(null).entries.get(0);source=entry.id;
            binding=VerifiedLocalCleanup.prepare(catalog,entry,()->false);
            try(DirectorySpool spool=new DirectorySpool(root,CACHE,NORMAL,NONE)){spool.quarantineVerified(binding);spool.purgeQuarantine(binding);}
        }
        Path marker(){return root.resolve(source.replace(".ready",".retired"));}
    }
    static DirectorySpool.Initializer floor(long next){return (root,protocol)->{RetirementSentinel.write(root,new RetirementSentinel.State(STORE,1),protocol);Map<String,Long> map=new HashMap<String,Long>();map.put(HASH,next);return map;};}
    public static void main(String[]args)throws Exception {
        String source="segment-"+HASH+"-0.ready";yes(SegmentIdentity.parse(source).sequence==0);yes(SegmentIdentity.parse(source).capture.equals(HASH));
        for(String bad:new String[]{source.replace("-0.ready","-00.ready"),source.replace(".ready",".part"),source.replace("-0.ready","-9223372036854775808.ready"),source.replace("-0.ready","-9223372036854775807.ready"),"../"+source})fails(()->SegmentIdentity.parse(bad));
        List<RetirementRanges.Range> ranges=new ArrayList<RetirementRanges.Range>();
        ranges=RetirementRanges.add(ranges,1);ranges=RetirementRanges.add(ranges,3);yes(ranges.size()==2);ranges=RetirementRanges.add(ranges,2);yes(ranges.size()==1&&ranges.get(0).low==1&&ranges.get(0).high==3);
        ranges=RetirementRanges.add(ranges,2);yes(ranges.size()==1);ranges=RetirementRanges.add(ranges,0);yes(ranges.get(0).low==0);
        fails(()->RetirementRanges.add(Arrays.asList(new RetirementRanges.Range(1,2),new RetirementRanges.Range(3,4)),0));
        fails(()->RetirementRanges.add(Collections.emptyList(),Long.MAX_VALUE));
        yes(RetirementRanges.add(Collections.emptyList(),Long.MAX_VALUE-1).get(0).high==Long.MAX_VALUE-1);
        SpoolUsage usage=new SpoolUsage();usage.inventory("other",9);
        for(int i=0;i<5000;i++){String id="segment-"+HASH+"-"+i+".retired";usage.inventory(id,0);usage.retireZero(id,false);yes(usage.objects()==1&&usage.total()==9);}
        fails(()->usage.retireZero("other",false));fails(()->usage.retireZero("missing",false));usage.retireZero("missing",true);yes(usage.total()==9);
        // Real owner cycles exercise both eligibility sets, beyond the 5000 ledger-only loop.
        Path cycles=Files.createTempDirectory(Paths.get("app/build"),"history-cycles-").toAbsolutePath();
        try(DirectorySpool spool=new DirectorySpool(cycles,CACHE,NORMAL,NONE)) {
            java.lang.reflect.Field eligible=DirectorySpool.class.getDeclaredField("eligible"),confirmed=DirectorySpool.class.getDeclaredField("confirmed");
            eligible.setAccessible(true);confirmed.setAccessible(true);
            for(int i=0;i<32;i++) {
                CaptureTimeline.Epoch epoch=new CaptureTimeline.Epoch(CAPTURE,"run",0,new PcmFormat(16000,1),"mic","mic",CaptureTimeline.Gap.START);
                PcmSegmentWriter writer=new PcmSegmentWriter(spool,epoch,8,i,p->{});writer.append(new byte[16],0,16);writer.stop();
                CommittedSegments catalog=new CommittedSegments(cycles);CommittedSegments.Entry entry=catalog.page(null).entries.get(0);
                VerifiedLocalCleanup.Prepared binding=VerifiedLocalCleanup.prepare(catalog,entry,()->false);
                try(DirectorySpool.Maintenance lease=spool.maintenance(entry.id)){lease.confirm(()->false);lease.quarantine(binding);lease.purge(binding);}
                try(DirectorySpool.Maintenance lease=spool.retirement(entry.id,i+1)){lease.pruneRetired(binding,false);}
                yes(((Set<?>)eligible.get(spool)).isEmpty()&&((Set<?>)confirmed.get(spool)).isEmpty());
                yes(spool.nextSequence(CAPTURE)==i+1);
            }
            // Concurrent low/high updates must never lower the shared sequence floor.
            java.lang.reflect.Method update=DirectorySpool.class.getDeclaredMethod("sequenceFloor",String.class,long.class);
            update.setAccessible(true);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
            Thread high=new Thread(()->{try{start.await();for(long i=1000;i<=10000;i++)update.invoke(spool,"segment-"+HASH+"-",i);}catch(Throwable e){failure.set(e);}});
            Thread low=new Thread(()->{try{start.await();for(long i=1;i<=10000;i++)update.invoke(spool,"segment-"+HASH+"-",i%1000);}catch(Throwable e){failure.set(e);}});
            high.start();low.start();start.countDown();long last=spool.nextSequence(CAPTURE);
            while(high.isAlive()||low.isAlive()){long next=spool.nextSequence(CAPTURE);if(next<last)throw new AssertionError("sequence floor regressed");last=next;Thread.yield();}
            high.join();low.join();yes(failure.get()==null);yes(spool.nextSequence(CAPTURE)==10000);
        }
        Fixture f=new Fixture();
        try(DirectorySpool spool=new DirectorySpool(f.root,CACHE,NORMAL,NONE,floor(1))) {
            try(DirectorySpool.Maintenance lease=spool.retirement(f.source,1)){lease.pruneRetired(f.binding,false);fails(()->lease.purge(f.binding));}
            yes(!Files.exists(f.marker()));yes(spool.nextSequence(CAPTURE)==1);
            try(DirectorySpool.Maintenance lease=spool.retirement(f.source,1)){lease.pruneRetired(f.binding,true);}
            yes(spool.nextSequence(CAPTURE)==1);
        }
        fails(()->{try(DirectorySpool wrong=new DirectorySpool(f.root,CACHE,NORMAL,NONE)){wrong.nextSequence(CAPTURE);}});
        try(DirectorySpool restarted=new DirectorySpool(f.root,CACHE,NORMAL,NONE,floor(17))){yes(restarted.nextSequence(CAPTURE)==17);}
        Fixture replaced=new Fixture();Files.move(replaced.marker(),replaced.root.resolve("original-marker"));Files.createDirectory(replaced.marker());
        try(DirectorySpool spool=new DirectorySpool(replaced.root,CACHE,NORMAL,NONE,floor(1))){try(DirectorySpool.Maintenance lease=spool.retirement(replaced.source,1)){fails(()->lease.pruneRetired(replaced.binding,false));}yes(Files.exists(replaced.marker()));}
        Fixture nonempty=new Fixture();Files.write(nonempty.marker().resolve("preserve"),new byte[]{1});
        try(DirectorySpool spool=new DirectorySpool(nonempty.root,CACHE,NORMAL,NONE,floor(1))){try(DirectorySpool.Maintenance lease=spool.retirement(nonempty.source,1)){fails(()->lease.pruneRetired(nonempty.binding,false));}yes(Files.exists(nonempty.marker().resolve("preserve")));}
        Fixture crash=new Fixture();AtomicBoolean fail=new AtomicBoolean();
        DirectorySpool.DirectoryProtocol fault=new DirectorySpool.DirectoryProtocol(){public void atomicPublish(Path a,Path b)throws IOException{NORMAL.atomicPublish(a,b);}public void syncDirectory(Path path)throws IOException{NORMAL.syncDirectory(path);if(fail.compareAndSet(true,false))throw new IOException("synthetic post-unlink sync failure");}};
        try(DirectorySpool spool=new DirectorySpool(crash.root,CACHE,fault,NONE,floor(1))){try(DirectorySpool.Maintenance lease=spool.retirement(crash.source,1)){fail.set(true);fails(()->lease.pruneRetired(crash.binding,false));}yes(!Files.exists(crash.marker()));}
        try(DirectorySpool spool=new DirectorySpool(crash.root,CACHE,NORMAL,NONE,floor(1))){try(DirectorySpool.Maintenance lease=spool.retirement(crash.source,1)){lease.pruneRetired(crash.binding,true);}yes(spool.nextSequence(CAPTURE)==1);}
        System.out.println("PASS: "+checks+" canonical interval/empty-marker/ledger retirement assertions; fresh synthetic files only");
    }
}
