package io.github.headmaster218.recorder.android;
import android.content.Context;import io.github.headmaster218.recorder.core.*;import java.nio.file.*;import java.util.*;
/** Synthetic receipts only: no claim of an upload or permission to remove real recordings. */
public final class HistoryCapacityTest {
 private static int checks;private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 private static FtpsTransfer.Result receipt(FtpsQueue.Item item)throws Exception{java.lang.reflect.Constructor<FtpsTransfer.Result> c=FtpsTransfer.Result.class.getDeclaredConstructor(long.class,String.class,String.class,String.class);c.setAccessible(true);return c.newInstance(item.bytes,item.wavHash,FtpsQueue.digest(new byte[]{2}),FtpsQueue.digest(new byte[]{1}));}
 private static List<CommittedSegments.Entry> entries(Path spool)throws Exception{List<CommittedSegments.Entry> all=new ArrayList<>();CommittedSegments c=new CommittedSegments(spool);String after=null;while(true){CommittedSegments.Page p=c.page(after);all.addAll(p.entries);if(!p.hasMore)return all;after=p.cursor();}}
 private static Map<String,String> hashes(Path root)throws Exception{Map<String,String> result=new TreeMap<>();try(java.util.stream.Stream<Path> files=Files.walk(root)){for(Path p:(Iterable<Path>)files::iterator)if(Files.isRegularFile(p))result.put(root.relativize(p).toString(),FtpsQueue.digest(Files.readAllBytes(p)));}return result;}
 public static void main(String[] args)throws Exception{
  Path root=Paths.get(args[0]);Files.createDirectories(root);Path spool=root.resolve("audio-spool");
  try(DirectorySpool store=new DirectorySpool(spool,new CachePolicy(32L*1024*1024,0),new DirectorySpool.NioProtocol(),(op,p)->{})){
   CaptureTimeline.Epoch epoch=new CaptureTimeline.Epoch("history-fixture-capture","history-fixture-run",0,new PcmFormat(8000,1),"fixture","fixture",CaptureTimeline.Gap.START);
   PcmSegmentWriter writer=new PcmSegmentWriter(store,epoch,2,0,p->{});writer.append(new byte[501*4],0,501*4);writer.stop();
  }
  List<CommittedSegments.Entry> all=entries(spool);check(all.size()==501,"501 finalized originals");Map<String,String> before=hashes(spool);
  FtpsQueue q=new FtpsQueue(new Context(root.toFile()));FtpsQueue.Profile profile=q.save(new FtpsTransfer.Profile("fixture.invalid",21,"fixture","/history"),null);q.automatic(profile.revision,"ON: fixture consent");
  for(int i=0;i<500;i++)check(q.enqueue(all.get(i),profile.revision,true),"queued source "+i);
  for(FtpsQueue.Item item:q.items(false)){String attempt=FtpsTransfer.newAttemptName(item.source,item.wavHash,item.revision);q.begin(item,attempt);q.verified(item,receipt(item));if(item.key%2==0)q.cleanup(item,"RETAINED",null);}
  check(q.full(),"verified history still fills capacity");check(q.items(true).isEmpty(),"verified receipts are not runnable uploads");check(q.retirementCandidates().isEmpty(),"no REMOVED receipts eligible for retirement");check(q.retiring().isEmpty(),"no retirement in progress");
  int none=0,retained=0;for(FtpsQueue.Item item:q.items(false)){check(item.state.equals("VERIFIED_AT_TIME"),"synthetic verified state");if(item.cleanupState.equals("NONE"))none++;if(item.cleanupState.equals("RETAINED"))retained++;}
  check(none==250&&retained==250,"mixed 250 NONE / 250 RETAINED");
  boolean refused=false;try{q.enqueue(all.get(500),profile.revision,true);}catch(java.io.IOException expected){refused=true;}check(refused,"501st source rejected safely");q.automaticCapacityWait(12345);
  // Exactly the persistence operations used by cleanupEnabled(true, revision).
  if(Boolean.getBoolean("history.actual.coordinator")){
   Class<?> harness=Class.forName("io.github.headmaster218.recorder.android.RealCoordinatorHarness");Object h=harness.getConstructor(Context.class,FtpsQueue.class).newInstance(new Context(root.toFile()),q);Object coordinator=harness.getField("coordinator").get(h);
   java.lang.reflect.Method enable=coordinator.getClass().getDeclaredMethod("cleanupEnabled",boolean.class,String.class);enable.setAccessible(true);enable.invoke(coordinator,true,profile.revision);
   Object worker=harness.getField("worker").get(h);worker.getClass().getMethod("drain").invoke(worker);harness.getMethod("assertAvailable").invoke(h);
   Class<?> job=Class.forName("io.github.headmaster218.recorder.android.FtpsCoordinator$Job");java.lang.reflect.Constructor<?> constructor=job.getDeclaredConstructor();constructor.setAccessible(true);
   java.lang.reflect.Method recheck=coordinator.getClass().getDeclaredMethod("recheckDeferred",job);recheck.setAccessible(true);check(Boolean.FALSE.equals(recheck.invoke(coordinator,constructor.newInstance())),"actual coordinator skips NONE/RETAINED receipts");
   System.out.println("PASS actual cleanupEnabled and recheckDeferred executed without entering network path");
  }else{q.revokeCleanup();q.cleanupRevision(profile.revision);}
  q.close();q=new FtpsQueue(new Context(root.toFile()));
  check(profile.revision.equals(q.cleanupRevision()),"future-only cleanup consent persisted");check(profile.revision.equals(q.automaticRevision()),"auto pin retained");check(q.automaticCapacityAt()==12345,"capacity wait persisted");check(q.full(),"future cleanup toggle does not free a slot");check(q.retirementCandidates().isEmpty(),"future cleanup toggle does not invent retired audio");
  int eligible=0;for(FtpsQueue.Item item:q.items(false))if(item.cleanupState.equals("DEFERRED")||item.cleanupState.equals("RECHECKING")||item.cleanupState.equals("WAIT_SOURCE"))eligible++;
  check(eligible==0,"no existing receipt selected by deferred readback states");refused=false;try{q.enqueue(all.get(500),profile.revision,true);}catch(java.io.IOException expected){refused=true;}check(refused,"501st still rejected after future cleanup enable");
  check(before.equals(hashes(spool)),"all local original bytes and metadata unchanged");check(entries(spool).size()==501,"all originals remain readable");q.close();
  System.out.println("PASS history capacity: "+checks+" assertions; 500 synthetic verified receipts, 501 intact real local recordings; future-only cleanup cannot unblock capacity. Evidence: "+root);
 }
}
