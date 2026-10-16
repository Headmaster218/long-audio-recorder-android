package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ContinuousCleanupTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void yes(boolean value) { checks++; if(!value) throw new AssertionError("check "+checks); }
    private static void fails(Action action)throws Exception { checks++;try{action.run();}catch(IOException e){return;}throw new AssertionError("expected IOException"); }
    private static void denied(Action action,boolean busy)throws Exception { checks++;try{action.run();}catch(IOException e){if((e instanceof DirectorySpool.MaintenanceBusy)==busy)return;throw new AssertionError("wrong rejection type",e);}throw new AssertionError("expected denial"); }
    private static final DirectorySpool.DirectoryProtocol NORMAL=new DirectorySpool.NioProtocol();
    private static final CachePolicy CACHE=new CachePolicy(1024*1024,0);
    private static final DirectorySpool.Faults NONE=(op,path)->{};
    private static CaptureTimeline.Epoch epoch(String capture) {return new CaptureTimeline.Epoch(capture,"run",0,new PcmFormat(16000,1),"mic","mic",CaptureTimeline.Gap.START);}
    private static long bytes(Path root)throws IOException {
        try(java.util.stream.Stream<Path> paths=Files.walk(root)) { return paths.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new RuntimeException(e);}}).sum(); }
    }
    public static void main(String[] args)throws Exception {
        String stem="segment-"+String.join("",java.util.Collections.nCopies(64,"a"))+"-0";
        SpoolUsage usage=new SpoolUsage();usage.inventory(stem+".quarantine",20);usage.inventory("unrelated",100);
        yes(usage.total()==120);usage.purged(stem+".ready");yes(usage.total()==100);usage.purged(stem+".ready");yes(usage.total()==100);
        // Rebuilt census after partial crash credits only surviving bytes, never old binding size.
        SpoolUsage restart=new SpoolUsage();restart.inventory(stem+".quarantine",3);restart.inventory("other",100);
        restart.purged(stem+".ready");yes(restart.total()==100);fails(()->restart.purged("missing"));
        usage.charge("partial",7);yes(usage.total()==107);fails(()->usage.charge("bad",Long.MAX_VALUE));yes(usage.total()==107);
        fails(()->usage.inventory(stem+".retired",0));
        SpoolUsage bound=new SpoolUsage();for(int i=0;i<SpoolUsage.MAX_OBJECTS;i++)bound.inventory("x"+i,0);fails(()->bound.inventory("overflow",0));
        Path root=Files.createTempDirectory(Paths.get("app/build"),"continuous-synthetic-").toAbsolutePath();
        try(DirectorySpool spool=new DirectorySpool(root,CACHE,NORMAL,NONE)) {
            PcmSegmentWriter writer=new PcmSegmentWriter(spool,epoch("continuous"),8,0,p->{});writer.append(new byte[16],0,16);writer.stop();
        }
        CommittedSegments catalog=new CommittedSegments(root);CommittedSegments.Entry entry=catalog.page(null).entries.get(0);
        VerifiedLocalCleanup.Prepared binding=VerifiedLocalCleanup.prepare(catalog,entry,()->false);
        CountDownLatch inside=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicBoolean armed=new AtomicBoolean(),closed=new AtomicBoolean();AtomicReference<Throwable> error=new AtomicReference<Throwable>();
        DirectorySpool.DirectoryProtocol blocked=new DirectorySpool.DirectoryProtocol() {
            public void syncDirectory(Path path)throws IOException {
                NORMAL.syncDirectory(path);
                if(armed.compareAndSet(true,false)) {
                    inside.countDown();try {if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test timeout");}catch(InterruptedException e){throw new IOException(e);}
                }
            }
            public void atomicPublish(Path from,Path to)throws IOException {NORMAL.atomicPublish(from,to);}
        };
        DirectorySpool spool=new DirectorySpool(root,CACHE,blocked,NONE);
        PcmSegmentWriter writer=new PcmSegmentWriter(spool,epoch("continuous"),1000,1,p->{});writer.append(new byte[16],0,16);
        yes(spool.chargedBytes()==bytes(root));
        long charged=spool.chargedBytes();
        writer.append(new byte[16],0,16);yes(spool.chargedBytes()==charged+16);yes(spool.chargedBytes()==bytes(root));
        denied(()->spool.maintenance(entry.id.replace("-0.ready","-1.ready")),false);
        denied(()->spool.maintenance("invalid"),false);
        DirectorySpool.Maintenance lease=spool.maintenance(entry.id);
        denied(()->spool.maintenance(entry.id),true);
        lease.confirm(()->false);
        Thread cleanup=new Thread(()->{try {armed.set(true);lease.quarantine(binding);lease.purge(binding);lease.close();}catch(Throwable e){error.set(e);}},"synthetic-cleanup");
        cleanup.start();yes(inside.await(3,TimeUnit.SECONDS));
        // The cleanup fsync is blocked, but the writer can append without waiting on its monitor.
        writer.append(new byte[16],0,16);yes(Files.exists(root.resolve(entry.id.replace("-0.ready","-1.part")).resolve("audio.wav")));
        Thread stop=new Thread(()->{try{spool.close();closed.set(true);}catch(Throwable e){error.set(e);}},"synthetic-stop");stop.start();
        long waitingDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(stop.getState()!=Thread.State.WAITING && stop.isAlive() && System.nanoTime()<waitingDeadline) Thread.sleep(1);
        yes(stop.getState()==Thread.State.WAITING && !closed.get());fails(()->spool.maintenance(entry.id));
        fails(()->{try(DirectorySpool second=new DirectorySpool(root,CACHE,NORMAL,NONE)){second.nextSequence("continuous");}});
        release.countDown();cleanup.join(3000);stop.join(3000);yes(!cleanup.isAlive()&&!stop.isAlive());yes(error.get()==null);yes(closed.get());yes(spool.chargedBytes()==bytes(root));
        fails(lease::close);fails(()->lease.purge(binding));
        try(DirectorySpool next=new DirectorySpool(root,CACHE,NORMAL,NONE)){yes(next.nextSequence("continuous")==2);yes(next.chargedBytes()==bytes(root));next.purgeQuarantine(binding);yes(next.chargedBytes()==bytes(root));}
        Path crash=Files.createTempDirectory(Paths.get("app/build"),"credit-crash-synthetic-").toAbsolutePath();
        try(DirectorySpool seed=new DirectorySpool(crash,CACHE,NORMAL,NONE)) {
            PcmSegmentWriter two=new PcmSegmentWriter(seed,epoch("credit"),8,0,p->{});two.append(new byte[32],0,32);two.stop();
        }
        CommittedSegments cc=new CommittedSegments(crash);CommittedSegments.Entry ce=cc.page(null).entries.get(0);
        VerifiedLocalCleanup.Prepared cb=VerifiedLocalCleanup.prepare(cc,ce,()->false);AtomicBoolean failSync=new AtomicBoolean();
        DirectorySpool.DirectoryProtocol interrupted=new DirectorySpool.DirectoryProtocol() {
            public void syncDirectory(Path path)throws IOException {NORMAL.syncDirectory(path);if(failSync.compareAndSet(true,false))throw new IOException("synthetic partial purge");}
            public void atomicPublish(Path from,Path to)throws IOException {NORMAL.atomicPublish(from,to);}
        };
        try(DirectorySpool original=new DirectorySpool(crash,CACHE,interrupted,NONE)) {
            original.quarantineVerified(cb);long before=original.chargedBytes();failSync.set(true);fails(()->original.purgeQuarantine(cb));
            yes(original.chargedBytes()==before);yes(original.chargedBytes()>bytes(crash));
        }
        try(DirectorySpool replay=new DirectorySpool(crash,CACHE,NORMAL,NONE)) {
            yes(replay.chargedBytes()==bytes(crash));replay.purgeQuarantine(cb);yes(replay.chargedBytes()==bytes(crash));yes(replay.chargedBytes()>0);
            replay.purgeQuarantine(cb);yes(replay.chargedBytes()==bytes(crash));
        }
        // Unsafe entries are detected at initial census, before capture starts.
        Path unsafe=Files.createTempDirectory(Paths.get("app/build"),"census-synthetic-").toAbsolutePath();
        Files.createSymbolicLink(unsafe.resolve("link"),root);
        fails(()->{try(DirectorySpool bad=new DirectorySpool(unsafe,CACHE,NORMAL,NONE)){bad.nextSequence("x");}});
        Path failed=Files.createTempDirectory(Paths.get("app/build"),"reservation-synthetic-").toAbsolutePath();
        DirectorySpool.Faults fault=(op,path)->{if(op==DirectorySpool.Operation.WRITE_MANIFEST)throw new IOException("synthetic failed reservation");};
        try(DirectorySpool failedSpool=new DirectorySpool(failed,CACHE,NORMAL,fault)) {
            PcmSegmentWriter failedWriter=new PcmSegmentWriter(failedSpool,epoch("reservation"),8,0,p->{});
            fails(()->failedWriter.append(new byte[16],0,16));yes(failedSpool.chargedBytes()>bytes(failed));
        }
        try(DirectorySpool fresh=new DirectorySpool(failed,CACHE,NORMAL,NONE)){yes(fresh.chargedBytes()==bytes(failed));}
        Path orphan=Files.createTempDirectory(Paths.get("app/build"),"orphan-synthetic-").toAbsolutePath();
        Files.write(orphan.resolve("unknown-regular"),new byte[27]);Files.createDirectory(orphan.resolve("unknown-dir"));Files.write(orphan.resolve("unknown-dir/member"),new byte[31]);
        try(DirectorySpool census=new DirectorySpool(orphan,CACHE,NORMAL,NONE)){yes(census.chargedBytes()==58);}
        System.out.println("PASS: "+checks+" continuous-cleanup ledger/concurrency assertions; synthetic only, no Android runtime");
    }
}
