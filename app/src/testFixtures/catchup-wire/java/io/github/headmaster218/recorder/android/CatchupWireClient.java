package io.github.headmaster218.recorder.android;
import android.content.Context;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import io.github.headmaster218.recorder.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Actual queue/coordinator/FTPS classes; platform/JVM transport and host SQLite adapters. */
public final class CatchupWireClient {
 static int assertions;
 static void yes(boolean value,String reason){assertions++;if(!value)throw new AssertionError(reason);}
 static Context context(Path root){Context c=new Context(root.toFile());c.connectivity.networks.put(new Network(),new NetworkCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED));return c;}
 static int verified(FtpsQueue q){int count=0;for(FtpsQueue.Item i:q.items(false))if(i.state.equals("VERIFIED_AT_TIME"))count++;return count;}
 static void receipt(FtpsQueue q,Path path)throws Exception{List<String> lines=new ArrayList<>();for(FtpsQueue.Item i:q.items(false))lines.add(i.source+"\t"+i.state+"\t"+i.attempt+"\t"+i.wavHash+"\t"+i.resultMetadataHash+"\t"+i.markerHash+"\t"+i.verifiedAt);Files.write(path,lines,StandardCharsets.UTF_8);}
 @SuppressWarnings("unchecked") static void password(RealCoordinatorHarness h)throws Exception{((Map<String,char[]>)RealCoordinatorHarness.get(h.coordinator,"sessionPasswords")).put(h.queue.current().revision,"synthetic".toCharArray());}
 static void transfer(RealCoordinatorHarness h,Context context,int until)throws Exception{
  h.queue.uploadNow();password(h);int before=verified(h.queue);
  while(before<until){FtpsCoordinator.Job job=new FtpsCoordinator.Job();job.deadline=SystemClock.elapsedRealtime()+FtpsNetwork.ATTEMPT_MILLIS;h.runOne(job);h.assertAvailable();int after=verified(h.queue);yes(after==before+1,"one real transfer must verify; states="+states(h.queue));before=after;}
  for(Network network:context.connectivity.networks.keySet())yes(network.allSocketsClosed(),"all owned raw sockets closed before process exit");
  yes(context.receiversReleased()&&context.connectivity.callbacksReleased(),"network callbacks released");
 }
 static String states(FtpsQueue q){StringBuilder s=new StringBuilder();for(FtpsQueue.Item i:q.items(false))if(!i.state.equals("VERIFIED_AT_TIME"))s.append(i.state).append(':').append(i.detail).append(';');return s.toString();}
 public static void main(String[]args)throws Exception{
  String phase=args[0];Path root=Paths.get(args[1]).toAbsolutePath();Context context=context(root);FtpsQueue q=new FtpsQueue(context);q.getWritableDatabase();q.recover();
  if(phase.equals("setup")){
   FtpsQueue.Profile profile=q.save(new FtpsTransfer.Profile("127.0.0.1",Integer.parseInt(args[2]),"synthetic","/fixture"),null);q.automatic(profile.revision,"fixture explicit consent");
   List<SegmentStore.Published> publications=RealCoordinatorHarness.seed(root.resolve("audio-spool"));yes(publications.size()==40,"forty actual segments");RealCoordinatorHarness h=new RealCoordinatorHarness(context,q);h.publishAll(publications);yes(h.worker.size()==33,"32 callbacks plus one recovery");h.worker.drain();h.assertAvailable();yes(q.items(false).size()==32,"eight absent before catch-up");yes(q.automaticCapacityAt()>System.currentTimeMillis(),"future durable capacity wake");receipt(q,root.resolve("setup.tsv"));Files.write(root.resolve("due.txt"),Long.toString(q.automaticCapacityAt()).getBytes(StandardCharsets.UTF_8));q.close();
  } else if(phase.equals("first")){
   yes(q.items(false).size()==32,"32 callback rows survived JVM restart");yes(q.automaticCapacityAt()>0,"wake survived JVM restart");
   if(args.length>2&&args[2].equals("force-due"))q.automaticCapacityWait(System.currentTimeMillis()-1);
   yes(q.automaticCapacityAt()<=System.currentTimeMillis(),"capacity wake is due");RealCoordinatorHarness h=new RealCoordinatorHarness(context,q);
   try(AutoCloseable owned=h.openSpool()){yes(owned!=null,"owned spool lease");h.invoke("resumeCapacity");yes(h.page()!=null&&h.page().size()==8,"exact eight selected by production catch-up");while(!h.page().isEmpty())h.invoke("catchupOne");}
   yes(q.items(false).size()==40,"all eight admitted by production confirmation");transfer(h,context,36);receipt(q,root.resolve("first.tsv"));yes(verified(q)==36,"36 verified before hard exit");System.out.println("PHASE first assertions="+assertions);System.out.flush();Runtime.getRuntime().halt(23);
  } else if(phase.equals("finish")){
   yes(q.items(false).size()==40&&verified(q)==36,"36 receipts and four pending survived hard JVM exit");Map<String,String> prior=new HashMap<>();for(String line:Files.readAllLines(root.resolve("first.tsv"),StandardCharsets.UTF_8)){String[] f=line.split("\t");if(f[1].equals("VERIFIED_AT_TIME"))prior.put(f[0],f[2]);}
   RealCoordinatorHarness h=new RealCoordinatorHarness(context,q);try(AutoCloseable owned=h.openSpool()){yes(owned!=null,"owned spool lease");h.invoke("prepareCatchup");yes(h.page().isEmpty(),"restart discovers no duplicate sources");}
   transfer(h,context,40);for(FtpsQueue.Item i:q.items(false)){yes(i.verifiedAt>0&&i.markerHash!=null&&i.resultMetadataHash!=null,"durable verification receipt");if(prior.containsKey(i.source))yes(prior.get(i.source).equals(i.attempt),"previous attempt unchanged");}
   CommittedSegments catalog=new CommittedSegments(root.resolve("audio-spool"));for(FtpsQueue.Item i:q.items(false))catalog.verify(catalog.load(i.source),()->false);
   receipt(q,root.resolve("final.tsv"));q.close();
  } else throw new IllegalArgumentException("Unknown phase");
  System.out.println("PHASE "+phase+" assertions="+assertions);
 }
}
