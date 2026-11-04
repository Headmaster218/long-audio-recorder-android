package io.github.headmaster218.recorder.android;
import android.content.*;
import io.github.headmaster218.recorder.core.*;
import java.nio.file.*;
import java.util.*;
/** Actual controller/journal tests with explicit synthetic prior-verification receipts. No network or deletion. */
public final class HistoricalCleanupControllerTest {
 static int checks;static void yes(boolean b,String reason){checks++;if(!b)throw new AssertionError(reason);}
 static void scenario(int mode)throws Exception{
  Path root=Files.createTempDirectory("historical-controller-");Context context=new Context(root.toFile());List<SegmentStore.Published> all=RealCoordinatorHarness.seed(root.resolve("audio-spool"));
  try(FtpsQueue q=new FtpsQueue(context)){
   FtpsTransfer.Profile destination=new FtpsTransfer.Profile("localhost",2121,"fixture","/fixture");String revision=q.save(destination,null).revision;
   CommittedSegments catalog=new CommittedSegments(root.resolve("audio-spool"));
   for(int i=0;i<8;i++){
    CommittedSegments.Entry entry=catalog.load(all.get(i).localObjectId);q.enqueue(entry,revision);
    String attempt=FtpsTransfer.newAttemptName(entry.id,entry.metadata.wavSha256,revision);FtpsDelivery delivery=FtpsDelivery.encode(entry,revision,attempt);
    // Model a prior completed transfer without claiming this fixture performs TLS verification.
    ContentValues receipt=new ContentValues();receipt.put("state","VERIFIED_AT_TIME");receipt.put("attempt",attempt);receipt.put("result_metadata_hash",delivery.metadataSha256);receipt.put("marker_hash",delivery.markerSha256);receipt.put("verified_at",1000000L);
    yes(q.getWritableDatabase().update("queue",receipt,"source=? AND revision=?",new String[]{entry.id,revision})==1,"seed synthetic prior receipt");
   }
   RealCoordinatorHarness h=new RealCoordinatorHarness(context,q);h.coordinator.cleanupEnabled(true,revision);h.worker.drain();
   List<FtpsQueue.Item> selected=new ArrayList<FtpsQueue.Item>(q.items(false));Map<Long,String> attempts=new HashMap<Long,String>();for(FtpsQueue.Item item:selected){yes(FtpsQueue.historicalCleanupEligible(item,revision),"synthetic shown receipt eligible");attempts.put(item.key,item.attempt);}
   long token=h.coordinator.cleanupConfirmationEpoch();yes(token>=0,"dialog captures enabled confirmation epoch");
   if(mode==4){h.coordinator.disableCleanup();h.worker.drain();h.coordinator.cleanupEnabled(true,revision);h.worker.drain();yes(h.coordinator.cleanupConfirmationEpoch()!=token,"disable/reenable invalidates dialog token");}
   if(mode==5)RealCoordinatorHarness.set(h.coordinator,"activeJob",new FtpsCoordinator.Job());
   if(mode==6){h.coordinator.disableCleanup();h.worker.drain();token=h.coordinator.cleanupConfirmationEpoch();yes(token<0,"disabled dialog token invalid");}
   if(mode==7)q.cleanupRevision(null);
   boolean accepted=h.coordinator.requestHistoricalCleanup(revision,selected,token);
   yes(accepted==(mode!=5),"busy request refused, otherwise queued for validation");
   if(mode==0)selected.clear(); // Submission must freeze the exact displayed list.
   if(mode==1)h.coordinator.disableCleanup();
   if(mode==2)h.coordinator.pause();
   if(mode==3)q.save(new FtpsTransfer.Profile("localhost",2122,"fixture","/other"),null);
   h.worker.drain();h.assertAvailable();
   int deferred=0;for(FtpsQueue.Item item:q.items(false)){
    if(item.cleanupState.equals("DEFERRED"))deferred++;
    yes(attempts.get(item.key).equals(item.attempt)&&item.verifiedAt==1000000L,"request preserves exact prior attempt and verification timestamp");
    yes(item.cleanupBinding==null,"request creates no local deletion binding");
    yes(Arrays.equals(catalog.load(item.source).metadata.encode(),all.get((int)item.key-1).metadata.encode()),"request preserves local source");
   }
   yes(q.items(false).size()==8,"request retains all detailed rows");yes(deferred==(mode==0?8:0),"only fresh exact authorization marks bounded rows DEFERRED; mode="+mode);
   if(mode==1||mode==6)yes(q.cleanupRevision()==null,"Disable remains authoritative");
   if(mode==2)yes(q.paused(),"Pause remains authoritative");
  }
 }
 public static void main(String[]args)throws Exception{for(int i=0;i<8;i++)scenario(i);System.out.println("PASS: "+checks+" actual historical-cleanup controller/journal assertions; synthetic prior receipts, no TLS or deletion");}
}
