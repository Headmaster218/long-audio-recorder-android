package io.github.headmaster218.recorder.android;

import java.io.IOException;
import io.github.headmaster218.recorder.core.DirectorySpool;

/** Process admission only. No filesystem I/O while holding LOCK. Physical owner outlives leases. */
final class SpoolAdmission {
    static final Object LOCK=new Object();
    private static DirectorySpool capture;
    private static Object opening;
    private static Reservation maintenance;
    private SpoolAdmission() { }
    static Object beginCapture() throws IOException {
        synchronized(LOCK) {
            if(opening!=null || capture!=null || maintenance!=null) throw new IOException("Spool maintenance still active; retry Start");
            opening=new Object(); return opening;
        }
    }
    static void opened(Object token,DirectorySpool spool) {
        synchronized(LOCK) { if(opening!=token || token==null || capture!=null) throw new IllegalStateException("stale capture admission"); capture=spool; opening=null; }
    }
    static void abandonOpening(Object token) { synchronized(LOCK) { if(opening!=token || token==null) throw new IllegalStateException("stale opening token"); opening=null; } }
    static void captureClosed(DirectorySpool spool) {
        synchronized(LOCK) { if(capture!=spool || spool==null) throw new IllegalStateException("stale capture owner"); capture=null; }
    }
    static boolean maintenanceActive() { synchronized(LOCK) { return maintenance!=null; } }
    /** Null is an idle reservation; nonnull is the same lifetime physical writer-lock owner. */
    static Reservation beginMaintenance() throws IOException {
        synchronized(LOCK) {
            if(opening!=null || maintenance!=null || ExportCoordinator.cleanupBlocked()) throw new IOException("Spool reader/maintenance busy");
            maintenance=new Reservation(capture); return maintenance;
        }
    }
    static final class Reservation implements AutoCloseable {
        final DirectorySpool owner;
        private Reservation(DirectorySpool owner) { this.owner=owner; }
        @Override public void close() { synchronized(LOCK) { if(maintenance!=this) throw new IllegalStateException("stale maintenance token"); maintenance=null; } }
    }
}
