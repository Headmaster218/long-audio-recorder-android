package io.github.headmaster218.recorder.android;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.headmaster218.recorder.core.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/** Real production coordinator/admission/core with host Android adapters, not Android runtime coverage. */
public final class SealedRecoveryCoordinatorTest {
    private static int checks, cases;
    private static Path base;
    private static final long BUDGET=4000000;
    private interface Action { void run()throws Exception; }
    private static void yes(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void denied(Action action)throws Exception {
        checks++;try{action.run();}catch(IOException expected){return;}throw new AssertionError("expected admission denial");
    }
    private static final class Prefs implements SharedPreferences {
        final Path file; boolean value; String cursor=""; int commits,failAt;
        Prefs(Path file)throws IOException{this.file=file;value=Files.exists(file);Path saved=file.resolveSibling("cursor");if(Files.exists(saved))cursor=new String(Files.readAllBytes(saved),java.nio.charset.StandardCharsets.UTF_8);}
        @Override public synchronized boolean getBoolean(String key,boolean fallback){return value;}
        @Override public synchronized String getString(String key,String fallback){return cursor;}
        @Override public Editor edit(){return new Editor(){
            private boolean next;private String nextCursor=cursor;
            @Override public Editor putBoolean(String key,boolean value){next=value;return this;}
            @Override public Editor putString(String key,String value){nextCursor=value;return this;}
            @Override public Editor remove(String key){next=false;return this;}
            @Override public boolean commit(){synchronized(Prefs.this){
                // Android commit can update memory even when persistent storage fails.
                value=next;cursor=nextCursor;if(++commits==failAt)return false;
                try{if(next)Files.write(file,new byte[]{1});else Files.deleteIfExists(file);Files.write(file.resolveSibling("cursor"),nextCursor.getBytes(java.nio.charset.StandardCharsets.UTF_8));return true;}
                catch(IOException e){throw new IllegalStateException(e);}
            }}
        };}
    }
    private static final class Host extends Context {
        final Path files;final Prefs prefs;
        Host(Path files)throws IOException{this.files=Files.createDirectories(files);prefs=new Prefs(files.resolve("recovery-marker"));}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
        @Override public File getFilesDir(){return files.toFile();}
        Path root(){return files.resolve("audio-spool");}
    }
    private static Host host()throws IOException{return new Host(base.resolve("case-"+cases++));}
    private static final class Worker implements AutoCloseable {
        final SealedRecoveryCoordinator coordinator;
        Worker(Host host)throws Exception {this(host,false);}
        Worker(Host host,boolean singleton)throws Exception {
            if(singleton){coordinator=SealedRecoveryCoordinator.get(host);return;}
            Constructor<SealedRecoveryCoordinator> ctor=SealedRecoveryCoordinator.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);coordinator=ctor.newInstance(host);
        }
        void run()throws Exception{coordinator.start(BUDGET);await();}
        void await()throws Exception{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(coordinator.busy()&&System.nanoTime()<end)Thread.sleep(2);
            yes(!coordinator.busy(),"worker completes");
        }
        @Override public void close(){
            try{
            Field field=SealedRecoveryCoordinator.class.getDeclaredField("worker");field.setAccessible(true);
            ExecutorService service=(ExecutorService)field.get(coordinator);service.shutdownNow();
            yes(service.awaitTermination(10,TimeUnit.SECONDS),"host worker terminated");
            Field instance=SealedRecoveryCoordinator.class.getDeclaredField("instance");instance.setAccessible(true);
            if(instance.get(null)==coordinator)instance.set(null,null);
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            catch(ReflectiveOperationException e){throw new AssertionError(e);}
        }
    }
    private static DirectorySpool spool(Path root)throws IOException {
        return new DirectorySpool(root,new CachePolicy(BUDGET,0),new DirectorySpool.NioProtocol(),(op,path)->{});
    }
    private static CaptureTimeline.Epoch epoch(String id){return new CaptureTimeline.Epoch(id,"run",0,PcmFormat.DEFAULT,"synthetic","synthetic",CaptureTimeline.Gap.START);}
    private static String sealed(Path root,String id)throws Exception {
        final String[] result={null};
        try(DirectorySpool store=spool(root)){
            PcmSegmentWriter writer=PcmSegmentWriter.terminalAware(store,epoch(id),4,0,p->result[0]=p.localObjectId);
            writer.append(new byte[]{7,2,5,4,3,6,1,8},0,8);writer.stop();
        }
        // A real finalized production object moved back to its staged name models a sealed pre-rename crash.
        String part=result[0].replace(".ready",".part");Files.move(root.resolve(result[0]),root.resolve(part));return part;
    }
    private static Map<String,byte[]> snapshot(Path directory)throws IOException {
        Map<String,byte[]> data=new TreeMap<String,byte[]>();
        try(Stream<Path> paths=Files.walk(directory)){
            Iterator<Path> iterator=paths.iterator();while(iterator.hasNext()){
                Path p=iterator.next();if(Files.isRegularFile(p))data.put(directory.relativize(p).toString(),Files.readAllBytes(p));
            }
        }return data;
    }
    private static void same(Map<String,byte[]> expected,Path actual)throws IOException {
        Map<String,byte[]> current=snapshot(actual);yes(expected.keySet().equals(current.keySet()),"same names");
        for(String key:expected.keySet())yes(Arrays.equals(expected.get(key),current.get(key)),"unchanged bytes: "+key);
    }
    private static long count(Path root,String suffix)throws IOException {
        try(Stream<Path> paths=Files.list(root)){return paths.filter(p->p.toString().endsWith(suffix)).count();}
    }
    private static void explicitAndAdmission()throws Exception {
        Host h=host();String part=sealed(h.root(),"explicit");
        try(Worker w=new Worker(h)){
            yes(!w.coordinator.busy(),"construction idle");yes(w.coordinator.status().contains("No recovery requested"),"explicit-only prompt");
            yes(h.prefs.commits==0&&Files.exists(h.root().resolve(part)),"constructor does not inspect/recover");
            Object opening=SpoolAdmission.beginCapture();
            try{w.run();yes(w.coordinator.status().contains("busy"),"opening capture rejected");}finally{SpoolAdmission.abandonOpening(opening);}
            Object token=SpoolAdmission.beginCapture();
            try(DirectorySpool active=spool(h.root())){
                SpoolAdmission.opened(token,active);
                try{w.run();yes(w.coordinator.status().contains("Stop recording before recovery"),"active owner rejected");}
                finally{SpoolAdmission.captureClosed(active);}
            }
            ExportCoordinator.busy=true;
            try{w.run();yes(w.coordinator.status().contains("busy"),"export rejected");}finally{ExportCoordinator.busy=false;}
            try(SpoolAdmission.Reservation held=SpoolAdmission.beginMaintenance()){
                yes(held.owner==null,"idle reservation");w.run();yes(w.coordinator.status().contains("busy"),"competing maintenance rejected");
            }
            yes(h.prefs.commits==0,"denied admissions never write marker");
            yes(Files.exists(h.root().resolve(part)),"denied admissions preserve staged object");
            w.run();yes(w.coordinator.status().startsWith("Recovered 1 sealed recordings."),"idle recovery succeeds");
            yes(!SpoolAdmission.maintenanceActive(),"reservation released");
        }
    }
    private static void singleFlight()throws Exception {
        Host h=host();sealed(h.root(),"single-flight");AndroidDirectoryProtocol.reset();
        AndroidDirectoryProtocol.entered=new CountDownLatch(1);AndroidDirectoryProtocol.release=new CountDownLatch(1);
        try(Worker w=new Worker(h,true)){
            w.coordinator.start(BUDGET);
            try{
                yes(AndroidDirectoryProtocol.entered.await(5,TimeUnit.SECONDS),"recovery at gate");
                yes(w.coordinator.busy()&&SpoolAdmission.maintenanceActive(),"maintenance owns idle window");
                yes(SealedRecoveryCoordinator.get(new Host(h.files))==w.coordinator,"recreated context retains in-flight coordinator");
                denied(SpoolAdmission::beginCapture);denied(SpoolAdmission::beginMaintenance);
                for(int i=0;i<50;i++)w.coordinator.start(BUDGET);
                yes(h.prefs.commits==1,"one start marker");
            }finally{AndroidDirectoryProtocol.release.countDown();}
            w.await();yes(h.prefs.commits==2&&AndroidDirectoryProtocol.publications==1,"only one worker pass");
        }finally{AndroidDirectoryProtocol.reset();}
    }
    private static void markers()throws Exception {
        for(int failure:new int[]{1,2}){
            Host h=host();String part=sealed(h.root(),"marker-"+failure);h.prefs.failAt=failure;
            try(Worker w=new Worker(h)){
                w.run();yes(w.coordinator.status().contains(failure==1?"interruption marker":"completion marker"),"commit failure reported");
                yes(count(h.root(),".ready")== (failure==1?0:1),"start commit failure blocks mutation; clear failure retains publication");
                yes(Files.exists(h.root().resolve(part))==(failure==1),"staged name after commit failure");
                yes(!SpoolAdmission.maintenanceActive(),"marker failure releases reservation");
            }
            Host restarted=new Host(h.files);
            try(Worker next=new Worker(restarted)){
                yes(next.coordinator.status().contains(failure==1?"No recovery requested":"Previous recovery was interrupted"),"persisted state survives host reconstruction");
                yes(restarted.prefs.commits==0,"interruption does not auto-retry");next.run();
                yes(!Files.exists(restarted.prefs.file),"successful explicit pass clears durable marker");
            }
        }
    }
    private static void mixedAndBound()throws Exception {
        Host h=host();List<String> parts=new ArrayList<String>();Map<String,Map<String,byte[]>> originals=new HashMap<String,Map<String,byte[]>>();
        for(int i=0;i<31;i++){String p=sealed(h.root(),"batch-"+i);parts.add(p);originals.put(p,snapshot(h.root().resolve(p)));}
        String ready=sealed(h.root(),"already-ready");Files.move(h.root().resolve(ready),h.root().resolve(ready.replace(".part",".ready")));
        String corrupt=sealed(h.root(),"corrupt");Files.write(h.root().resolve(corrupt).resolve("manifest.bin"),new byte[]{1,2,3});
        Map<String,byte[]> bad=snapshot(h.root().resolve(corrupt));
        try(DirectorySpool store=spool(h.root())){
            SegmentStore.Staging raw=store.open(new SegmentMetadata.Intent(epoch("raw"),0,0,4));raw.append(new byte[]{9,8,7,6},0,4);raw.preserve();
        }
        String raw;
        try(Stream<Path> paths=Files.list(h.root())){raw=paths.filter(p->p.toString().endsWith(".part")&&!parts.contains(p.getFileName().toString())&&!p.getFileName().toString().equals(corrupt)).findFirst().get().getFileName().toString();}
        Map<String,byte[]> unfinished=snapshot(h.root().resolve(raw));
        List<String> ordered=new ArrayList<String>(parts);ordered.add(raw);ordered.add(corrupt);Collections.sort(ordered);
        int kept=ordered.subList(0,32).contains(raw)?1:0;if(ordered.subList(0,32).contains(corrupt))kept++;
        try(Worker w=new Worker(h)){
            w.run();yes(w.coordinator.status().contains("Recovered "+(32-kept)+" sealed recordings. Preserved "+kept+" uncertain objects. 1 staged objects remain"),"bounded mixed report");
            yes(count(h.root(),".ready")==33-kept,"first page plus existing ready");
            same(bad,h.root().resolve(corrupt));same(unfinished,h.root().resolve(raw));
            for(String p:parts){Path location=Files.exists(h.root().resolve(p))?h.root().resolve(p):h.root().resolve(p.replace(".part",".ready"));same(originals.get(p),location);}
            w.run();yes(w.coordinator.status().contains("Recovered "+(kept-1)+" sealed recordings. Preserved "+(2-kept)+" uncertain objects. Sweep finished"),"second explicit batch: "+w.coordinator.status());
            w.run();yes(w.coordinator.status().startsWith("Recovered 0 sealed recordings."),"completed pass idempotent");
            same(bad,h.root().resolve(corrupt));same(unfinished,h.root().resolve(raw));
        }
    }
    private static void uncertainAbort()throws Exception {
        Host h=host();for(int i=0;i<3;i++)sealed(h.root(),"uncertain-"+i);
        AndroidDirectoryProtocol.reset();AndroidDirectoryProtocol.failAfterPublish=true;
        try(Worker w=new Worker(h)){
            w.run();yes(w.coordinator.status().contains("stopped after 0 confirmed recoveries"),"uncertain publication not counted");
            yes(w.coordinator.status().contains("outcomes may be uncertain"),"uncertainty surfaced");
            yes(AndroidDirectoryProtocol.publications==1&&count(h.root(),".ready")==1&&count(h.root(),".part")==2,"abort on first uncertain rename; no retry");
            yes(Files.exists(h.prefs.file),"uncertain completion marker retained");
        }finally{AndroidDirectoryProtocol.reset();}
        try(Worker w=new Worker(new Host(h.files))){
            yes(w.coordinator.status().contains("interrupted"),"uncertainty persists restart");w.run();
            yes(w.coordinator.status().startsWith("Recovered 2 sealed recordings."),"fresh explicit pass preserves already-ready outcome");
        }
    }
    private static void noCandidates()throws Exception {
        Host h=host();
        try(Worker w=new Worker(h)){
            w.run();yes(w.coordinator.status().contains("Recovered 0 sealed recordings. Preserved 0 uncertain objects. Sweep finished"),"empty spool result");
            yes(!Files.exists(h.prefs.file),"empty pass clears marker");
        }
        String part=sealed(h.root(),"ready-only");Path ready=h.root().resolve(part.replace(".part",".ready"));
        Files.move(h.root().resolve(part),ready);Map<String,byte[]> bytes=snapshot(ready);
        AndroidDirectoryProtocol.reset();
        try(Worker w=new Worker(h)){
            w.run();yes(w.coordinator.status().contains("Recovered 0 sealed recordings. Preserved 0 uncertain objects. Sweep finished"),"already-ready only result");
            yes(AndroidDirectoryProtocol.publications==0,"already-ready never republished");same(bytes,ready);
        }
    }
    private static void persistedCursorAndNoStarvation()throws Exception {
        Host h=host();List<String> names=new ArrayList<String>();
        for(int i=0;i<33;i++)names.add(sealed(h.root(),"starvation-"+i));Collections.sort(names);
        for(int i=0;i<32;i++)Files.delete(h.root().resolve(names.get(i)).resolve("manifest.bin"));
        Map<String,Map<String,byte[]>> raw=new HashMap<String,Map<String,byte[]>>();
        for(int i=0;i<32;i++)raw.put(names.get(i),snapshot(h.root().resolve(names.get(i))));
        h.prefs.failAt=2;
        try(Worker w=new Worker(h)){
            w.run();yes(w.coordinator.status().contains("completion marker"),"cursor completion failure reported");
            yes(h.prefs.cursor.equals(names.get(31)),"failed commit changes modeled preference memory");
            yes(new Host(h.files).prefs.cursor.isEmpty(),"failed commit leaves durable prior cursor");
            h.prefs.failAt=3;w.run();yes(w.coordinator.status().contains("interruption marker"),"retry start marker failure blocks pass");
            yes(h.prefs.cursor.isEmpty(),"start marker restores authoritative old cursor, not failed editor state");
            h.prefs.failAt=0;w.run();
            yes(w.coordinator.status().contains("Recovered 0 sealed recordings. Preserved 32 uncertain objects. 1 staged objects remain"),"failed completion did not advance in-memory authoritative cursor");
            yes(new Host(h.files).prefs.cursor.equals(names.get(31)),"full page commits cursor over raw tails");
        }
        Host restarted=new Host(h.files);
        try(Worker w=new Worker(restarted)){
            yes(Files.exists(h.root().resolve(names.get(32))),"recreation does not auto-start next page");w.run();
            yes(w.coordinator.status().contains("Recovered 1 sealed recordings. Preserved 0 uncertain objects. Sweep finished"),"persisted cursor reaches sealed object behind 32 raw tails");
            yes(new Host(h.files).prefs.cursor.isEmpty(),"sweep end resets durable cursor");
            w.run();yes(w.coordinator.status().contains("Preserved 32 uncertain objects. Sweep finished"),"next sweep revisits retained tails");
        }
        for(String name:raw.keySet())same(raw.get(name),h.root().resolve(name));
    }
    private static void cancellationBoundaries()throws Exception {
        for(boolean publishing:new boolean[]{false,true}){
            Host h=host();for(int i=0;i<2;i++)sealed(h.root(),"cancel-"+publishing+"-"+i);
            AndroidDirectoryProtocol.reset();AndroidDirectoryProtocol.gatePublication=publishing;
            AndroidDirectoryProtocol.entered=new CountDownLatch(1);AndroidDirectoryProtocol.release=new CountDownLatch(1);
            try(Worker w=new Worker(h)){
                w.coordinator.start(BUDGET);
                try{
                    yes(AndroidDirectoryProtocol.entered.await(5,TimeUnit.SECONDS),"cancel boundary reached");
                    w.coordinator.cancel();yes(w.coordinator.busy(),"cancel retains worker until safe boundary");
                    yes(SpoolAdmission.maintenanceActive(),"cancel retains storage reservation");denied(SpoolAdmission::beginCapture);
                }finally{AndroidDirectoryProtocol.release.countDown();}
                w.await();
                yes(w.coordinator.status().contains("stopped after "+(publishing?1:0)+" confirmed recoveries (cancellation requested)"),"cancellation counts only safely finished publication");
                yes(count(h.root(),".ready")== (publishing?1:0),"pre-publish cancellation blocks rename; in-progress rename finishes");
                yes(count(h.root(),".part")== (publishing?1:2),"other staged objects retained");
                yes(new Host(h.files).prefs.cursor.isEmpty()&&Files.exists(h.prefs.file),"cancellation retains original cursor and interrupted marker");
                yes(!SpoolAdmission.maintenanceActive(),"reservation released after cancellation safe point");
                AndroidDirectoryProtocol.reset();w.run();
                yes(w.coordinator.status().startsWith("Recovered "+(publishing?1:2)+" sealed recordings."),"next explicit start clears cancellation");
            }finally{AndroidDirectoryProtocol.reset();}
        }
        Host h=host();String part=sealed(h.root(),"hash-cancel");Map<String,byte[]> original=snapshot(h.root().resolve(part));
        try(DirectorySpool store=spool(h.root())){
            final int[] polls={0};
            // inspectStaged polls once at entry, once before opening WAV, then in the PCM read loop.
            denied(()->store.inspectStaged(part,()->++polls[0]>=3));
            yes(polls[0]==3,"cancel observed inside staged WAV validation loop");same(original,h.root().resolve(part));
        }
        try(DirectorySpool store=spool(h.root())){
            final int[] polls={0};denied(()->store.recoverFinalizedPart(part,()->++polls[0]>=3));
            yes(polls[0]==3,"cancel observed inside recovery WAV validation loop");same(original,h.root().resolve(part));
        }
    }
    private static void readyPayloadSkipped()throws Exception {
        Host h=host();String part=sealed(h.root(),"skip-ready");Path ready=h.root().resolve(part.replace(".part",".ready"));
        Files.move(h.root().resolve(part),ready);
        // Deliberately invalid payload: namespace paging must not inspect or hash ready objects.
        Files.write(ready.resolve("audio.wav"),new byte[]{1});Map<String,byte[]> before=snapshot(ready);
        try(DirectorySpool store=spool(h.root())){
            SealedRecovery.Result result=SealedRecovery.runPage(store,null,()->false,n->{throw new AssertionError("ready promoted");});
            yes(result.recovered==0&&result.preserved==0&&result.remaining==0&&result.nextCursor==null,"ready payload excluded from page entirely");
        }
        same(before,ready);
    }
    private static void corePageEdges()throws Exception {
        Host h=host();List<String> names=new ArrayList<String>();
        for(int i=0;i<3;i++)names.add(sealed(h.root(),"page-edge-"+i));Collections.sort(names);
        try(DirectorySpool store=spool(h.root())){
            final int[] callbacks={0},hashPolls={0};
            VerifiedExport.Cancellation duringHash=()->{
                if(hashPolls[0]>=2)return true;
                for(StackTraceElement frame:Thread.currentThread().getStackTrace()){
                    if(frame.getClassName().equals(DirectorySpool.class.getName())&&frame.getMethodName().equals("hashWav"))return ++hashPolls[0]>=2;
                }
                return false;
            };
            denied(()->SealedRecovery.runPage(store,null,duringHash,n->callbacks[0]++));
            yes(hashPolls[0]==2&&callbacks[0]==0,"page propagates mid-hash cancellation rather than classifying corrupt/preserved");
            yes(count(h.root(),".part")==3&&count(h.root(),".ready")==0,"page validation cancellation leaves all names staged");
            denied(()->SealedRecovery.runPage(store,null,()->true,n->callbacks[0]++));
            yes(callbacks[0]==0,"pre-cancelled page reports no progress");
            DirectorySpool.StagedPage page=store.stagedPage(null,2,()->false);
            yes(page.objectIds.equals(names.subList(0,2))&&page.remaining==1&&page.nextCursor.equals(names.get(1)),"lexicographically ordered bounded page");
            SealedRecovery.Result result=SealedRecovery.runPage(store,names.get(0),()->false,n->callbacks[0]++);
            yes(result.recovered==2&&result.remaining==0&&result.nextCursor==null&&callbacks[0]==2,"exclusive cursor and completed sweep reset");
        }
        try(DirectorySpool store=spool(h.root())){
            SealedRecovery.Result result=SealedRecovery.runPage(store,null,()->false,n->{});
            yes(result.recovered==1,"reset sweep reaches earlier staged object");
        }
    }
    private static void coreProgress()throws Exception {
        Host h=host();for(int i=0;i<33;i++)sealed(h.root(),"core-"+i);
        List<Integer> progress=new ArrayList<Integer>();
        try(DirectorySpool store=spool(h.root())){
            SealedRecovery.Result r=SealedRecovery.run(store,progress::add);
            yes(r.recovered==32&&r.preserved==0&&r.remaining==1,"core result bound");
        }
        yes(progress.size()==32,"one progress callback per confirmed recovery");
        for(int i=0;i<32;i++)yes(progress.get(i)==i+1,"monotonic progress");
        try(DirectorySpool store=spool(h.root())){SealedRecovery.Result r=SealedRecovery.run(store,n->{});yes(r.recovered==1&&r.remaining==0,"core remaining pass");}
    }
    public static void main(String[] args)throws Exception {
        base=Files.createTempDirectory(Paths.get("app/build/sealed-recovery-coordinator-tests"),"run-");
        explicitAndAdmission();singleFlight();markers();mixedAndBound();uncertainAbort();noCandidates();persistedCursorAndNoStarvation();cancellationBoundaries();readyPayloadSkipped();corePageEdges();coreProgress();
        System.out.println("PASS sealed recovery coordinator/core: "+checks+" assertions across "+cases+" host fixtures. Production coordinator, admission and filesystem core; Android API, retirement and export adapters are stubs, NOT Android runtime/device durability proof.");
    }
}
