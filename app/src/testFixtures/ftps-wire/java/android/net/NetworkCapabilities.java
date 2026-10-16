package android.net;
import java.util.HashSet;
import java.util.Set;
public final class NetworkCapabilities {
    public static final int TRANSPORT_CELLULAR = 0, TRANSPORT_WIFI = 1, TRANSPORT_BLUETOOTH = 2,
        TRANSPORT_ETHERNET = 3, TRANSPORT_VPN = 4, TRANSPORT_WIFI_AWARE = 5, TRANSPORT_LOWPAN = 6,
        TRANSPORT_USB = 8, TRANSPORT_THREAD = 9, TRANSPORT_SATELLITE = 10;
    public static final int NET_CAPABILITY_NOT_METERED = 11, NET_CAPABILITY_NOT_VPN = 15,
        NET_CAPABILITY_NOT_ROAMING = 18, NET_CAPABILITY_NOT_SUSPENDED = 21;
    private final Set<Integer> transports = new HashSet<Integer>(), capabilities = new HashSet<Integer>();
    public NetworkCapabilities addTransportType(int value) { transports.add(Integer.valueOf(value)); return this; }
    public NetworkCapabilities addCapability(int value) { capabilities.add(Integer.valueOf(value)); return this; }
    public boolean hasTransport(int value) { return transports.contains(Integer.valueOf(value)); }
    public boolean hasCapability(int value) { return capabilities.contains(Integer.valueOf(value)); }
}
