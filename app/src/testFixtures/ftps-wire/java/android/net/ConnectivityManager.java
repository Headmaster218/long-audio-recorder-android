package android.net;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
/** One stable, explicitly configured Wi-Fi route. Registration does not request a real route. */
public final class ConnectivityManager {
    public final Map<Network,NetworkCapabilities> networks = new LinkedHashMap<Network,NetworkCapabilities>();
    private final Set<NetworkCallback> callbacks = new HashSet<NetworkCallback>();
    public int registrations, unregistrations;
    public Network[] getAllNetworks() { return networks.keySet().toArray(new Network[networks.size()]); }
    public NetworkCapabilities getNetworkCapabilities(Network network) { return networks.get(network); }
    public synchronized void registerNetworkCallback(NetworkRequest request, NetworkCallback callback) {
        if (request == null || !callbacks.add(callback)) throw new AssertionError("Invalid callback registration");
        registrations++;
        // Deliberately no callback is synthesized. FtpsNetwork's real watchdog inspects this stable model.
    }
    public synchronized void unregisterNetworkCallback(NetworkCallback callback) {
        if (!callbacks.remove(callback)) throw new AssertionError("Unknown callback");
        unregistrations++;
    }
    public synchronized boolean callbacksReleased() { return callbacks.isEmpty(); }
    public static class NetworkCallback {
        public void onAvailable(Network network) { }
        public void onLost(Network network) { }
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { }
        public void onBlockedStatusChanged(Network network, boolean blocked) { }
    }
}
