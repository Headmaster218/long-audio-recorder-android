package io.github.headmaster218.recorder.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.DnsResolver;
import android.net.InetAddresses;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.BatteryManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Process;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import io.github.headmaster218.recorder.core.FtpsTransfer;
import io.github.headmaster218.recorder.core.TransferPolicy;

/** One selected Android Network owns DNS and every TCP socket. No process-wide route binding. */
final class FtpsNetwork implements FtpsTransfer.Connector, FtpsTransfer.Guard, AutoCloseable {
    static final String LAN_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    static final long ATTEMPT_MILLIS = 180000;
    private static final int SOCKET_MILLIS = 10000;
    private final Context context;
    private final ConnectivityManager connectivity;
    private final Network network;
    private final long deadline = SystemClock.elapsedRealtime() + ATTEMPT_MILLIS;
    private final AtomicReference<IOException> stopped = new AtomicReference<IOException>();
    private final List<Socket> sockets = new ArrayList<Socket>(2);
    private final CancellationSignal dnsCancellation = new CancellationSignal();
    private final ScheduledExecutorService watchdog;
    private boolean networkRegistered, batteryRegistered;
    private volatile long nextInspection;
    private long nextPowerInspection;
    private volatile boolean powered;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { inspect(); }
        @Override public void onLost(Network n) { if (network.equals(n)) cancel("Wi-Fi route was revoked"); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            // Callback data is authoritative even before getNetworkCapabilities reflects the change.
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                || (network.equals(n) && !eligibleWifi(caps))
                || (!network.equals(n) && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && (!onlyWifiTransport(caps) || eligibleWifi(caps)))) {
                cancel("Network capability callback revoked the safe Wi-Fi route"); return;
            }
            inspect();
        }
        @Override public void onBlockedStatusChanged(Network n, boolean blocked) {
            if (network.equals(n) && blocked) cancel("Android blocked this network"); else inspect();
        }
    };
    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_POWER_DISCONNECTED.equals(i.getAction())) { powered = false; cancel("Charger disconnected"); }
            else { powered = batteryCharging(i); inspect(); }
        }
    };
    static boolean permissionReady(Context c) {
        return Build.VERSION.SDK_INT < 37 || c.checkSelfPermission(LAN_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }
    static boolean charging(Context c) {
        Intent i = c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return batteryCharging(i);
    }
    private static boolean batteryCharging(Intent i) {
        if (i == null || i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) == 0) return false;
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
    }
    static TransferPolicy.Network route(ConnectivityManager cm, Network selected) {
        NetworkCapabilities caps = selected == null ? null : cm.getNetworkCapabilities(selected);
        boolean same = selected != null && selected.equals(selectWifi(cm));
        boolean wifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        boolean cellular = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        boolean ambiguous = caps == null || !same || !onlyWifiTransport(caps)
            || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        return new TransferPolicy.Network(caps != null && same,wifi,cellular,
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),ambiguous);
    }
    private static Network selectWifi(ConnectivityManager cm) {
        Network selected = null; boolean ambiguous = false;
        for (Network candidate : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(candidate);
            if (caps == null) continue;
            // An observed VPN or mixed transport makes the route ineligible; never bypass it.
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) { ambiguous = true; continue; }
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            if (!onlyWifiTransport(caps)) { ambiguous = true; continue; }
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) continue;
            if (selected != null) ambiguous = true;
            selected = candidate;
        }
        return ambiguous ? null : selected;
    }
    private static boolean eligibleWifi(NetworkCapabilities caps) {
        return onlyWifiTransport(caps)
            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
    }
    private static boolean onlyWifiTransport(NetworkCapabilities caps) {
        if (Build.VERSION.SDK_INT > 37 || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false;
        // Public compile-SDK transport constants are inlined ints, not newer method calls.
        // If an older platform rejects an unknown value, fail closed rather than guessing a route.
        try {
            return !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_THREAD)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE);
        } catch (IllegalArgumentException unsupported) { return false; }
    }
    static Network allowed(Context c, long bytes, long age, boolean uploadNow) throws IOException {
        if (!permissionReady(c)) throw new IOException("Local-network permission required; request it on the transfer screen");
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = selectWifi(cm);
        TransferPolicy.Block block = TransferPolicy.DEFAULT.evaluate(bytes,age,false,uploadNow,charging(c),route(cm,n));
        if (block != TransferPolicy.Block.NONE) throw new IOException("Transfer blocked: " + block.name());
        return n;
    }
    FtpsNetwork(Context c, Network selected) throws IOException {
        context = c.getApplicationContext(); network = selected; powered = charging(context);
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        watchdog = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(final Runnable r) {
                return new Thread(new Runnable() { @Override public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run();
                } },"ftps-socket-watchdog");
            }
        });
        try {
            check();
            // Observe Wi-Fi and VPN changes, even when cellular is the default route. This never requests a new route.
            connectivity.registerNetworkCallback(new NetworkRequest.Builder().clearCapabilities().build(),networkCallback);
            networkRegistered = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED); filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(battery,filter,Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(battery,filter);
            batteryRegistered = true;
            watchdog.scheduleAtFixedRate(new Runnable() { @Override public void run() { inspect(); } },0,250,TimeUnit.MILLISECONDS);
            check();
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    private synchronized void inspect() {
        nextInspection = 0;
        try { check(); }
        catch (IOException | RuntimeException e) { cancel("Charging, permission, deadline or Wi-Fi route no longer safe"); }
    }
    @Override public synchronized void check() throws IOException {
        IOException cancelled = stopped.get(); if (cancelled != null) throw cancelled;
        if (SystemClock.elapsedRealtime() >= deadline) { cancel("Transfer deadline reached"); throw stopped.get(); }
        long now = SystemClock.elapsedRealtime();
        if (now < nextInspection) return;
        if (now >= nextPowerInspection) { powered = charging(context); nextPowerInspection = now + 1000; }
        if (!permissionReady(context)) { cancel("Local-network permission revoked"); throw stopped.get(); }
        TransferPolicy.Block block = TransferPolicy.DEFAULT.evaluate(1,0,false,true,powered,route(connectivity,network));
        if (block != TransferPolicy.Block.NONE) { cancel("Transfer safety gate changed: " + block.name()); throw stopped.get(); }
        nextInspection = now + 250;
    }
    void cancel(String reason) {
        if (!stopped.compareAndSet(null,new IOException(reason))) return;
        dnsCancellation.cancel();
        // Close raw owned TCP sockets, rather than waiting for TLS close_notify on an unsafe route.
        synchronized (sockets) {
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            sockets.clear();
        }
    }
    private InetAddress resolve(String host) throws IOException {
        check();
        if (InetAddresses.isNumericAddress(host)) return InetAddresses.parseNumericAddress(host);
        final AtomicReference<InetAddress> address = new AtomicReference<InetAddress>();
        final AtomicReference<IOException> error = new AtomicReference<IOException>();
        final CountDownLatch completed = new CountDownLatch(1);
        DnsResolver.getInstance().query(network,host,DnsResolver.FLAG_EMPTY,context.getMainExecutor(),dnsCancellation,
            new DnsResolver.Callback<List<InetAddress>>() {
                @Override public void onAnswer(List<InetAddress> addresses, int rcode) {
                    if (rcode == 0 && addresses != null && !addresses.isEmpty()) address.set(addresses.get(0));
                    else error.set(new IOException("No usable bound DNS answer"));
                    completed.countDown();
                }
                @Override public void onError(DnsResolver.DnsException e) { error.set(new IOException("Bound DNS resolution failed")); completed.countDown(); }
            });
        long until = SystemClock.elapsedRealtime() + SOCKET_MILLIS;
        while (true) {
            check();
            try { if (completed.await(250,TimeUnit.MILLISECONDS)) break; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); cancel("DNS interrupted"); throw new IOException("DNS interrupted",e); }
            if (SystemClock.elapsedRealtime() >= until) { cancel("Bound DNS deadline reached"); throw stopped.get(); }
        }
        check(); if (error.get() != null) throw error.get();
        if (address.get() == null) throw new IOException("No bound DNS peer"); return address.get();
    }
    @Override public FtpsTransfer.Connection connect(String host, int port) throws IOException {
        return connectPeer(host,resolve(host),port);
    }
    private BoundConnection connectPeer(String host, InetAddress peer, int port) throws IOException {
        check(); if (port < 1 || port > 65535) throw new IOException("Invalid port");
        Socket raw = network.getSocketFactory().createSocket();
        synchronized (sockets) {
            if (stopped.get() != null) { raw.close(); throw stopped.get(); }
            if (sockets.size() >= 2) { raw.close(); throw new IOException("Owned socket limit exceeded"); }
            sockets.add(raw);
        }
        try {
            raw.setSoTimeout(SOCKET_MILLIS); raw.setSoLinger(false,0);
            raw.connect(new InetSocketAddress(peer,port),SOCKET_MILLIS); check();
            if (!peer.equals(raw.getInetAddress())) throw new IOException("Peer identity changed");
            return new BoundConnection(host,peer,raw);
        } catch (IOException | RuntimeException e) {
            try { raw.close(); } catch (IOException ignored) { }
            synchronized (sockets) { sockets.remove(raw); }
            throw e;
        }
    }
    private final class BoundConnection implements FtpsTransfer.Connection {
        private final String host;
        private final InetAddress peer;
        private final Socket raw;
        private Socket channel;
        private boolean secure, closed;
        BoundConnection(String host, InetAddress peer, Socket raw) { this.host = host; this.peer = peer; this.raw = raw; channel = raw; }
        @Override public InputStream input() throws IOException { check(); return channel.getInputStream(); }
        @Override public OutputStream output() throws IOException { check(); return channel.getOutputStream(); }
        @Override public void secure(String expectedHost) throws IOException {
            check(); if (closed || secure || !host.equals(expectedHost)) throw new IOException("TLS host/state mismatch");
            try {
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw,expectedHost,raw.getPort(),true);
                channel = tls; tls.setUseClientMode(true); tls.setSoTimeout(SOCKET_MILLIS);
                List<String> protocols = new ArrayList<String>(2);
                for (String p : tls.getSupportedProtocols()) if ("TLSv1.2".equals(p) || "TLSv1.3".equals(p)) protocols.add(p);
                if (protocols.isEmpty()) throw new IOException("TLS 1.2 or newer unavailable");
                tls.setEnabledProtocols(protocols.toArray(new String[protocols.size()]));
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS"); tls.setSSLParameters(parameters);
                // Platform trust manager plus endpoint identification apply independently on control AND data.
                tls.startHandshake(); check();
                if (!tls.getSession().isValid() || !("TLSv1.2".equals(tls.getSession().getProtocol()) || "TLSv1.3".equals(tls.getSession().getProtocol())))
                    throw new IOException("Trusted TLS session unavailable");
                secure = true;
            } catch (IOException | RuntimeException e) {
                try { close(); } catch (IOException closing) { e.addSuppressed(closing); }
                throw e;
            }
        }
        @Override public FtpsTransfer.Connection openData(int port) throws IOException {
            check(); if (!secure || closed) throw new IOException("Data connection requires secured control channel");
            // No hostname re-resolution or PASV host fallback. Data is pinned to this control peer and Network.
            return connectPeer(host,peer,port);
        }
        @Override public void close() throws IOException {
            if (closed) return; closed = true;
            try {
                // Normal EOF sends TLS close_notify. The deadline watchdog still owns and can close raw TCP.
                if (channel != raw && stopped.get() == null) channel.close();
            } finally {
                try { raw.close(); } finally { synchronized (sockets) { sockets.remove(raw); } }
            }
        }
    }
    @Override public void close() {
        cancel("Transfer connection closed"); watchdog.shutdownNow();
        if (networkRegistered) { try { connectivity.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) { } networkRegistered = false; }
        if (batteryRegistered) { try { context.unregisterReceiver(battery); } catch (RuntimeException ignored) { } batteryRegistered = false; }
    }
}
