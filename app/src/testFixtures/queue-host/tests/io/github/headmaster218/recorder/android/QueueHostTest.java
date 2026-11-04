package io.github.headmaster218.recorder.android;
import android.content.Context;import android.database.Cursor;import android.database.sqlite.SQLiteDatabase;
import io.github.headmaster218.recorder.core.*;import java.nio.file.*;import java.util.*;
public final class QueueHostTest {
 private static int assertions;private static void check(boolean b,String why){assertions++;if(!b)throw new AssertionError(why);}
 private static FtpsQueue queue(Path root){return new FtpsQueue(new Context(root.toFile()));}
 private static FtpsTransfer.Profile profile(){return new FtpsTransfer.Profile("fixture.invalid",21,"fixture-user","/fixture");}
 private static void seed(Path root)throws Exception{
  Files.createDirectories(root);Path spool=root.resolve("audio-spool");
  try(DirectorySpool store=new DirectorySpool(spool,new CachePolicy(32L*1024*1024,0),new DirectorySpool.NioProtocol(),(op,p)->{})){
   CaptureTimeline.Epoch epoch=new CaptureTimeline.Epoch("fixture-capture","fixture-run",0,new PcmFormat(8000,1),"fixture","fixture",CaptureTimeline.Gap.START);
   PcmSegmentWriter writer=new PcmSegmentWriter(store,epoch,2,0,p->{});writer.append(new byte[501*4],0,501*4);writer.stop();
  }
  FtpsQueue q=queue(root);FtpsQueue.Profile p=q.save(profile(),new byte[]{0,1,2,(byte)255});q.automatic(p.revision,"ON: fixture consent");
  CommittedSegments catalog=new CommittedSegments(spool);String cursor=null;List<CommittedSegments.Entry> entries=new ArrayList<>();
  do {CommittedSegments.Page page=catalog.page(cursor);entries.addAll(page.entries);if(!page.hasMore)break;cursor=page.cursor();}while(true);
  check(entries.size()==501,"real source count");for(int i=0;i<500;i++)check(q.enqueue(entries.get(i),p.revision,true),"enqueue "+i);
  check(q.full(),"queue full");check(!q.enqueue(entries.get(0),p.revision,true),"idempotent duplicate even full");boolean refused=false;try{q.enqueue(entries.get(500),p.revision,true);}catch(java.io.IOException expected){refused=true;}check(refused,"capacity enforced");
  q.automaticCapacityWait(123456789L);q.pause(true);FtpsQueue.Item first=q.items(false).get(0);String attempt=FtpsTransfer.newAttemptName(first.source,first.wavHash,p.revision);q.begin(first,attempt);
  Files.write(root.resolve("attempt.txt"),attempt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  check(q.items(false).get(0).state.equals("RUNNING"),"durable attempt running");
  SQLiteDatabase db=q.getWritableDatabase();db.beginTransaction();db.execSQL("UPDATE settings SET paused=0 WHERE id=1");
  System.out.println("PASS seed "+assertions+" assertions; halting JVM with committed attempt and uncommitted pause change");System.out.flush();Runtime.getRuntime().halt(0);
 }
 private static FtpsTransfer.Result syntheticResult(FtpsQueue.Item item)throws Exception{
  // Receipt persistence test only. Parent TLS fixture must supply a real FtpsTransfer.run result.
  java.lang.reflect.Constructor<FtpsTransfer.Result> c=FtpsTransfer.Result.class.getDeclaredConstructor(long.class,String.class,String.class,String.class);c.setAccessible(true);return c.newInstance(item.bytes,item.wavHash,item.metadataHash,FtpsQueue.digest(new byte[]{1}));
 }
 private static void reopen(Path root)throws Exception{
  FtpsQueue q=queue(root);check(q.getReadableDatabase().getVersion()==6,"exact current schema version");
  check(q.current().revision.equals(FtpsQueue.revision(profile())),"profile revision persisted");check(Arrays.equals(q.current().credential,new byte[]{0,1,2,(byte)255}),"opaque BLOB persisted");
  check(q.automaticRevision().equals(q.current().revision),"auto destination retained");check(q.automaticCapacityAt()==123456789L,"capacity timestamp retained");check(q.paused(),"uncommitted pause update rolled back on process exit");
  check(q.items(false).size()==500 && q.full(),"all capacity rows survived");FtpsQueue.Item first=q.items(false).get(0);String attempt=new String(Files.readAllBytes(root.resolve("attempt.txt")),java.nio.charset.StandardCharsets.UTF_8);check(first.state.equals("RUNNING")&&attempt.equals(first.attempt),"attempt survives process boundary");
  q.recover();first=q.items(false).get(0);check(first.state.equals("RECONCILE")&&attempt.equals(first.attempt),"recover retains exact attempt");q.begin(first,attempt);FtpsQueue.Item active=q.items(false).get(0);check(active.reconciliations==1,"reconciliation counted");
  boolean stale=false;try{q.begin(first,attempt);}catch(java.io.IOException expected){stale=true;}check(stale,"stale attempt CAS rejected");check(q.items(false).get(0).reconciliations==1,"failed CAS transaction rolled back");
  q.verified(active,syntheticResult(active));q.close();q=queue(root);FtpsQueue.Item verified=q.items(false).get(0);check(verified.state.equals("VERIFIED_AT_TIME")&&verified.verifiedAt>0&&attempt.equals(verified.attempt),"receipt persisted across fresh bridge connection");check(verified.markerHash!=null&&verified.resultMetadataHash!=null,"receipt hashes persisted");check(q.paused()&&q.automaticRevision()!=null&&q.automaticCapacityAt()==123456789L,"receipt does not clear paused/capacity consent");
  q.automaticCapacityScanned();q.close();q=queue(root);check(q.automaticCapacityAt()==0&&q.automaticRevision()!=null,"scanned clears only wait");
  try(Cursor c=q.getReadableDatabase().rawQuery("PRAGMA foreign_keys",null)){check(c.moveToFirst()&&c.getInt(0)==1,"foreign keys enabled on reopen");}
  boolean fk=false;try{q.automatic("missing-profile","bad");}catch(android.database.sqlite.SQLiteException expected){fk=true;}check(fk,"foreign key constraints enforced");
  check(q.automaticRevision().equals(q.current().revision),"failed FK mutation preserves consent");q.close();System.out.println("PASS reopen "+assertions+" assertions; actual SQLite persisted queue/receipt and crash rollback");
 }
 public static void main(String[] args)throws Exception{if(args[0].equals("seed"))seed(Paths.get(args[1]));else if(args[0].equals("reopen"))reopen(Paths.get(args[1]));else throw new IllegalArgumentException("mode");}
}
