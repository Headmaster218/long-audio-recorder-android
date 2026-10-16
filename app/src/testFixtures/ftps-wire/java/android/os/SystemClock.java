package android.os;
import java.util.concurrent.TimeUnit;
public final class SystemClock {
    private SystemClock() { }
    public static long elapsedRealtime() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
}
