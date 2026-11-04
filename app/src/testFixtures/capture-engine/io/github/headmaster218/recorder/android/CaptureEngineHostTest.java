package io.github.headmaster218.recorder.android;

import android.content.Context;
import android.media.AudioRecord;
import io.github.headmaster218.recorder.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Executes unchanged CaptureEngine against scripted platform calls and real core filesystem storage. */
public final class CaptureEngineHostTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void await(CountDownLatch latch,String what)throws Exception{check(latch.await(5,TimeUnit.SECONDS),"Timed out: "+what);}
    private static final class Run implements CaptureEngine.Completion {
        final Path directory;final CaptureEngine engine;final Thread worker;
        final CountDownLatch done=new CountDownLatch(1);
        final boolean stopOnFirst;
        volatile CaptureEvidence.Result result;volatile String text;volatile boolean paused;volatile Throwable uncaught;volatile int completions,firsts;
        Run(Path root,String name,AudioRecord.Scenario scenario,boolean stopOnFirst)throws Exception{
            this.stopOnFirst=stopOnFirst;directory=Files.createDirectory(root.resolve(name));AudioRecord.scenario=scenario;
            engine=new CaptureEngine(new Context(directory.toFile()),new RecordingSettings(8000,1,1,16),name,0,false,this);
            worker=new Thread(engine,"fixture-storage-"+name);worker.setUncaughtExceptionHandler((t,e)->{uncaught=e;done.countDown();});
        }
        void start(){worker.start();}
        void finish()throws Exception{await(done,"engine completion");worker.join(5000);check(!worker.isAlive(),"storage thread exited");check(uncaught==null,"no uncaught exception: "+uncaught);check(completions==1,"exactly one completion");check(result.producerTerminated,"producer terminated");check(result.spool==CaptureEvidence.Closure.CLOSED,"spool closed");}
        public void finished(CaptureEngine e,String s,boolean p,long epoch){throw new AssertionError("typed completion required");}
        public void finished(CaptureEngine e,CaptureEvidence.Result r,String s,boolean p,long epoch){result=r;text=s;paused=p;completions++;done.countDown();}
        public void firstPcm(CaptureEngine e,CaptureEvidence.FirstPcm observation){firsts++;if(stopOnFirst)e.requestStop(CaptureEngine.Stop.STOP);}
        List<SegmentMetadata> manifests()throws Exception{
            List<SegmentMetadata> list=new ArrayList<>();try(java.util.stream.Stream<Path> files=Files.walk(directory)){for(Path p:(Iterable<Path>)files::iterator)if(p.getFileName().toString().equals("manifest.bin")){check(p.getParent().getFileName().toString().endsWith(".ready"),"manifest is published, not staged");list.add(SegmentMetadata.decode(Files.readAllBytes(p)));}}return list;
        }
        void saved(long samples,CaptureTimeline.Close close)throws Exception{
            check(result.pcm.readSamples==samples,"read accounting");check(result.pcm.confirmedSamples==samples,"confirmed accounting");check(!result.pcm.hasUnconfirmed(),"no unconfirmed PCM");check(result.writer==CaptureEvidence.Closure.CLOSED,"writer closed");
            List<SegmentMetadata> m=manifests();check(m.size()==1,"one published manifest");check(m.get(0).closeReason==close,"closure metadata: "+m.get(0).closeReason);check(m.get(0).frameCount==samples,"manifest frame count");
            Path wav;try(java.util.stream.Stream<Path> files=Files.walk(directory)){wav=files.filter(p->p.getFileName().toString().equals("audio.wav")).findFirst().get();}
            byte[] bytes=Files.readAllBytes(wav);check(bytes.length==WavHeader.BYTES+samples*2,"WAV length");for(int i=0;i<samples;i++){int expected=i%32+1;check((bytes[WavHeader.BYTES+i*2]&255)==expected && bytes[WavHeader.BYTES+i*2+1]==0,"PCM bytes at "+i);}
        }
    }
    private static void normal(Path root,String name)throws Exception{
        AudioRecord.Scenario s=new AudioRecord.Scenario();Run r=new Run(root,name,s,true);r.start();r.finish();r.saved(32,CaptureTimeline.Close.STOP);
        check(r.result.failureMask==0,"normal failure-free");check(r.firsts==1,"one first-PCM event");check(!r.paused,"STOP is not pause");check(s.stopCalls==1 && s.releaseCalls==1,"stop/release once");check(r.result.release==CaptureEvidence.Release.RETURNED,"release returned");System.out.println("PASS "+name);
    }
    private static void observationFailure(Path root)throws Exception{
        AudioRecord.Scenario s=new AudioRecord.Scenario();s.observationFailure=true;Run r=new Run(root,"observation-failure",s,false);r.start();r.finish();
        check(r.result.has(CaptureEvidence.Failure.OBSERVATION),"observation failure retained");check(r.result.has(CaptureEvidence.Failure.PCM_ACCOUNTING),"accounting failure retained");check(r.result.pcm.readSamples==32 && r.result.pcm.unconfirmedSamples==32 && r.result.pcm.confirmedSamples==0,"just-read PCM unconfirmed");check(r.result.writer==CaptureEvidence.Closure.NOT_OPENED,"no fabricated writer");check(r.manifests().isEmpty(),"no fabricated saved PCM");check(r.firsts==0,"no first append");check(r.text.startsWith("Unconfirmed PCM:"),"warning first");check(s.releaseCalls==1,"released once");System.out.println("PASS positive read then observation failure");
    }
    private static void pauseStop(Path root)throws Exception{
        AudioRecord.Scenario s=new AudioRecord.Scenario();s.reads=2;
        CountDownLatch gate=new CountDownLatch(1);AndroidDirectoryProtocol.entered=gate;AndroidDirectoryProtocol.proceed=new CountDownLatch(1);
        Run r=new Run(root,"pause-then-stop-draining",s,false);r.start();
        try{await(gate,"writer held before append");await(s.read,"two positive reads");r.engine.requestStop(CaptureEngine.Stop.PAUSE);r.engine.requestStop(CaptureEngine.Stop.STOP);r.engine.requestStop(CaptureEngine.Stop.PAUSE);}finally{AndroidDirectoryProtocol.proceed.countDown();}
        r.finish();r.saved(64,CaptureTimeline.Close.STOP);check(!r.paused,"later Pause cannot override STOP");check(r.result.failureMask==0,"drain failure-free");System.out.println("PASS Pause then Stop while writer is gated; queued PCM drains");
    }
    private static void cleanupFailure(Path root,boolean stop,boolean release)throws Exception{
        AudioRecord.Scenario s=new AudioRecord.Scenario();s.stopFailure=stop;s.releaseFailure=release;String name="cleanup-stop-"+stop+"-release-"+release;Run r=new Run(root,name,s,true);r.start();r.finish();r.saved(32,CaptureTimeline.Close.INTERRUPTION);
        check(r.result.has(CaptureEvidence.Failure.STOP_FAILED)==stop,"stop failure evidence");check(r.result.has(CaptureEvidence.Failure.RELEASE_FAILED)==release,"release failure evidence");check(r.result.release==(release?CaptureEvidence.Release.THREW:CaptureEvidence.Release.RETURNED),"release outcome");check(RecoveryGate.ownershipReleased(r.result)==!release,"ownership gate");check(!r.paused,"failed cleanup not clean pause");check(s.stopCalls==1 && s.releaseCalls==1,"one cleanup attempt each");System.out.println("PASS "+name);
    }
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]);Files.createDirectories(root);
        normal(root,"normal-stop");observationFailure(root);pauseStop(root);cleanupFailure(root,true,false);cleanupFailure(root,false,true);cleanupFailure(root,true,true);normal(root,"repeat-normal-stop");
        System.out.println("PASS CaptureEngine host fixture: 7 flows, "+checks+" assertions. Evidence: "+root);
    }
}
