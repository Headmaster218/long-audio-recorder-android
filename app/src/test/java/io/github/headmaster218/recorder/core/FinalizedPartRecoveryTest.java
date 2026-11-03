package io.github.headmaster218.recorder.core;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit sealed-object recovery only. All fixtures are tiny synthetic host data. */
public final class FinalizedPartRecoveryTest {
    private static int checks, cases;
    private static Path base;
    private interface Action { void run() throws Exception; }
    private static void yes(boolean value) { checks++; if(!value)throw new AssertionError("check "+checks); }
    private static void rejects(Action action)throws Exception {
        checks++;try{action.run();}catch(IOException | IllegalArgumentException expected){return;}
        throw new AssertionError("expected rejection "+checks);
    }
    private static CaptureTimeline.Epoch epoch() {
        return new CaptureTimeline.Epoch("sealed-recovery","run",0,PcmFormat.DEFAULT,"synthetic","synthetic",CaptureTimeline.Gap.START);
    }
    private static DirectorySpool open(Path root,DirectorySpool.Faults faults)throws IOException {
        return new DirectorySpool(root,new CachePolicy(2000000,0),new DirectorySpool.NioProtocol(),faults);
    }
    private static String object(Path root,String suffix)throws IOException {
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(root,"segment-*"+suffix)) {
            for(Path p:entries)return p.getFileName().toString();
        }
        throw new IOException("missing "+suffix);
    }
    private static void child(String mode,Path root,String point,String reason)throws Exception {
        if(mode.equals("seal")) {
            final boolean[] finishing={false};
            DirectorySpool store=open(root,(op,p)->{
                if(finishing[0]&&op.name().equals(point.isEmpty()?"SYNC_STAGE":point))Runtime.getRuntime().halt(73);
            });
            PcmSegmentWriter writer=PcmSegmentWriter.terminalAware(store,epoch(),4,0,p->{throw new AssertionError("unexpected publish");});
            byte[] pcm={7,2,5,4,3,6,1,8};
            CaptureAnchors.Read observation=reason.equals("ANCHORED_STOP")?new CaptureAnchors.Read(
                "00000000-0000-0000-0000-000000000001","run",0,0,4,1,2,3,4,5,6,0,0,
                CaptureAnchors.Timestamp.notPolled(),CaptureAnchors.RUN_START):null;
            writer.append(pcm,0,pcm.length,observation);
            finishing[0]=true;
            if(reason.endsWith("STOP"))writer.stop();
            else if(reason.equals("INTERRUPTION"))writer.interrupt();
            else writer.append(new byte[]{9,10},0,2);
        } else {
            DirectorySpool store=open(root,(op,p)->{if(op.name().equals(point))Runtime.getRuntime().halt(73);});
            store.recoverFinalizedPart(object(root,".part"));
        }
        throw new AssertionError("halt not reached");
    }
    private static void runChild(String mode,Path root,String point,String reason)throws Exception {
        Process process=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),
            "-Xmx32m","-XX:+UseSerialGC","-XX:ActiveProcessorCount=1","-cp",System.getProperty("java.class.path"),
            FinalizedPartRecoveryTest.class.getName(),mode,root.toString(),point,reason).inheritIO().start();
        if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor();throw new AssertionError("timeout");}
        yes(process.exitValue()==73);
    }
    private static Path fixture(String reason)throws Exception {
        Path root=base.resolve("case-"+cases++);runChild("seal",root,"",reason);return root;
    }
    private static void recover(Path root,String reason,byte[] manifest,byte[] wav)throws Exception {
        try(DirectorySpool store=open(root,(op,p)->{})) {
            String part=object(root,".part");long charged=store.chargedBytes();
            SegmentStore.Published result=store.recoverFinalizedPart(part);
            yes(result.metadata.closeReason==CaptureTimeline.Close.valueOf(reason.equals("ANCHORED_STOP")?"STOP":reason));
            yes(result.metadata.schemaVersion==(reason.equals("ANCHORED_STOP")?2:1));
            yes(Arrays.equals(result.metadata.encode(),manifest));
            yes(Arrays.equals(Files.readAllBytes(root.resolve(result.localObjectId).resolve("manifest.bin")),manifest));
            yes(Arrays.equals(Files.readAllBytes(root.resolve(result.localObjectId).resolve("audio.wav")),wav));
            yes(!Files.exists(root.resolve(part)));yes(store.chargedBytes()==charged);yes(store.nextSequence("sealed-recovery")==1);
            store.confirmReady(result.localObjectId);
            CommittedSegments catalog=new CommittedSegments(root);
            CommittedSegments.Entry entry=catalog.page(null).entries.get(0);catalog.verify(entry,()->false);
            String revision="synthetic-recovery";
            String attempt=FtpsTransfer.newAttemptName(entry.id,entry.metadata.wavSha256,revision);
            FtpsDelivery delivery=FtpsDelivery.encode(entry,revision,attempt);
            try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(delivery.metadataBytes()))) {
                yes(input.readInt()==FtpsDelivery.MAGIC);yes(input.readUTF().equals(revision));yes(input.readUTF().equals(entry.id));
                byte[] transported=new byte[input.readInt()];input.readFully(transported);
                yes(Arrays.equals(transported,manifest));yes(input.read()==-1);
            }
            String output=System.getProperty("sealed.delivery.root");
            if(output!=null) {
                Path parent=Files.createDirectories(Paths.get(output).resolve(root.getFileName()));
                Path destination=Files.createDirectory(parent.resolve(attempt));
                Files.copy(root.resolve(entry.id).resolve("audio.wav"),destination.resolve("audio.wav"));
                Files.write(destination.resolve("segment.v1.bin"),delivery.metadataBytes());
                Files.write(destination.resolve("delivery.v1.txt"),delivery.markerBytes());
            }
            try(DirectorySpool.Maintenance lease=store.maintenance(result.localObjectId)){lease.confirm(()->false);}
            rejects(()->store.recoverFinalizedPart(part));
        }
    }
    private static void normalAndCrash()throws Exception {
        for(String reason:new String[]{"STOP","INTERRUPTION","ROTATION","ANCHORED_STOP"}) {
            Path root=fixture(reason);Path part=root.resolve(object(root,".part"));
            byte[] manifest=Files.readAllBytes(part.resolve("manifest.bin")),wav=Files.readAllBytes(part.resolve("audio.wav"));
            recover(root,reason,manifest,wav);
        }
        for(String point:new String[]{"SYNC_MANIFEST","PUBLISH"}) {
            Path root=base.resolve("case-"+cases++);runChild("seal",root,point,"STOP");
            Path part=root.resolve(object(root,".part"));
            recover(root,"STOP",Files.readAllBytes(part.resolve("manifest.bin")),Files.readAllBytes(part.resolve("audio.wav")));
        }
        for(String point:new String[]{"SYNC_INTENT","SYNC_FINAL_WAV","SYNC_MANIFEST","SYNC_STAGE","PUBLISH","SYNC_ROOT","VALIDATE"}) {
            Path root=fixture("STOP");Path part=root.resolve(object(root,".part"));
            byte[] manifest=Files.readAllBytes(part.resolve("manifest.bin")),wav=Files.readAllBytes(part.resolve("audio.wav"));
            runChild("recover",root,point,"");
            if(point.equals("SYNC_ROOT")||point.equals("VALIDATE")) {
                try(DirectorySpool store=open(root,(op,p)->{})) {
                    SegmentStore.Published result=store.confirmReady(object(root,".ready"));
                    yes(Arrays.equals(result.metadata.encode(),manifest));
                    yes(Arrays.equals(Files.readAllBytes(root.resolve(result.localObjectId).resolve("audio.wav")),wav));
                }
            } else recover(root,"STOP",manifest,wav);
        }
    }
    private static void rejectedEvidence()throws Exception {
        for(String corruption:new String[]{"missing-manifest","bad-manifest","bad-pcm","placeholder","extra-entry","wrong-name"}) {
            Path root=fixture("STOP");String name=object(root,".part");Path part=root.resolve(name);
            if(corruption.equals("missing-manifest"))Files.move(part.resolve("manifest.bin"),base.resolve("retained-manifest-"+cases));
            if(corruption.equals("bad-manifest"))Files.write(part.resolve("manifest.bin"),new byte[]{1,2,3});
            if(corruption.equals("bad-pcm")){byte[] wav=Files.readAllBytes(part.resolve("audio.wav"));wav[44]^=1;Files.write(part.resolve("audio.wav"),wav);}
            if(corruption.equals("placeholder")){byte[] wav=Files.readAllBytes(part.resolve("audio.wav"));System.arraycopy(WavHeader.encode(PcmFormat.DEFAULT,0),0,wav,0,44);Files.write(part.resolve("audio.wav"),wav);}
            if(corruption.equals("extra-entry"))Files.write(part.resolve("unexpected"),new byte[]{1});
            if(corruption.equals("wrong-name")){name=name.replace("-0.part","-1.part");Files.move(part,root.resolve(name));part=root.resolve(name);}
            final String id=name;byte[] wav=Files.readAllBytes(part.resolve("audio.wav"));
            try(DirectorySpool store=open(root,(op,p)->{})){rejects(()->store.recoverFinalizedPart(id));}
            yes(Files.exists(part));yes(Arrays.equals(wav,Files.readAllBytes(part.resolve("audio.wav"))));
            yes(new CommittedSegments(root).page(null).entries.isEmpty());
        }
        Path root=fixture("STOP");String part=object(root,".part");
        try(DirectorySpool store=open(root,(op,p)->{})) {
            Path target=root.resolve(part.replace(".part",".ready"));Files.createDirectory(target);
            rejects(()->store.recoverFinalizedPart(part));yes(Files.isDirectory(target));yes(Files.exists(root.resolve(part)));
        }
        root=fixture("STOP");final String stale=object(root,".part");
        try(DirectorySpool store=open(root,(op,p)->{})) {
            store.open(new SegmentMetadata.Intent(epoch(),1,4,4));
            rejects(()->store.recoverFinalizedPart(stale));
        }
        root=fixture("STOP");final String linked=object(root,".part");
        try(DirectorySpool store=open(root,(op,p)->{})) {
            Path wav=root.resolve(linked).resolve("audio.wav"),saved=base.resolve("saved-wav-"+cases);
            Files.move(wav,saved);Files.createSymbolicLink(wav,saved.toAbsolutePath());
            rejects(()->store.recoverFinalizedPart(linked));yes(Files.isSymbolicLink(wav));
        }
        root=fixture("STOP");final String failed=object(root,".part");
        try(DirectorySpool store=open(root,(op,p)->{if(op==DirectorySpool.Operation.SYNC_ROOT)throw new IOException("injected root sync failure");})) {
            rejects(()->store.recoverFinalizedPart(failed));
            rejects(()->store.open(new SegmentMetadata.Intent(epoch(),1,4,4)));
            rejects(()->store.recoverFinalizedPart(failed));
        }
        try(DirectorySpool store=open(root,(op,p)->{})){store.confirmReady(object(root,".ready"));yes(store.nextSequence("sealed-recovery")==1);}
    }
    private static void exclusion()throws Exception {
        for(String mode:new String[]{"open","close","maintenance","second-recovery"}) {
            Path root=fixture("STOP");String part=object(root,".part");
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
            DirectorySpool store=open(root,(op,p)->{
                if(op==DirectorySpool.Operation.SYNC_STAGE) {
                    entered.countDown();
                    try{if(!release.await(20,TimeUnit.SECONDS))throw new IOException("test latch timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
                }
            });
            Thread recovery=new Thread(()->{try{store.recoverFinalizedPart(part);}catch(Throwable t){failure.compareAndSet(null,t);}});
            Thread competing=new Thread(()->{try{
                if(mode.equals("open"))store.open(new SegmentMetadata.Intent(epoch(),1,4,4));
                else if(mode.equals("close"))store.close();
                else if(mode.equals("maintenance"))store.maintenance(part.replace(".part",".ready")).close();
                else {
                    try{store.recoverFinalizedPart(part);throw new AssertionError("duplicate recovery succeeded");}
                    catch(IOException expected){/* The first recovery consumed the original name. */}
                }
            }catch(Throwable t){failure.compareAndSet(null,t);}});
            recovery.setDaemon(true);competing.setDaemon(true);
            recovery.start();
            try {
                yes(entered.await(5,TimeUnit.SECONDS));competing.start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(competing.getState()!=Thread.State.BLOCKED&&competing.isAlive()&&System.nanoTime()<deadline)Thread.sleep(5);
                yes(competing.getState()==Thread.State.BLOCKED);
            } finally {release.countDown();}
            recovery.join(5000);competing.join(5000);
            yes(!recovery.isAlive()&&!competing.isAlive());
            if(failure.get()!=null)throw new AssertionError("exclusion "+mode,failure.get());
            store.close();
            yes(Files.exists(root.resolve(part.replace(".part",".ready"))));
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length>0){child(args[0],Paths.get(args[1]),args[2],args[3]);return;}
        base=Files.createDirectories(Paths.get("app/build/finalized-part-recovery-tests/run-"+System.nanoTime()));
        normalAndCrash();rejectedEvidence();exclusion();
        System.out.println("PASS explicit finalized-part recovery: "+checks+" assertions, "+cases+" fixtures; host only");
    }
}
