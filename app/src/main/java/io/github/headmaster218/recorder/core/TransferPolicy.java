package io.github.headmaster218.recorder.core;

/** Triggers are OR; all enabled safety constraints are AND, including Upload now. */
public final class TransferPolicy {
    public enum Block { NONE, NO_PENDING_DATA, NOT_TRIGGERED, NOT_CHARGING, OFFLINE,
        AMBIGUOUS_ROUTE, WIFI_REQUIRED, CELLULAR_DISABLED, METERED_DISABLED, ROAMING_DISABLED }
    public static final class Network {
        public final boolean connected, wifi, cellular, metered, roaming, ambiguous;
        public Network(boolean connected, boolean wifi, boolean cellular, boolean metered,
                       boolean roaming, boolean ambiguous) {
            this.connected = connected; this.wifi = wifi; this.cellular = cellular;
            this.metered = metered; this.roaming = roaming; this.ambiguous = ambiguous;
        }
    }
    public static final TransferPolicy DEFAULT = new TransferPolicy(true, true, false, false, false,
                                                                    9600000, -1);
    private final boolean chargingRequired, wifiOnly, cellularAllowed, meteredAllowed, roamingAllowed;
    private final long thresholdBytes, oldestAgeMillis;
    public TransferPolicy(boolean chargingRequired, boolean wifiOnly, boolean cellularAllowed,
                          boolean meteredAllowed, boolean roamingAllowed, long thresholdBytes,
                          long oldestAgeMillis) {
        if (thresholdBytes <= 0 || oldestAgeMillis < -1) throw new IllegalArgumentException();
        this.chargingRequired = chargingRequired; this.wifiOnly = wifiOnly;
        this.cellularAllowed = cellularAllowed; this.meteredAllowed = meteredAllowed;
        this.roamingAllowed = roamingAllowed; this.thresholdBytes = thresholdBytes;
        this.oldestAgeMillis = oldestAgeMillis;
    }
    public Block evaluate(long pendingBytes, long pendingAgeMillis, boolean sessionStopped,
                          boolean uploadNow, boolean charging, Network network) {
        if (pendingBytes < 0 || pendingAgeMillis < 0 || network == null) throw new IllegalArgumentException();
        if (pendingBytes == 0) return Block.NO_PENDING_DATA;
        if (!(pendingBytes >= thresholdBytes || (oldestAgeMillis >= 0 && pendingAgeMillis >= oldestAgeMillis)
            || sessionStopped || uploadNow)) return Block.NOT_TRIGGERED;
        if (chargingRequired && !charging) return Block.NOT_CHARGING;
        if (!network.connected) return Block.OFFLINE;
        if (network.ambiguous || (network.wifi == network.cellular)) return Block.AMBIGUOUS_ROUTE;
        if (wifiOnly && !network.wifi) return Block.WIFI_REQUIRED;
        if (!cellularAllowed && (network.cellular || !network.wifi)) return Block.CELLULAR_DISABLED;
        if (!meteredAllowed && network.metered) return Block.METERED_DISABLED;
        if (!roamingAllowed && network.roaming) return Block.ROAMING_DISABLED;
        return Block.NONE;
    }
}
