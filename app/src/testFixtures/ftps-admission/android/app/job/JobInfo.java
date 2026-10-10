package android.app.job;
import android.content.ComponentName; import android.net.NetworkCapabilities; import android.net.NetworkRequest;
/** Deliberately models actual AOSP shorthand: UNMETERED adds INTERNET and VALIDATED. */
public final class JobInfo {
 public static final int NETWORK_TYPE_NONE=0,NETWORK_TYPE_UNMETERED=2,BACKOFF_POLICY_EXPONENTIAL=1;
 public boolean charging,persisted; public long latency,backoff; public NetworkRequest network;
 public boolean schedulable(boolean powered,NetworkCapabilities uidDefault){return (!charging||powered)&&(network==null||network.matches(uidDefault));}
 public NetworkRequest getRequiredNetwork(){return network;}
 public static final class Builder {
  final JobInfo j=new JobInfo(); public Builder(int id,ComponentName c) { }
  public Builder setRequiresCharging(boolean v){j.charging=v;return this;}
  public Builder setRequiredNetworkType(int t){
   if(t==NETWORK_TYPE_NONE)j.network=null;
   else { NetworkRequest.Builder b=new NetworkRequest.Builder().addCapability(12).addCapability(16).removeCapability(15);if(t==NETWORK_TYPE_UNMETERED)b.addCapability(11);j.network=b.build(); }
   return this;
  }
  public Builder setRequiredNetwork(NetworkRequest request){j.network=request;return this;}
  public Builder setPersisted(boolean v){j.persisted=v;return this;}
  public Builder setMinimumLatency(long v){j.latency=v;return this;}
  public Builder setBackoffCriteria(long v,int type){j.backoff=v;return this;}
  public JobInfo build(){return j;}
 }
}
