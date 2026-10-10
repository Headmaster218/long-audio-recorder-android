package io.github.headmaster218.recorder.android;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import java.io.IOException;
/** Runs actual production JobInfo factory and FtpsNetwork.allowed/route methods against explicit API fixtures. */
public final class FtpsAdmissionTest {
 static int checks,secretReads;
 static void yes(boolean value){checks++;if(!value)throw new AssertionError("check "+checks+" API "+Build.VERSION.SDK_INT);}
 static NetworkCapabilities wifi(){return new NetworkCapabilities().addTransportType(1).addCapability(11).addCapability(13).addCapability(14).addCapability(15).addCapability(18).addCapability(21);}
 static NetworkCapabilities cell(){return new NetworkCapabilities().addTransportType(0).addCapability(12).addCapability(13).addCapability(14).addCapability(15).addCapability(16).addCapability(18).addCapability(21);}
 static final Network WIFI=new Network(10),CELL=new Network(20);
 static Network tryAdmission(Context c,long bytes,boolean now)throws IOException {
  // Same preflight/credential ordering is enforced against the production coordinator by wiring checks.
  Network selected=FtpsNetwork.allowed(c,bytes,0,now);
  secretReads++; return selected; // Spy only, no secret store or socket operation.
 }
 static void blocked(Context c,long bytes,boolean now)throws Exception {
  int before=secretReads;boolean failed=false;try{tryAdmission(c,bytes,now);}catch(IOException e){failed=true;}
  yes(failed);yes(secretReads==before);
 }
 public static void main(String[] args)throws Exception {
  ComponentName service=new ComponentName("synthetic","Job");
  for(int api=29;api<=37;api++) {
   Build.VERSION.SDK_INT=api;
   Context c=new Context();c.connectivity.networks.put(WIFI,wifi());
   JobInfo fixed=FtpsJobSchedule.build(6101,service,1000);
   JobInfo old=new JobInfo.Builder(6101,service).setRequiresCharging(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).build();
   JobInfo custom=new JobInfo.Builder(6101,service).setRequiresCharging(true).setRequiredNetwork(new NetworkRequest.Builder().addTransportType(1).addCapability(11).build()).build();
   yes(fixed.charging&&fixed.persisted&&fixed.latency==1000&&fixed.backoff==60000);
   yes(fixed.getRequiredNetwork()==null);yes(!old.schedulable(true,wifi()));
   yes(custom.schedulable(true,wifi())); // Custom request solves INTERNET alone, not a non-default route.
   for(boolean now:new boolean[]{false,true}) {
    long bytes=now?1:9600000;
    yes(fixed.schedulable(true,null));yes(tryAdmission(c,bytes,now).equals(WIFI));
    c.connectivity.defaultNetwork=CELL;c.connectivity.networks.put(CELL,cell());
    yes(!old.schedulable(true,cell()));yes(!custom.schedulable(true,cell()));
    yes(fixed.schedulable(true,cell()));yes(tryAdmission(c,bytes,now).equals(WIFI));
    c.connectivity.networks.get(WIFI).removeCapability(11);blocked(c,bytes,now);
    c.connectivity.networks.put(WIFI,wifi().removeCapability(18));blocked(c,bytes,now);
    c.connectivity.networks.put(WIFI,wifi().removeCapability(21));blocked(c,bytes,now);
    c.connectivity.networks.put(WIFI,wifi().addTransportType(0));blocked(c,bytes,now);
    c.connectivity.networks.put(WIFI,wifi());c.connectivity.networks.put(new Network(30),wifi());blocked(c,bytes,now);c.connectivity.networks.remove(new Network(30));
    c.connectivity.networks.put(new Network(40),new NetworkCapabilities().addTransportType(4));blocked(c,bytes,now);c.connectivity.networks.remove(new Network(40));
    c.powered=false;yes(!fixed.schedulable(false,cell()));blocked(c,bytes,now);c.powered=true;
    c.permission=false;if(api>=37)blocked(c,bytes,now);else yes(tryAdmission(c,bytes,now).equals(WIFI));c.permission=true;
    c.connectivity.networks.remove(WIFI);blocked(c,bytes,now);c.connectivity.networks.clear();blocked(c,bytes,now);
    c.connectivity.networks.put(WIFI,wifi());c.connectivity.defaultNetwork=null;
   }
   blocked(c,1,false); // Threshold not reached, no automatic transfer.
   yes(FtpsJobSchedule.build(6101,service,300000).latency==300000);
  }
  System.out.println("PASS admission: "+checks+" assertions; actual production factory/route methods, AOSP-semantics API fixtures 29-37; no Android runtime, credentials or network I/O");
 }
}
