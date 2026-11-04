package io.github.headmaster218.recorder.android;
import io.github.headmaster218.recorder.core.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Invokes actual compiled FtpsCoordinator.published(); no production method extraction. */
public final class PublishedNotificationTest {
 static int checks;
 static void yes(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static final class ManualExecutor extends AbstractExecutorService {
  final ArrayDeque<Runnable> pending=new ArrayDeque<Runnable>();
  public void execute(Runnable r){pending.add(r);}
  public void shutdown(){} public List<Runnable> shutdownNow(){return Collections.emptyList();}
  public boolean isShutdown(){return false;} public boolean isTerminated(){return false;}
  public boolean awaitTermination(long t,TimeUnit u){return false;}
  void drain(){while(!pending.isEmpty())pending.remove().run();}
 }
 static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
 static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static Object allocate(Class<?> type)throws Exception{Class<?> c=Class.forName("sun.misc.Unsafe");Field f=c.getDeclaredField("theUnsafe");f.setAccessible(true);return c.getMethod("allocateInstance",Class.class).invoke(f.get(null),type);}
 static final class Fixture {
  final FtpsCoordinator c; final FtpsQueue q=new FtpsQueue(); final ManualExecutor executor=new ManualExecutor();
  final Semaphore permits=new Semaphore(32); final AtomicBoolean overflow=new AtomicBoolean(); final FtpsCoordinator.EnrollmentConsent consent=new FtpsCoordinator.EnrollmentConsent();
  Fixture(Path spool)throws Exception{
   c=(FtpsCoordinator)allocate(FtpsCoordinator.class);
   set(c,"worker",executor);set(c,"queue",q);set(c,"catalog",new CommittedSegments(spool));set(c,"main",new android.os.Handler());
   set(c,"notifications",permits);set(c,"overflow",overflow);set(c,"enrollmentConsent",consent);
   for(String field:new String[]{"automaticDisableQueued","commandPending","pauseRequested","pauseWriteQueued","reschedulePending","cleanupRevoked","cleanupDisableQueued","catchupPosted"})set(c,field,new AtomicBoolean());
   set(c,"retirementBlocked",true);set(c,"retirementNotice","");
   yes(consent.enable(q.revision,consent.epoch()),"enable fixture consent");
  }
  void burst(SegmentStore.Published p){burst(Collections.nCopies(40,p));}
  void burst(List<SegmentStore.Published> sources){for(SegmentStore.Published p:sources)c.published(p);yes(executor.pending.size()>=32&&executor.pending.size()<=33,"bounded by 32 notifications plus one recovery runnable");yes(permits.availablePermits()==0,"32 permits held");yes(overflow.get(),"overflow observed");}
  void healthy()throws Exception{yes(!(Boolean)get(c,"unavailable"),"coordinator did not fail closed unexpectedly");yes(permits.availablePermits()==32,"all permits restored");}
 }
 static List<SegmentStore.Published> seed(Path root)throws Exception{
  List<SegmentStore.Published> result=new ArrayList<SegmentStore.Published>();
  try(DirectorySpool spool=new DirectorySpool(root,new CachePolicy(4*1024*1024,0),new DirectorySpool.NioProtocol(),(op,path)->{})){
   CaptureTimeline.Epoch epoch=new CaptureTimeline.Epoch("fixture","run",0,new PcmFormat(16000,1),"mic","mic",CaptureTimeline.Gap.START);
   PcmSegmentWriter writer=new PcmSegmentWriter(spool,epoch,8,0,result::add);writer.append(new byte[640],0,640);writer.stop();
  }return result;
 }
 static final class DelayedFailureSemaphore extends Semaphore {
  private static final long serialVersionUID=1L;
  final transient CountDownLatch failed=new CountDownLatch(1), releaseFailure=new CountDownLatch(1);
  DelayedFailureSemaphore(){super(32);}
  @Override public boolean tryAcquire(){boolean result=super.tryAcquire();if(!result){failed.countDown();try{if(!releaseFailure.await(5,TimeUnit.SECONDS))throw new AssertionError("failed-acquire latch timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}return result;}
 }
 static void lostWake(Path root,SegmentStore.Published first,SegmentStore.Published dropped)throws Exception{
  Fixture f=new Fixture(root);DelayedFailureSemaphore gate=new DelayedFailureSemaphore();set(f.c,"notifications",gate);
  for(int i=0;i<32;i++)f.c.published(first);
  AtomicReference<Throwable> error=new AtomicReference<Throwable>();
  Thread producer=new Thread(()->{try{f.c.published(dropped);}catch(Throwable t){error.set(t);}},"delayed-rejected-producer");
  producer.start();yes(gate.failed.await(5,TimeUnit.SECONDS),"rejected producer paused after semaphore failure");
  try{f.executor.drain();yes(f.executor.pending.isEmpty(),"all accepted notifications drained before rejection returns");yes(f.q.capacity==0,"no earlier capacity wake masks race");}
  finally{gate.releaseFailure.countDown();}
  producer.join(5000);yes(!producer.isAlive()&&error.get()==null,"rejected producer returned without failure");
  yes(f.executor.pending.size()==1,"late rejection posts its own recovery runnable");f.executor.drain();
  yes(f.q.revision!=null&&f.q.capacity>0,"late rejected publication has durable recovery wake");
  yes(gate.availablePermits()==32,"late rejection neither leaks nor invents permits");
 }
 public static void main(String[]args)throws Exception{
  Path root=Files.createTempDirectory("published-notification-fixture-");List<SegmentStore.Published> sources=seed(root);SegmentStore.Published p=sources.get(0);
  yes(sources.size()==40,"seed creates forty distinct actual finalized publications");
  Fixture normal=new Fixture(root);normal.burst(sources);int normalBacklog=normal.executor.pending.size();normal.executor.drain();normal.healthy();
  yes("fixture-revision".equals(normal.q.revision),"OVERFLOW MUST RETAIN AUTOMATIC CONSENT");
  yes(normalBacklog==33,"overflow has exactly one dedicated recovery runnable");
  yes(normal.q.capacity>0,"overflow persisted a capacity wake");yes(normal.q.capacityWrites==1,"burst coalesces into one persisted wake");
  yes(normal.q.rows.size()==32,"distinct burst admits first 32 source identities");yes(normal.q.enqueueCalls==32,"all accepted callbacks reached actual admission");
  Set<String> expectedMissing=new HashSet<String>();
  CommittedSegments retained=new CommittedSegments(root);
  for(int i=0;i<sources.size();i++){
   SegmentStore.Published source=sources.get(i);
   yes(Arrays.equals(retained.load(source.localObjectId).metadata.encode(),source.metadata.encode()),"finalized source remains intact after overflow");
   if(i>=32){expectedMissing.add(source.localObjectId);yes(!normal.q.rows.contains(source.localObjectId+"@"+normal.q.revision),"rejected source remains unqueued");}
  }
  FinalizedDiscovery.Page discovered=FinalizedDiscovery.page(root,id->normal.q.rows.contains(id+"@"+normal.q.revision),4096,64);
  yes(discovered.ids.size()==8&&new HashSet<String>(discovered.ids).equals(expectedMissing),"actual bounded discovery finds exactly eight missed publications");
  yes(!discovered.hasMore,"all eight missed publications fit one bounded page");
  Fixture duplicate=new Fixture(root);duplicate.burst(p);duplicate.executor.drain();duplicate.healthy();
  yes(duplicate.q.rows.size()==1&&duplicate.q.enqueueCalls==32,"separate repeated-callback case deduplicates 32 admissions into one row");
  Fixture disabled=new Fixture(root);FtpsCoordinator.EnrollmentConsent.Ticket stale=disabled.consent.ticket(p.localObjectId,disabled.q.revision);disabled.burst(p);disabled.c.automatic(false,null);
  yes(!disabled.consent.admit(stale),"Disable immediately cancels issued ticket");disabled.executor.drain();disabled.healthy();yes(disabled.q.revision==null,"Disable remains OFF");yes(disabled.q.rows.isEmpty(),"Disable blocks queued admissions");yes(disabled.q.capacity==0,"Disable clears recovery wake");
  Fixture paused=new Fixture(root);paused.burst(p);paused.c.pause();paused.executor.drain();paused.healthy();yes(paused.q.paused,"pause persisted");yes(paused.q.revision!=null,"Pause retains consent");yes(paused.q.capacity>0,"Pause retains overflow wake");
  Fixture full=new Fixture(root);full.q.full=true;full.burst(p);full.executor.drain();full.healthy();yes(full.q.enqueueCalls==0,"full queue receives no enqueue");yes(full.q.revision!=null&&full.q.capacity>0,"full queue retains consent and wake");
  lostWake(root,p,sources.get(1));
  System.out.println("PASS: "+checks+" actual compiled published() assertions; fixture journal/main-thread boundaries, not Android runtime");
 }
}
