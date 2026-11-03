package android.os;
/** Host tests cannot validate Android thread scheduling. */
public final class Process {
    public static final int THREAD_PRIORITY_BACKGROUND=10;
    private Process(){ }
    public static void setThreadPriority(int priority){ }
}
