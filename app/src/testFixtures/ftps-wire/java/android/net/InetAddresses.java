package android.net;
import java.net.InetAddress;
import java.net.UnknownHostException;
/** Literal only, no resolver calls, including for an accidentally supplied name. */
public final class InetAddresses {
    private InetAddresses() { }
    public static boolean isNumericAddress(String address) {
        requireLiteral(address); return true;
    }
    public static InetAddress parseNumericAddress(String address) {
        requireLiteral(address);
        try { return InetAddress.getByAddress(new byte[] {127, 0, 0, 1}); }
        catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
    }
    private static void requireLiteral(String address) {
        if (!"127.0.0.1".equals(address)) throw new IllegalArgumentException("Wire fixture rejects DNS and non-loopback literals");
    }
}
