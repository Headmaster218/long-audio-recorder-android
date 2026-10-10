package io.github.headmaster218.recorder.android;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.headmaster218.recorder.core.CommittedSegments;
import io.github.headmaster218.recorder.core.FtpsTransfer;
import io.github.headmaster218.recorder.core.TransferPolicy;

/** Sole queue owner in the default app process. No Activity references and no microphone service dependency. */
final class FtpsCoordinator {
    static final int JOB_ID = 6101;
    private static FtpsCoordinator instance;
    static synchronized FtpsCoordinator get(Context c) {
        if (instance == null) instance = new FtpsCoordinator(c.getApplicationContext()); return instance;
    }
    static final class Snapshot {
        final CommittedSegments.Page page;
        final FtpsQueue.Profile profile;
        final List<FtpsQueue.Item> items;
        final Map<String,String> destinations;
        final String notice;
        final boolean busy, paused;
        Snapshot(CommittedSegments.Page page,FtpsQueue.Profile profile,List<FtpsQueue.Item> items,
                Map<String,String> destinations,String notice,boolean busy,boolean paused) {
            this.page = page; this.profile = profile; this.items = Collections.unmodifiableList(items);
            this.destinations = Collections.unmodifiableMap(destinations); this.notice = notice; this.busy = busy; this.paused = paused;
        }
    }
    interface Completion { void finished(); }
    static final class Job {
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile FtpsNetwork connection;
        long deadline;
        void cancel() {
            cancelled.set(true); FtpsNetwork n = connection; if (n != null) n.cancel("Transfer job stopped");
        }
        void check() throws IOException {
            if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) throw new IOException("Transfer job cancelled or deadline reached");
        }
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean commandPending = new AtomicBoolean();
    private final AtomicBoolean pauseRequested = new AtomicBoolean();
    private final AtomicBoolean pauseWriteQueued = new AtomicBoolean();
    private final AtomicBoolean reschedulePending = new AtomicBoolean();
    private volatile long scheduleSerial;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(final Runnable r) {
            return new Thread(new Runnable() { @Override public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run();
            } },"recorder-ftps-queue");
        }
    });
    private final Map<String,char[]> sessionPasswords = new HashMap<String,char[]>();
    private FtpsQueue queue;
    private CommittedSegments catalog;
    private CommittedSegments.Page page;
    private volatile Job activeJob;
    private volatile Snapshot snapshot = new Snapshot(null,null,new ArrayList<FtpsQueue.Item>(),new HashMap<String,String>(),"Loading local transfer state",false,false);
    private boolean unavailable;
    private FtpsCoordinator(Context context) {
        this.context = context;
        worker.execute(new Runnable() { @Override public void run() {
            try {
                queue = new FtpsQueue(FtpsCoordinator.this.context); queue.getWritableDatabase(); queue.recover();
                catalog = new CommittedSegments(FtpsCoordinator.this.context.getFilesDir().toPath().toRealPath().resolve("audio-spool"));
                publish("Automatic enrollment is OFF. Only WAVs explicitly queued to a shown profile can transfer.",false);
                schedule(nextDelay()); // Repairs a crash after queue commit but before scheduler submission.
            } catch (IOException | RuntimeException e) { fatal(); }
        } });
    }
    Snapshot snapshot() { return snapshot; }
    boolean commandPending() { return commandPending.get() || pauseWriteQueued.get() || activeJob != null; }
    private void fatal() {
        unavailable = true;
        snapshot = new Snapshot(page,null,new ArrayList<FtpsQueue.Item>(),new HashMap<String,String>(),
            "Local FTPS journal unavailable. Transfers are blocked; all originals retained. No durability claim is made.",false,true);
    }
    private void publish(String notice, boolean busy) {
        if (unavailable) return;
        List<FtpsQueue.Item> items = queue.items(false); Map<String,String> destinations = new HashMap<String,String>();
        for (FtpsQueue.Item item : items) if (!destinations.containsKey(item.revision)) {
            FtpsQueue.Profile p = queue.profile(item.revision); destinations.put(item.revision,p == null ? "Missing profile" : p.label());
        }
        snapshot = new Snapshot(page,queue.current(),items,destinations,notice,busy,queue.paused());
    }
    private interface Command { void run() throws Exception; default void discard() { } }
    private boolean command(final Command action) {
        if (activeJob != null || !commandPending.compareAndSet(false,true)) return false;
        worker.execute(new Runnable() { @Override public void run() {
            try {
                if (unavailable) return;
                action.run(); if (activeJob == null) schedule(nextDelay());
            } catch (Exception e) {
                try { publish("Local transfer action failed (" + e.getClass().getSimpleName() + "). Originals retained.",false); }
                catch (RuntimeException journalFailure) { fatal(); }
            } finally { action.discard(); commandPending.set(false); }
        } });
        return true;
    }
    void load(final String after) {
        command(new Command() { @Override public void run() throws IOException {
            page = catalog.page(after); publish("Review the exact completed segment and saved destination before queueing.",false);
        } });
    }
    void saveProfile(final FtpsTransfer.Profile destination, final char[] password, final boolean remember) {
        boolean accepted = command(new Command() { @Override public void run() throws Exception {
            try {
                String revision = FtpsQueue.revision(destination);
                byte[] encrypted = remember ? FtpsCredentials.remember(revision,password,true) : null;
                FtpsQueue.Profile saved = queue.save(destination,encrypted);
                char[] old = sessionPasswords.remove(saved.revision);
                if (old != null) Arrays.fill(old,'\0');
                if (!remember) sessionPasswords.put(saved.revision,Arrays.copyOf(password,password.length));
                queue.credentialsReady(saved.revision);
                publish(remember ? "Profile saved. Password encrypted in app-private, no-backup storage; locked/unavailable keys block transfer."
                    : "Profile saved. Password is session-only and will be lost when this process ends.",false);
            } finally { Arrays.fill(password,'\0'); }
        }
        @Override public void discard() { Arrays.fill(password,'\0'); }
        });
        if (!accepted) Arrays.fill(password,'\0');
    }
    void enqueue(final CommittedSegments.Page displayed, final int index, final String shownRevision) {
        command(new Command() { @Override public void run() throws IOException {
            FtpsQueue.Profile current = queue.current();
            if (displayed == null || displayed != page || index < 0 || index >= displayed.entries.size()
                    || current == null || !current.revision.equals(shownRevision)) {
                publish("Selection or destination changed. Review the current list and shown saved profile, then queue again.",false); return;
            }
            CommittedSegments.Entry entry = displayed.entries.get(index); catalog.unchanged(entry);
            boolean added = queue.enqueue(entry,shownRevision);
            publish(added ? "Selected WAV and technical segment metadata queued to the shown profile. Threshold: 9,600,000 pending bytes; Upload queued now is an explicit trigger."
                : "This source and destination revision already have a queue record. No duplicate upload or new remote attempt created.",false);
        } });
    }
    void uploadNow() {
        command(new Command() { @Override public void run() {
            pauseRequested.set(false); queue.uploadNow(); publish("Queued work requested. Charging, unmetered Wi-Fi, runtime permission and trusted TLS remain mandatory.",false);
        } });
    }
    void pause() {
        pauseRequested.set(true); Job running = activeJob; if (running != null) running.cancel();
        // Cancellation is immediate; the single journal owner commits pause after the owned I/O releases.
        if (!pauseWriteQueued.compareAndSet(false,true)) return;
        worker.execute(new Runnable() { @Override public void run() {
            try {
                if (!unavailable) { queue.pause(true); publish("Queue paused. Any started attempt can only be reconciled by readback; originals retained.",false); schedule(-1); }
            } catch (RuntimeException e) { fatal(); }
            finally { pauseWriteQueued.set(false); }
        } });
    }
    synchronized Job startJob(final Completion completion) {
        if (activeJob != null) return null;
        final Job job = new Job(); activeJob = job;
        worker.execute(new Runnable() { @Override public void run() {
            try {
                if (!unavailable && !pauseRequested.get()) {
                    job.deadline = SystemClock.elapsedRealtime() + FtpsNetwork.ATTEMPT_MILLIS;
                    runOne(job);
                }
                if (!unavailable) {
                    if (pauseRequested.get()) queue.pause(true);
                    publish(queue.paused() ? "Queue paused; originals retained." : snapshot.notice,false);
                }
            } catch (RuntimeException e) { fatal(); }
            finally {
                job.cancel();
                main.post(new Runnable() { @Override public void run() {
                    synchronized (FtpsCoordinator.this) { if (activeJob == job) activeJob = null; }
                    completion.finished();
                } });
            }
        } });
        return job;
    }
    private char[] password(FtpsQueue.Profile profile) throws Exception {
        char[] session = sessionPasswords.get(profile.revision);
        if (session != null) return Arrays.copyOf(session,session.length);
        if (profile.credential != null) return FtpsCredentials.unlock(profile.revision,profile.credential);
        throw new IOException("Session-only credential no longer available");
    }
    private long pendingBytes(List<FtpsQueue.Item> items) {
        long bytes = 0;
        for (FtpsQueue.Item item : items) bytes = item.bytes > Long.MAX_VALUE - bytes ? Long.MAX_VALUE : bytes + item.bytes;
        return bytes;
    }
    private void runOne(final Job job) {
        if (queue.paused()) return;
        List<FtpsQueue.Item> items = queue.items(true); long total = pendingBytes(items), now = System.currentTimeMillis();
        FtpsQueue.Item selected = null;
        for (FtpsQueue.Item item : items) if (item.nextAt <= now && triggered(item,total,now)) { selected = item; break; }
        if (selected == null) return;
        final FtpsQueue.Item item = selected;
        FtpsQueue.Profile profile = queue.profile(item.revision); char[] secret = null;
        try {
            if (profile == null) { queue.state(item,"BLOCKED","Saved destination is unavailable",0); return; }
            try { secret = password(profile); }
            catch (Exception e) {
                queue.state(item,"NEEDS_CREDENTIALS","Password unavailable or secure storage locked. Unlock device and explicitly save credentials for this exact profile again.",0);
                publish("Transfer blocked until credentials are supplied for the exact destination revision.",false); return;
            }
            final Network selectedNetwork;
            try { selectedNetwork = FtpsNetwork.allowed(context,total,Math.max(0,now - item.queuedAt),item.uploadNow || item.attempt != null); }
            catch (IOException | RuntimeException e) {
                queue.state(item,item.state,"Waiting for charging, unmetered Wi-Fi and local-network permission; VPN/ambiguous routes are blocked",now + 300000);
                publish("Transfer safety gates are not satisfied. Upload now cannot bypass them.",false); return;
            }
            final CommittedSegments.Entry entry;
            try {
                job.check(); entry = catalog.load(item.source);
                if (entry.metadata.wavBytes != item.bytes || !entry.metadata.wavSha256.equals(item.wavHash)
                    || !FtpsQueue.digest(entry.metadata.encode()).equals(item.metadataHash)) throw new IOException("Source snapshot changed");
                job.check();
            } catch (IOException | RuntimeException e) {
                if (job.cancelled.get() || SystemClock.elapsedRealtime() >= job.deadline) {
                    queue.state(item,item.state,"Stopped before network I/O; source and queued identity retained",now + 60000);
                } else queue.state(item,"BLOCKED","Local source identity, metadata or content verification failed. Source mutation is blocked; all originals retained.",0);
                return;
            }
            if (item.attempt != null && item.reconciliations >= FtpsQueue.MAX_RECONCILIATIONS) {
                queue.state(item,"BLOCKED","Readback retry limit reached. Remote attempt may be incomplete. No overwrite, replacement attempt or deletion is permitted in this version.",0); return;
            }
            String attempt = item.attempt == null ? FtpsTransfer.newAttemptName(item.source,item.wavHash,item.revision) : item.attempt;
            queue.begin(item,attempt); // Must commit successfully before creating any connection or issuing DNS.
            publish(item.attempt == null ? "Sending explicitly queued WAV + technical metadata to " + profile.label()
                : "Read-only reconciliation of the exact prior remote attempt at " + profile.label(),true);
            try (FtpsNetwork connection = new FtpsNetwork(context,selectedNetwork)) {
                job.connection = connection; job.check();
                final FtpsNetwork guardedNetwork = connection;
                FtpsTransfer.Guard guard = new FtpsTransfer.Guard() { @Override public void check() throws IOException { job.check(); guardedNetwork.check(); } };
                FtpsTransfer.Result result = FtpsTransfer.run(catalog,entry,profile.destination,secret,connection,item.revision,attempt,item.attempt != null,guard);
                job.check(); queue.verified(item,result);
                publish("VERIFIED AT TIME: remote bytes and hashes matched on readback. Originals retained; server durability and future integrity are unproven.",false);
            } catch (IOException | RuntimeException e) {
                boolean permanent = e instanceof javax.net.ssl.SSLHandshakeException
                    || e instanceof javax.net.ssl.SSLPeerUnverifiedException
                    || e instanceof FtpsTransfer.VerificationException
                    || (e instanceof FtpsTransfer.ProtocolException && !((FtpsTransfer.ProtocolException) e).retryable);
                int used = item.reconciliations + (item.attempt == null ? 0 : 1);
                if (permanent || used >= FtpsQueue.MAX_RECONCILIATIONS) {
                    queue.state(item,"BLOCKED","Remote attempt is incomplete, rejected or unverified. Inspect the shown attempt. No automatic replacement, overwrite or deletion.",0);
                } else {
                    queue.state(item,"RECONCILE","Outcome unknown. Next attempt only reads the same remote directory; it never resumes writing or creates another attempt.",System.currentTimeMillis() + (60000L << used));
                }
                publish("Transfer was interrupted or unverified. All originals retained; any retry is bounded readback-only reconciliation.",false);
            } finally { job.connection = null; }
        } catch (IOException e) {
            // A journal begin/commit error forbids all network work; do not guess whether the intent persisted.
            unavailable = true; fatal();
        } finally { if (secret != null) Arrays.fill(secret,'\0'); }
    }
    private boolean triggered(FtpsQueue.Item item, long total, long now) {
        // Reuse the policy trigger rather than independently reimplementing the threshold. Actual gates use the real route.
        TransferPolicy.Network eligible = new TransferPolicy.Network(true,true,false,false,false,false);
        return TransferPolicy.DEFAULT.evaluate(total,Math.max(0,now - item.queuedAt),false,
            item.uploadNow || item.attempt != null,true,eligible) == TransferPolicy.Block.NONE;
    }
    void reschedule() {
        if (!reschedulePending.compareAndSet(false,true)) return;
        worker.execute(new Runnable() { @Override public void run() {
            try { schedule(nextDelay()); } catch (RuntimeException e) { fatal(); }
            finally { reschedulePending.set(false); }
        } });
    }
    private long nextDelay() {
        if (unavailable || queue.paused() || pauseRequested.get()) return -1;
        List<FtpsQueue.Item> pending = queue.items(true); long total = pendingBytes(pending), soonest = Long.MAX_VALUE, now = System.currentTimeMillis();
        for (FtpsQueue.Item item : pending) if (triggered(item,total,now))
            soonest = Math.min(soonest,Math.max(0,item.nextAt - now));
        return soonest == Long.MAX_VALUE ? -1 : Math.max(1000,soonest);
    }
    private void schedule(final long delay) {
        final long serial = ++scheduleSerial; // Called only by the queue owner.
        main.post(new Runnable() { @Override public void run() {
            if (serial != scheduleSerial || activeJob != null) return;
            scheduleOnMain(pauseRequested.get() ? -1 : delay);
        } });
    }
    private void scheduleOnMain(long delay) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (delay < 0) { scheduler.cancel(JOB_ID); return; }
        JobInfo job = new JobInfo.Builder(JOB_ID,new ComponentName(context,FtpsJobService.class))
            .setRequiresCharging(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setPersisted(true).setMinimumLatency(delay).setBackoffCriteria(60000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            Snapshot old = snapshot;
            snapshot = new Snapshot(old.page,old.profile,old.items,old.destinations,
                "Android did not schedule the transfer. Queue is retained; open this screen and request Upload queued now again.",old.busy,old.paused);
        }
    }
}
