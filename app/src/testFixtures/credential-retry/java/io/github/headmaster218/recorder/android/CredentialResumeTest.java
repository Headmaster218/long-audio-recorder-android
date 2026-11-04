package io.github.headmaster218.recorder.android;
import android.content.*;
import android.net.*;
import android.os.SystemClock;
import io.github.headmaster218.recorder.core.*;
import java.nio.file.*;
import java.util.*;
public final class CredentialResumeTest {
 static int checks;static void yes(boolean b,String reason){checks++;if(!b)throw new AssertionError(reason);}
 static FtpsQueue.Item row(FtpsQueue q,long id){for(FtpsQueue.Item x:q.items(false))if(x.key==id)return x;throw new AssertionError("row missing");}
 static FtpsTransfer.Profile destination(int port){return new FtpsTransfer.Profile("127.0.0.1",port,"fixture","/fixture");}
 static FtpsQueue.Item add(FtpsQueue q,CommittedSegments catalog,SegmentStore.Published p,String revision,String state,boolean prior,long retry,String cleanup)throws Exception{
  CommittedSegments.Entry entry=catalog.load(p.localObjectId);q.enqueue(entry,revision);FtpsQueue.Item item=q.items(false).get(q.items(false).size()-1);
  ContentValues v=new ContentValues();v.put("state",state);v.put("next_at",retry);v.put("cleanup_state",cleanup);
  if(prior){String attempt=FtpsTransfer.newAttemptName(entry.id,entry.metadata.wavSha256,revision);v.put("attempt",attempt);v.put("reconciliations",2);if(state.equals("VERIFIED_AT_TIME")){FtpsDelivery d=FtpsDelivery.encode(entry,revision,attempt);v.put("result_metadata_hash",d.metadataSha256);v.put("marker_hash",d.markerSha256);v.put("verified_at",1000000L);}}
  q.getWritableDatabase().update("queue",v,"id=?",new String[]{Long.toString(item.key)});return row(q,item.key);
 }
 @SuppressWarnings("unchecked") static void noPlaintext(RealCoordinatorHarness h)throws Exception{
  for(char[] probe:FtpsCredentials.returned)for(char c:probe)yes(c=='\0',"decrypted probe wiped");
  yes(((Map<String,char[]>)RealCoordinatorHarness.get(h.coordinator,"sessionPasswords")).isEmpty(),"retry does not cache plaintext in sessionPasswords");
 }
 public static void main(String[]args)throws Exception{
  Path root=Files.createTempDirectory("credential-resume-");Context context=new Context(root.toFile());Network network=new Network();context.connectivity.networks.put(network,new NetworkCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED));
  List<SegmentStore.Published> sources=RealCoordinatorHarness.seed(root.resolve("audio-spool"));CommittedSegments catalog=new CommittedSegments(root.resolve("audio-spool"));
  try(FtpsQueue q=new FtpsQueue(context)){
   String a=q.save(destination(2121),new byte[]{1}).revision;q.automatic(a,"fixture enabled");
   q.enqueue(catalog.load(sources.get(0).localObjectId),a);q.uploadNow();RealCoordinatorHarness h=new RealCoordinatorHarness(context,q);
   FtpsCoordinator.Job job=new FtpsCoordinator.Job();job.deadline=SystemClock.elapsedRealtime()+FtpsNetwork.ATTEMPT_MILLIS;h.runOne(job);h.assertAvailable();
   FtpsQueue.Item fresh=q.items(false).get(0);yes(fresh.state.equals("NEEDS_CREDENTIALS"),"actual runOne maps temporary locked key to NEEDS_CREDENTIALS");yes(network.connectionsMade()==0,"locked key opens no connection");
   long retry=System.currentTimeMillis()+3600000;
   FtpsQueue.Item old=add(q,catalog,sources.get(1),a,"NEEDS_CREDENTIALS",true,retry,"NONE");
   FtpsQueue.Item cleanup=add(q,catalog,sources.get(2),a,"VERIFIED_AT_TIME",true,retry,"WAIT_CREDENTIALS");
   String invalid=q.save(destination(2122),new byte[]{2}).revision;FtpsQueue.Item corrupt=add(q,catalog,sources.get(3),invalid,"NEEDS_CREDENTIALS",false,0,"NONE");
   String absent=q.save(destination(2123),null).revision;FtpsQueue.Item missing=add(q,catalog,sources.get(4),absent,"NEEDS_CREDENTIALS",false,0,"NONE");
   String unrelated=q.save(destination(2124),new byte[]{1}).revision;FtpsQueue.Item blocked=add(q,catalog,sources.get(5),unrelated,"BLOCKED",false,0,"NONE");
   String missingKey=q.save(destination(2125),new byte[]{3}).revision;FtpsQueue.Item unavailable=add(q,catalog,sources.get(6),missingKey,"NEEDS_CREDENTIALS",false,0,"NONE");
   q.configureBudget(true,10000000L,q.transferBudget().revision,false,false);yes(q.reserveBudget(1000,1000),"seed nonzero budget charge");TransferBudget.State budget=q.transferBudget();
   q.cleanupRevision(null);q.automaticCapacityWait(1234567);q.pause(true);
   h.coordinator.uploadNow();h.worker.drain();h.assertAvailable();
   yes(row(q,fresh.key).state.equals("NEEDS_CREDENTIALS")&&row(q,old.key).state.equals("NEEDS_CREDENTIALS"),"still locked retries remain blocked");yes(row(q,cleanup.key).cleanupState.equals("WAIT_CREDENTIALS"),"still locked cleanup remains waiting");yes(!q.paused(),"explicit existing Upload now action resumes paused queue");
   FtpsCredentials.locked=false;Map<String,Integer> before=new HashMap<String,Integer>(FtpsCredentials.calls);
   h.coordinator.uploadNow();h.worker.drain();h.assertAvailable();
   yes(row(q,fresh.key).state.equals("QUEUED"),"EXPLICIT UPLOAD NOW MUST RETRY REMEMBERED CREDENTIAL AFTER UNLOCK");
   yes(row(q,old.key).state.equals("RECONCILE")&&row(q,old.key).attempt.equals(old.attempt),"prior attempt resumes exact read-only reconciliation");
   yes(row(q,old.key).nextAt==retry&&row(q,old.key).reconciliations==2,"retry deadline and reconciliation count preserved");
   yes(row(q,cleanup.key).cleanupState.equals("DEFERRED")&&row(q,cleanup.key).attempt.equals(cleanup.attempt)&&row(q,cleanup.key).nextAt==retry,"cleanup credential wait resumes without replacing attempt or delay");
   yes(q.cleanupRevision()==null&&q.automaticRevision().equals(a)&&q.automaticCapacityAt()==1234567,"retry grants no cleanup/enrollment consent and preserves capacity marker");
   yes(row(q,corrupt.key).state.equals("NEEDS_CREDENTIALS")&&row(q,missing.key).state.equals("NEEDS_CREDENTIALS")&&row(q,unavailable.key).state.equals("NEEDS_CREDENTIALS"),"invalid/missing credential or key stays blocked");
   yes(row(q,blocked.key).state.equals("BLOCKED")&&!FtpsCredentials.calls.containsKey(unrelated)&&!FtpsCredentials.calls.containsKey(absent),"unrelated blocked pin and session-only profile are not unlocked");
   yes(FtpsCredentials.calls.get(a)-before.get(a)==1,"multiple rows and cleanup deduplicate one unlock per revision");
   TransferBudget.State after=q.transferBudget();yes(after.enabled==budget.enabled&&after.limitBytes==budget.limitBytes&&after.chargedBytes==budget.chargedBytes&&after.revision==budget.revision,"retry preserves budget and all charges");
   yes(network.connectionsMade()==0,"explicit retry itself performs no network transfer");noPlaintext(h);
   // Journal failure after successful decryption must propagate and probe must already be erased.
   q.state(row(q,fresh.key),"NEEDS_CREDENTIALS","fixture retry",0);
   q.getWritableDatabase().execSQL("CREATE TRIGGER fixture_fail_credential_resume BEFORE UPDATE OF state ON queue WHEN OLD.state='NEEDS_CREDENTIALS' AND NEW.state='QUEUED' BEGIN SELECT RAISE(ABORT,'fixture journal refusal'); END");
   h.coordinator.uploadNow();h.worker.drain();
   yes(row(q,fresh.key).state.equals("NEEDS_CREDENTIALS"),"journal refusal leaves row blocked");noPlaintext(h);
   yes(h.coordinator.snapshot().notice.contains("Local transfer action failed"),"journal write failure is surfaced separately from key failure");
   q.getWritableDatabase().execSQL("DROP TRIGGER fixture_fail_credential_resume");
   yes(q.profile(a).credential.length==1&&q.profile(a).credential[0]==1,"encrypted credential unchanged");
  }
  System.out.println("PASS: "+checks+" actual coordinator/SQLite credential retry assertions; synthetic Keystore boundary, no transfer");
 }
}
