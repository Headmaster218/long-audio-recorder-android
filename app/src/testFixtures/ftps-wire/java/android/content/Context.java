package android.content;

import android.net.ConnectivityManager;
import android.os.BatteryManager;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/** Host-only modeled permissions/power. This is not evidence about an Android device. */
public class Context {
    public static final String CONNECTIVITY_SERVICE = "connectivity";
    public static final int RECEIVER_NOT_EXPORTED = 4;
    public final ConnectivityManager connectivity = new ConnectivityManager();
    private final Set<BroadcastReceiver> receivers = new HashSet<BroadcastReceiver>();
    public boolean powered = true, permission = true;
    public int receiverRegistrations, receiverUnregistrations;
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String name) {
        if (!CONNECTIVITY_SERVICE.equals(name)) throw new AssertionError("Unexpected service");
        return connectivity;
    }
    public int checkSelfPermission(String name) {
        if (!"android.permission.ACCESS_LOCAL_NETWORK".equals(name)) throw new AssertionError("Unexpected permission");
        return permission ? 0 : -1;
    }
    public synchronized Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (receiver != null) {
            if (!receivers.add(receiver)) throw new AssertionError("Duplicate receiver");
            receiverRegistrations++;
        }
        // Sticky return is modeled; no unsolicited broadcast is synthesized.
        return new Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PLUGGED, powered ? 1 : 0)
            .putExtra(BatteryManager.EXTRA_STATUS, powered ? BatteryManager.BATTERY_STATUS_CHARGING : 3);
    }
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
        if (flags != RECEIVER_NOT_EXPORTED) throw new AssertionError("Unexpected receiver flags");
        return registerReceiver(receiver, filter);
    }
    public synchronized void unregisterReceiver(BroadcastReceiver receiver) {
        if (!receivers.remove(receiver)) throw new AssertionError("Unknown receiver");
        receiverUnregistrations++;
    }
    public Executor getMainExecutor() {
        return new Executor() { @Override public void execute(Runnable command) { command.run(); } };
    }
    public synchronized boolean receiversReleased() { return receivers.isEmpty(); }
}
