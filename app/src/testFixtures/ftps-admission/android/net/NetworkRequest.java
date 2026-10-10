package android.net;
public final class NetworkRequest {
 final NetworkCapabilities requested; NetworkRequest(NetworkCapabilities c){requested=c;}
 public boolean matches(NetworkCapabilities actual){return actual!=null&&actual.caps.containsAll(requested.caps)&&(requested.transports.isEmpty()||!java.util.Collections.disjoint(actual.transports,requested.transports));}
 public static final class Builder {
  final NetworkCapabilities c=new NetworkCapabilities().addCapability(13).addCapability(14).addCapability(15);
  public Builder addCapability(int x){c.addCapability(x);return this;} public Builder removeCapability(int x){c.removeCapability(x);return this;}
  public Builder addTransportType(int x){c.addTransportType(x);return this;} public NetworkRequest build(){return new NetworkRequest(c);}
 }
}
