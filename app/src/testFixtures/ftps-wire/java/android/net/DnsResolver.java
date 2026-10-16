package android.net;
import android.os.CancellationSignal;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.Executor;
/** Fail closed if production ever leaves the numeric-address branch. */
public final class DnsResolver {
    public static final int FLAG_EMPTY = 0;
    private static final DnsResolver INSTANCE = new DnsResolver();
    private DnsResolver() { }
    public static DnsResolver getInstance() { return INSTANCE; }
    public void query(Network network, String host, int flags, Executor executor,
            CancellationSignal signal, Callback<? super List<InetAddress>> callback) {
        throw new AssertionError("DNS is forbidden in the wire fixture");
    }
    public interface Callback<T> {
        void onAnswer(T answer, int rcode);
        void onError(DnsException exception);
    }
    public static final class DnsException extends Exception {
        private static final long serialVersionUID = 1L;
        public DnsException() { super("DNS is forbidden"); }
    }
}
