package io.github.headmaster218.recorder.android;
import io.github.headmaster218.recorder.core.*;
import android.content.Context;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Host harness: actual coordinator and queue classes; Android platform adapter required. */
public final class RealCoordinatorHarness {
 public final FtpsCoordinator coordinator;
 public final FtpsQueue queue;
 public final ManualExecutor worker=new ManualExecutor();
 public final Semaphore permits=new Semaphore(32);
 public final Path spoolRoot;
 public static final class ManualExecutor extends AbstractExecutorService {
  final ArrayDeque<Runnable> pending=new ArrayDeque<Runnable>();
  public void execute(Runnable r){pending.add(r);} public void shutdown(){}
  public List<Runnable> shutdownNow(){return Collections.emptyList();} public boolean isShutdown(){return false;}public boolean isTerminated(){return false;}public boolean awaitTermination(long t,TimeUnit unit){return false;}
  public int size(){return pending.size();}public void drain(){while(!pending.isEmpty())pending.remove().run();}
 }
 public static void set(Object o,String field,Object value)throws Exception{Field f=o.getClass().getDeclaredField(field);f.setAccessible(true);f.set(o,value);}
 public static Object get(Object o,String field)throws Exception{Field f=o.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(o);}
 public static Object allocate(Class<?> type)throws Exception{Class<?> c=Class.forName("sun.misc.Unsafe");Field f=c.getDeclaredField("theUnsafe");f.setAccessible(true);return c.getMethod("allocateInstance",Class.class).invoke(f.get(null),type);}
 public RealCoordinatorHarness(Context context,FtpsQueue queue)throws Exception{
  this.queue=queue;spoolRoot=context.getFilesDir().toPath().resolve("audio-spool");coordinator=(FtpsCoordinator)allocate(FtpsCoordinator.class);
  set(coordinator,"context",context);set(coordinator,"worker",worker);set(coordinator,"queue",queue);set(coordinator,"catalog",new CommittedSegments(spoolRoot));set(coordinator,"main",new android.os.Handler());set(coordinator,"notifications",permits);set(coordinator,"overflow",new AtomicBoolean());
  FtpsCoordinator.EnrollmentConsent consent=new FtpsCoordinator.EnrollmentConsent();if(queue.automaticRevision()!=null&&!consent.enable(queue.automaticRevision(),consent.epoch()))throw new AssertionError("fixture consent initialization failed");set(coordinator,"enrollmentConsent",consent);
  for(String field:new String[]{"automaticDisableQueued","commandPending","pauseRequested","pauseWriteQueued","reschedulePending","cleanupRevoked","cleanupDisableQueued","catchupPosted"})set(coordinator,field,new AtomicBoolean());
  set(coordinator,"snapshot",new FtpsCoordinator.Snapshot(null,null,new ArrayList<FtpsQueue.Item>(),new HashMap<String,String>(),"host fixture",false,false));
  set(coordinator,"sessionPasswords",new HashMap<String,char[]>());set(coordinator,"retirementBlocked",true);set(coordinator,"retirementNotice","");
 }
 public void invoke(String name)throws Exception{Method m=FtpsCoordinator.class.getDeclaredMethod(name);m.setAccessible(true);invokeMethod(m);}
 private void invokeMethod(Method m,Object... args)throws Exception{try{m.invoke(coordinator,args);}catch(InvocationTargetException e){Throwable t=e.getCause();if(t instanceof Exception)throw (Exception)t;throw new AssertionError(t);}}
 public void runOne(FtpsCoordinator.Job job)throws Exception{Method m=FtpsCoordinator.class.getDeclaredMethod("runOne",FtpsCoordinator.Job.class);m.setAccessible(true);invokeMethod(m,job);}
 @SuppressWarnings("unchecked") public ArrayDeque<String> page()throws Exception{return (ArrayDeque<String>)get(coordinator,"catchup");}
 public void publishAll(List<SegmentStore.Published> publications){for(SegmentStore.Published p:publications)coordinator.published(p);}
 public void assertAvailable()throws Exception{if((Boolean)get(coordinator,"unavailable"))throw new AssertionError("coordinator unexpectedly unavailable");}
 /** Real capture-owner spool branch. Does not execute Android idle-spool initializer. */
 public AutoCloseable openSpool()throws Exception{
  final DirectorySpool spool=new DirectorySpool(spoolRoot,new CachePolicy(4*1024*1024,0),new DirectorySpool.NioProtocol(),(op,path)->{});
  Object token=SpoolAdmission.beginCapture();SpoolAdmission.opened(token,spool);
  return ()->{spool.close();SpoolAdmission.captureClosed(spool);};
 }
 public static List<SegmentStore.Published> seed(Path root)throws Exception{
  List<SegmentStore.Published> result=new ArrayList<SegmentStore.Published>();
  try(DirectorySpool spool=new DirectorySpool(root,new CachePolicy(4*1024*1024,0),new DirectorySpool.NioProtocol(),(op,path)->{})){
   CaptureTimeline.Epoch epoch=new CaptureTimeline.Epoch("fixture","run",0,new PcmFormat(16000,1),"mic","mic",CaptureTimeline.Gap.START);
   PcmSegmentWriter writer=new PcmSegmentWriter(spool,epoch,8,0,result::add);writer.append(new byte[640],0,640);writer.stop();
  }return result;
 }
}
