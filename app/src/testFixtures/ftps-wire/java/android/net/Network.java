package android.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;

/** Real host TCP, with a pre-connect literal-address gate on every socket. */
public final class Network {
    private final List<LoopbackSocket> sockets = new ArrayList<LoopbackSocket>();
    private final CountDownLatch connected = new CountDownLatch(1);
    private int connectionCount;
    private final SocketFactory factory = new SocketFactory() {
        @Override public Socket createSocket() {
            LoopbackSocket socket = new LoopbackSocket();
            synchronized (sockets) { sockets.add(socket); }
            return socket;
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            if (!"127.0.0.1".equals(host)) throw new IOException("DNS and non-loopback are forbidden");
            return createSocket(InetAddresses.parseNumericAddress(host), port);
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket socket = createSocket();
            try { socket.connect(new InetSocketAddress(host, port)); return socket; }
            catch (IOException | RuntimeException e) { socket.close(); throw e; }
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            throw new IOException("Explicit local binding is outside this fixture");
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
            throw new IOException("Explicit local binding is outside this fixture");
        }
    };
    public SocketFactory getSocketFactory() { return factory; }
    public boolean awaitConnection(long millis) throws InterruptedException { return connected.await(millis, TimeUnit.MILLISECONDS); }
    public int socketsCreated() { synchronized (sockets) { return sockets.size(); } }
    public int connectionsMade() { synchronized (sockets) { return connectionCount; } }
    public boolean allSocketsClosed() {
        synchronized (sockets) {
            for (Socket socket : sockets) if (!socket.isClosed()) return false;
            return true;
        }
    }
    private final class LoopbackSocket extends Socket {
        @Override public void connect(SocketAddress endpoint) throws IOException { connect(endpoint, 10000); }
        @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress)) throw new IOException("Non-IP socket address forbidden");
            InetSocketAddress target = (InetSocketAddress) endpoint;
            if (target.isUnresolved() || !"127.0.0.1".equals(target.getHostString()))
                throw new IOException("Only literal 127.0.0.1 is permitted");
            byte[] bytes = target.getAddress().getAddress();
            if (bytes.length != 4 || bytes[0] != 127 || bytes[1] != 0 || bytes[2] != 0 || bytes[3] != 1)
                throw new IOException("Non-loopback destination forbidden");
            if (target.getPort() < 1 || target.getPort() > 65535) throw new IOException("Invalid port");
            super.connect(target, timeout);
            synchronized (sockets) { connectionCount++; }
            connected.countDown();
        }
    }
}
