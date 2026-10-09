package io.github.headmaster218.recorder.android;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Process;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import io.github.headmaster218.recorder.core.CommittedSegments;
import io.github.headmaster218.recorder.core.ExportSession;
import io.github.headmaster218.recorder.core.VerifiedExport;

/** One process-local worker, no Activity reference, no persistent URI grant and no restart/retry job. */
final class ExportCoordinator {
    private static ExportCoordinator instance;
    static synchronized ExportCoordinator get(Context c) {
        if (instance == null) instance = new ExportCoordinator(c.getApplicationContext());
        return instance;
    }
    final ExportSession session = new ExportSession();
    private final Context context;
    private final SharedPreferences journal;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(final Runnable work) {
            return new Thread(new Runnable() { @Override public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); work.run();
            } }, "recorder-export");
        }
    });
    private CommittedSegments catalog;
    private final boolean interrupted;
    private ExportCoordinator(Context context) {
        this.context = context; journal = context.getSharedPreferences("manual-export", Context.MODE_PRIVATE);
        interrupted = journal.getBoolean("in-flight", false);
    }
    boolean previousInterrupted() { return interrupted; }
    void load(final String after) {
        final String token = session.list(); if (token == null) return;
        worker.execute(new Runnable() { @Override public void run() {
            try {
                // Canonicalize the platform-provided private anchor only; catalog rejects links below it.
                if (catalog == null) catalog = new CommittedSegments(context.getFilesDir().toPath().toRealPath().resolve("audio-spool"));
                session.listed(token, catalog.page(after), null);
            } catch (IOException | RuntimeException e) { session.listed(token, null, "Could not list completed segments (" + e.getClass().getSimpleName() + "). Originals retained."); }
        } });
    }
    String choose(CommittedSegments.Page displayedPage, int index) {
        String token = session.choose(displayedPage, index); if (token == null) return null;
        // Marker is committed before handing control to another app. No audio or target URI is stored here.
        if (!journal.edit().putBoolean("in-flight", true).commit()) {
            session.pickerCancelled(token, "Could not save export interruption marker; export has not started."); return null;
        }
        return token;
    }
    void pickerCancelled(String token, String detail) {
        ExportSession.Snapshot before = session.snapshot();
        session.pickerCancelled(token, detail);
        if (before.phase == ExportSession.Phase.CHOOSING && before.token.equals(token))
            journal.edit().remove("in-flight").apply();
    }
    void copy(final String token, final Uri uri) {
        final CommittedSegments.Entry entry = session.destination(token); if (entry == null) return;
        worker.execute(new Runnable() { @Override public void run() {
            final ContentResolver resolver = context.getContentResolver();
            VerifiedExport.Result result = VerifiedExport.copy(catalog, entry, new VerifiedExport.Target() {
                @Override public OutputStream openWrite() throws IOException {
                    // ACTION_CREATE_DOCUMENT creates a new target; "w" may not truncate on every provider.
                    // Readback catches trailing bytes. No seek, rename, file-path conversion or fsync is assumed.
                    AssetFileDescriptor fd = resolver.openAssetFileDescriptor(uri, "w");
                    if (fd == null) throw new IOException("No target descriptor");
                    try { return fd.createOutputStream(); }
                    catch (IOException | RuntimeException e) { try { fd.close(); } catch (IOException close) { e.addSuppressed(close); } throw e; }
                }
                @Override public InputStream openRead() throws IOException {
                    AssetFileDescriptor fd = resolver.openAssetFileDescriptor(uri, "r");
                    if (fd == null) throw new IOException("No target readback descriptor");
                    try { return fd.createInputStream(); }
                    catch (IOException | RuntimeException e) { try { fd.close(); } catch (IOException close) { e.addSuppressed(close); } throw e; }
                }
            }, new VerifiedExport.Cancellation() { @Override public boolean cancelled() { return session.cancelled(token); } });
            // If the commit fails, the retained marker causes a conservative unknown result after process death.
            journal.edit().remove("in-flight").commit();
            session.finished(token, result);
        } });
    }
    void cancel() {
        // Cooperative cancellation only: providers can block in open/read/write/close. Never abandon
        // a blocked worker or create more copy/cancellation threads; keep the single-flight gate closed.
        session.cancelCopy();
    }
}
