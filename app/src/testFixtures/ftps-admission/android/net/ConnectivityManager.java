package android.net; import java.util.LinkedHashMap; import java.util.Map;
public class ConnectivityManager {
 public final Map<Network,NetworkCapabilities> networks=new LinkedHashMap<Network,NetworkCapabilities>(); public Network defaultNetwork;
 public Network[] getAllNetworks(){return networks.keySet().toArray(new Network[networks.size()]);}
 public NetworkCapabilities getNetworkCapabilities(Network n){return networks.get(n);}
}
