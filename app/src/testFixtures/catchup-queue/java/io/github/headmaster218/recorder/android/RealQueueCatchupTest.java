package io.github.headmaster218.recorder.android;
import android.content.Context;
import io.github.headmaster218.recorder.core.*;
import java.nio.file.*;
import java.util.*;
@SuppressWarnings("try")
public final class RealQueueCatchupTest {
 static int checks;
 static void yes(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void scenario(boolean disable)throws Exception{
  Path root=Files.createTempDirectory("actual-queue-catchup-");Context context=new Context(root.toFile());
  List<SegmentStore.Published> sources=RealCoordinatorHarness.seed(root.resolve("audio-spool"));String revision;
  try(FtpsQueue queue=new FtpsQueue(context)){
   revision=queue.save(new FtpsTransfer.Profile("localhost",2121,"fixture","/fixture"),null).revision;queue.automatic(revision,"fixture ON");
   RealCoordinatorHarness h=new RealCoordinatorHarness(context,queue);h.publishAll(sources);yes(h.worker.size()==33,"actual callback burst bounded to32+1");h.worker.drain();h.assertAvailable();
   yes(queue.items(false).size()==32,"actual SQLite journal contains32 initial admissions");yes(revision.equals(queue.automaticRevision()),"actual SQLite retains consent");yes(queue.automaticCapacityAt()>0,"actual SQLite persists capacity wake");yes(h.permits.availablePermits()==32,"notification permits recovered");
   if(disable){
    try(AutoCloseable owner=h.openSpool()){
     h.invoke("prepareCatchup");yes(h.page().size()==8,"actual coordinator discovers8 missing rows");h.coordinator.automatic(false,null);
     boolean deferred=false;try{h.invoke("catchupOne");}catch(java.io.IOException e){deferred=e.getClass().getSimpleName().equals("CatchupDeferred");}
     yes(deferred,"immediate Disable cancels real catchup admission");h.worker.drain();yes(queue.items(false).size()==32,"Disable retains existing32 and adds zero");yes(queue.automaticRevision()==null&&queue.automaticCapacityAt()==0,"Disable persists OFF and clears wake");
    }
   }
  }
  // Closing/reopening launches a fresh SQLite bridge process on the same real DB.
  try(FtpsQueue reopened=new FtpsQueue(context)){
   reopened.recover();yes(reopened.items(false).size()==32,"actual SQLite close/reopen preserves32 source rows");
   RealCoordinatorHarness h=new RealCoordinatorHarness(context,reopened);
   if(disable){yes(reopened.automaticRevision()==null,"actual SQLite reopen preserves Disable");h.invoke("resumeCapacity");yes(h.page()==null,"disabled reconstructed coordinator creates no discovery page");}
   else{
    yes(revision.equals(reopened.automaticRevision())&&reopened.automaticCapacityAt()>0,"actual SQLite reopen preserves consent and timestamp");h.invoke("resumeCapacity");yes(h.page()==null,"future persisted timestamp is respected");
    reopened.automaticCapacityWait(System.currentTimeMillis()-1); // Explicit host clock advancement boundary.
    try(AutoCloseable owner=h.openSpool()){
     h.invoke("resumeCapacity");yes(h.page().size()==8,"due actual capacity wake prepares8 missing sources");
     Set<String> expected=new HashSet<String>();for(int i=32;i<40;i++)expected.add(sources.get(i).localObjectId);yes(new HashSet<String>(h.page()).equals(expected),"exact missed identities are recovered");
     for(int i=0;i<8;i++)h.invoke("catchupOne");yes(h.page().isEmpty(),"real catchup page is exhausted");yes(reopened.items(false).size()==40,"actual catchupOne adds8 rows to production SQLite journal");
     for(SegmentStore.Published source:sources)yes(reopened.contains(source.localObjectId,revision),"exact source plus destination revision retained");
    }
   }
   CommittedSegments catalog=new CommittedSegments(root.resolve("audio-spool"));for(SegmentStore.Published source:sources)yes(Arrays.equals(catalog.load(source.localObjectId).metadata.encode(),source.metadata.encode()),"all original sources remain intact");
  }
 }
 public static void main(String[]args)throws Exception{scenario(false);scenario(true);System.out.println("PASS: "+checks+" actual coordinator + production FtpsQueue/host SQLite assertions; no Android runtime or transfer claim");}
}
