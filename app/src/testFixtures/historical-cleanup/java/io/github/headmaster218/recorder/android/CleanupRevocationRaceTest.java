package io.github.headmaster218.recorder.android;
import android.content.Context;
import io.github.headmaster218.recorder.core.FtpsTransfer;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Executes real SDK-compiled coordinator and queue with deterministic worker ordering. */
public final class CleanupRevocationRaceTest {
 static int checks;
 static void yes(boolean condition,String reason){checks++;if(!condition)throw new AssertionError(reason);}
 public static void main(String[]args)throws Exception{
  Path root=Files.createTempDirectory("cleanup-revocation-race-");Context context=new Context(root.toFile());
  try(FtpsQueue q=new FtpsQueue(context)){
   String revision=q.save(new FtpsTransfer.Profile("localhost",2121,"fixture","/fixture"),null).revision;
   RealCoordinatorHarness h=new RealCoordinatorHarness(context,q);
   h.coordinator.cleanupEnabled(true,revision);
   yes(h.worker.size()==1,"enable is pending on worker");
   final AtomicBoolean observedRevoked=new AtomicBoolean();final AtomicBoolean probeRan=new AtomicBoolean();
   h.worker.execute(()->{try{observedRevoked.set(((AtomicBoolean)RealCoordinatorHarness.get(h.coordinator,"cleanupRevoked")).get());probeRan.set(true);}catch(Exception e){throw new AssertionError(e);}});
   h.coordinator.disableCleanup();
   yes(((AtomicBoolean)RealCoordinatorHarness.get(h.coordinator,"cleanupRevoked")).get(),"Disable revokes immediately before worker runs");
   yes(h.worker.size()==3,"enable, worker probe, durable disable ordered deterministically");
   h.worker.drain();h.assertAvailable();
   yes(probeRan.get(),"probe ran between earlier enable and durable disable");
   yes(observedRevoked.get(),"QUEUED ENABLE MUST NOT CLEAR A LATER IMMEDIATE DISABLE");
   yes(q.cleanupRevision()==null,"durable cleanup consent remains OFF");
   h.coordinator.cleanupEnabled(true,revision);h.worker.drain();
   yes(revision.equals(q.cleanupRevision()),"new explicit enable after completed disable succeeds");
   yes(!((AtomicBoolean)RealCoordinatorHarness.get(h.coordinator,"cleanupRevoked")).get(),"fresh enable can clear prior revocation");
   h.coordinator.disableCleanup();h.coordinator.disableCleanup();
   yes(h.worker.size()==1,"repeated disables coalesce durable worker writes");h.worker.drain();yes(q.cleanupRevision()==null,"repeated disables remain OFF");
   h.coordinator.cleanupEnabled(true,revision);h.worker.drain();
   long before=((Long)RealCoordinatorHarness.get(h.coordinator,"cleanupRevocationEpoch")).longValue();
   h.coordinator.cleanupEnabled(false,revision);
   yes(((AtomicBoolean)RealCoordinatorHarness.get(h.coordinator,"cleanupRevoked")).get(),"false enable API immediately revokes");
   yes(((Long)RealCoordinatorHarness.get(h.coordinator,"cleanupRevocationEpoch")).longValue()==before+1,"false enable API uses same revocation epoch");
   h.worker.drain();yes(q.cleanupRevision()==null,"false enable API persists OFF");
   RealCoordinatorHarness.set(h.coordinator,"cleanupRevocationEpoch",Long.MAX_VALUE);
   h.coordinator.disableCleanup();h.worker.drain();h.coordinator.cleanupEnabled(true,revision);h.worker.drain();
   yes(((Long)RealCoordinatorHarness.get(h.coordinator,"cleanupRevocationEpoch")).longValue()==Long.MAX_VALUE,"revocation epoch saturates without wrapping");
   yes(((AtomicBoolean)RealCoordinatorHarness.get(h.coordinator,"cleanupRevoked")).get()&&q.cleanupRevision()==null,"exhausted revocation epoch fails closed");
  }
  System.out.println("PASS: "+checks+" actual compiled cleanup-revocation ordering assertions");
 }
}
