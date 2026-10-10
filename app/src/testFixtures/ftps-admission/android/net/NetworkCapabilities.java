package android.net; import java.util.HashSet; import java.util.Set;
public final class NetworkCapabilities {
 public static final int TRANSPORT_WIFI=1,TRANSPORT_CELLULAR=0,TRANSPORT_VPN=4;
 public static final int NET_CAPABILITY_NOT_METERED=11,NET_CAPABILITY_INTERNET=12,NET_CAPABILITY_NOT_RESTRICTED=13,NET_CAPABILITY_TRUSTED=14,NET_CAPABILITY_NOT_VPN=15,NET_CAPABILITY_VALIDATED=16,NET_CAPABILITY_NOT_ROAMING=18,NET_CAPABILITY_NOT_SUSPENDED=21;
 final Set<Integer> transports=new HashSet<Integer>(),caps=new HashSet<Integer>();
 public NetworkCapabilities addTransportType(int t){transports.add(t);return this;}
 public NetworkCapabilities addCapability(int c){caps.add(c);return this;}
 public NetworkCapabilities removeCapability(int c){caps.remove(c);return this;}
 public boolean hasTransport(int t){return transports.contains(t);}
 public boolean hasCapability(int c){return caps.contains(c);}
}
