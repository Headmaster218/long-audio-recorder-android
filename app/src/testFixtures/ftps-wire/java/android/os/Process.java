package android.os;
/** Host process priority is enforced by the runner's nice/taskset, not this port. */
public final class Process {
    public static final int THREAD_PRIORITY_BACKGROUND = 10;
    private Process() { }
    public static void setThreadPriority(int priority) {
        if (priority != THREAD_PRIORITY_BACKGROUND) throw new AssertionError("Unexpected Android priority");
    }
}
