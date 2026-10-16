package android.os;
public final class CancellationSignal {
    private volatile boolean cancelled;
    public void cancel() { cancelled = true; }
    public boolean isCanceled() { return cancelled; }
}
