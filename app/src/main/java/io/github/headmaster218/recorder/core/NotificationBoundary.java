package io.github.headmaster218.recorder.core;

/** Narrow best-effort notification boundary. It grants no microphone or cleanup authority. */
public final class NotificationBoundary {
    private NotificationBoundary() { }
    public interface Publication {
        boolean visible();
        void publish();
    }
    /** False includes unavailable visibility and a failed query, build or notification operation. */
    public static boolean publish(Publication publication) {
        try {
            if (!publication.visible()) return false;
            publication.publish();
            return true;
        } catch (RuntimeException notificationFailure) { return false; }
    }
    /** Only notification failures are contained. Cleanup failures still propagate after service stop. */
    public static void finish(Runnable foregroundCleanup,Publication result,Runnable serviceStop) {
        try {
            foregroundCleanup.run();
            if (result!=null) publish(result);
        } finally { serviceStop.run(); }
    }
}
