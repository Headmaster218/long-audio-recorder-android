package android.net;
public final class NetworkRequest {
    private NetworkRequest() { }
    public static final class Builder {
        public Builder clearCapabilities() { return this; }
        public NetworkRequest build() { return new NetworkRequest(); }
    }
}
