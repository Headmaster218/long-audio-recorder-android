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
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.Semaphore;
import io.github.headmaster218.recorder.core.DirectorySpool;
import io.github.headmaster218.recorder.core.CachePolicy;
import io.github.headmaster218.recorder.core.SegmentStore;
import io.github.headmaster218.recorder.core.VerifiedLocalCleanup;
import io.github.headmaster218.recorder.core.VerifiedExport;
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
        String automaticRevision, automaticNotice = "OFF", cleanupRevision, cleanupLabel = "OFF", retiredCount="0",retiredForCurrent="0",retirementNotice="";
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
    private boolean retirementBlocked;
    private String retirementNotice="";
    private final AtomicBoolean cleanupRevoked=new AtomicBoolean();
    private final AtomicBoolean cleanupDisableQueued=new AtomicBoolean();
    private final Semaphore notifications = new Semaphore(32);
    private final AtomicBoolean overflow = new AtomicBoolean();
    private final AtomicBoolean catchupPosted = new AtomicBoolean();
    private ArrayDeque<String> catchup;
    private boolean catchupDeferred;
    private Path spoolRoot() throws IOException { return context.getFilesDir().toPath().toRealPath().resolve("audio-spool"); }
    private void automaticBlocked(String reason) {
        queue.automatic(null,reason + "; enrollment OFF; originals and existing queue retained. Review and enable again.");
        catchup = null; catchupDeferred = false;
    }
    void automatic(final boolean enabled, final String shownRevision) {
        command(new Command() { @Override public void run() throws IOException {
            FtpsQueue.Profile current = queue.current();
            if (enabled && (current == null || !current.revision.equals(shownRevision))) {
                publish("Destination changed; review and enable again.",false); return;
            }
            queue.automatic(enabled ? shownRevision : null,enabled ? "ON: pinned to " + current.label() : "OFF: already queued work remains authorized; Pause stops transfers");
            catchup = null; catchupDeferred = false;
            if (enabled) { try { prepareCatchup(); } catch (IOException e) { automaticBlocked("Discovery bounds or validation failed"); } }
            publish("Automatic enrollment setting saved. Uncertain sources retained.",false);
            postCatchup();
        } });
    }
    private void capacityWait() {
        if(queue.automaticCapacityAt()==0)queue.automaticCapacityWait(System.currentTimeMillis()+300000);
    }
    private void resumeCapacity() throws IOException {
        if(queue.paused() || pauseRequested.get() || queue.automaticRevision()==null || queue.automaticCapacityAt()==0 || queue.automaticCapacityAt()>System.currentTimeMillis())return;
        if(queue.full()){queue.automaticCapacityWait(System.currentTimeMillis()+300000);return;}
        // One bounded names-only scan per due capacity wake; never scan in published().
        prepareCatchup();queue.automaticCapacityScanned();catchupDeferred=false;postCatchup();
    }
    private void prepareCatchup() throws IOException {
        String revision = queue.automaticRevision(); if (revision == null) return;
        catchup = io.github.headmaster218.recorder.core.FinalizedDiscovery.candidates(spoolRoot(),new io.github.headmaster218.recorder.core.FinalizedDiscovery.Known() { @Override public boolean contains(String id) { return queue.contains(id,revision); } },4096,FtpsQueue.MAX_ROWS);
    }

    private void postCatchup() {
        if (catchup == null || catchup.isEmpty() || catchupDeferred || !catchupPosted.compareAndSet(false,true)) return;
        main.postDelayed(new Runnable() { @Override public void run() {
            worker.execute(new Runnable() { @Override public void run() {
                catchupPosted.set(false);
                try { catchupOne(); if (!unavailable) { publish(snapshot.notice,false); schedule(nextDelay()); postCatchup(); } }
                catch (IOException | RuntimeException e) {
                    try {
                        if (pauseRequested.get() || queue.paused()) { catchupDeferred = true; publish("Catch-up paused; consent and uncertain sources retained.",false); }
                        else { automaticBlocked("Catch-up confirmation failed"); publish("Catch-up stopped safely",false); }
                    } catch (RuntimeException failure) { fatal(); }
                }
            } });
        } },1000);
    }
    private void catchupOne() throws IOException {
        if (unavailable || queue.automaticRevision() == null || catchup == null || catchup.isEmpty()) return;
        if (queue.paused() || pauseRequested.get()) { catchupDeferred = true; return; }
        String revision = queue.automaticRevision(), id = catchup.peek();
        if (queue.contains(id,revision)) { catchup.remove(); return; }
        if (queue.full()) retireHistory();
        if (queue.full()) { capacityWait(); catchupDeferred=true; return; }
        SpoolAdmission.Reservation reservation;
        try { reservation=SpoolAdmission.beginMaintenance(); }
        catch(IOException e) { catchupDeferred=true; return; }
        try {
            if(reservation.owner!=null) { catchupDeferred=true; return; }
            final long deadline=SystemClock.elapsedRealtime()+10000;
            try(DirectorySpool spool=new DirectorySpool(spoolRoot(),new CachePolicy(RecordingSettings.defaults().cacheBytes,RecordingSettings.FREE_RESERVE),new AndroidDirectoryProtocol(),new DirectorySpool.Faults() { @Override public void before(DirectorySpool.Operation op,Path path) { } },new RetirementStore(context))) {
                spool.confirmReady(id,new VerifiedExport.Cancellation() { @Override public boolean cancelled() { return SystemClock.elapsedRealtime()>=deadline || pauseRequested.get(); } });
            }
        } finally { reservation.close(); }
        queue.enqueue(catalog.load(id),revision,true); catchup.remove();
    }
    void captureClosed() {
        if (!catchupPosted.compareAndSet(false,true)) return;
        worker.execute(new Runnable() { @Override public void run() {
            catchupPosted.set(false); catchupDeferred = false; postCatchup(); if(!unavailable) schedule(nextDelay());
        } });
    }
    void published(final SegmentStore.Published published) {
        // Never block the capture writer or build an unbounded executor backlog.
        if (!notifications.tryAcquire()) { overflow.set(true); return; }
        worker.execute(new Runnable() { @Override public void run() {
            try {
                if (unavailable) return;
                if (overflow.getAndSet(false)) automaticBlocked("Finalize notification bound reached");
                String revision = queue.automaticRevision();
                if (revision != null) {
                    CommittedSegments.Entry entry = catalog.load(published.localObjectId);
                    if (!Arrays.equals(entry.metadata.encode(),published.metadata.encode())) throw new IOException("Published identity changed");
                    if(queue.full())retireHistory();
                    if(queue.full()){capacityWait();publish(queue.automaticNotice(),false);schedule(nextDelay());return;}
                    queue.enqueue(entry,revision,true); publish("Finalized chunk enrolled automatically; transfer policy and hard gates still apply.",false); schedule(nextDelay());
                }
            } catch (IOException | RuntimeException e) {
                try { automaticBlocked("Automatic enrollment failed or queue history is full"); publish("Automatic enrollment stopped safely",false); }
                catch (RuntimeException failure) { fatal(); }
            } finally { notifications.release(); }
        } });
    }
    private FtpsCoordinator(Context context) {
        this.context = context;
        worker.execute(new Runnable() { @Override public void run() {
            try {
                queue = new FtpsQueue(FtpsCoordinator.this.context); queue.getWritableDatabase(); queue.recover();
                catalog = new CommittedSegments(FtpsCoordinator.this.context.getFilesDir().toPath().toRealPath().resolve("audio-spool"));
                replayCleanup(); retireHistory();
                try { if(queue.automaticCapacityAt()==0){prepareCatchup(); postCatchup();} }
                catch (IOException e) { automaticBlocked("Discovery failed"); }
                publish("Transfer state restored. Review automatic enrollment status below.",false);
                schedule(nextDelay()); // Repairs a crash after queue commit but before scheduler submission.
            } catch (IOException | RuntimeException e) { fatal(); }
        } });
    }
    Snapshot snapshot() { return snapshot; }
    boolean commandPending() { return commandPending.get() || pauseWriteQueued.get() || activeJob != null; }
    private void fatal() {
        unavailable = true;
        snapshot = new Snapshot(page,null,new ArrayList<FtpsQueue.Item>(),new HashMap<String,String>(),
            "Local FTPS journal unavailable. Transfers are blocked; uncertain sources retained. No durability claim is made.",false,true);
    }
    private void publish(String notice, boolean busy) {
        if (unavailable) return;
        List<FtpsQueue.Item> items = queue.items(false); Map<String,String> destinations = new HashMap<String,String>();
        for (FtpsQueue.Item item : items) if (!destinations.containsKey(item.revision)) {
            FtpsQueue.Profile p = queue.profile(item.revision); destinations.put(item.revision,p == null ? "Missing profile" : p.label());
        }
        Snapshot updated = new Snapshot(page,queue.current(),items,destinations,notice,busy,queue.paused());
        updated.automaticRevision = queue.automaticRevision(); updated.automaticNotice = queue.automaticNotice();
        if (updated.automaticRevision != null) {
            FtpsQueue.Profile pinned = queue.profile(updated.automaticRevision);
            updated.automaticNotice += "\nPinned destination: " + (pinned == null ? "Unavailable" : pinned.label());
        }
        updated.cleanupRevision=queue.cleanupRevision();
        if(updated.cleanupRevision!=null) { FtpsQueue.Profile p=queue.profile(updated.cleanupRevision); updated.cleanupLabel="ON: " + (p==null ? "Unavailable" : p.label()); }
        updated.retiredCount=queue.retiredCount();updated.retiredForCurrent=updated.profile==null?"0":queue.retiredCount(updated.profile.revision);updated.retirementNotice=retirementNotice;
        snapshot = updated;
    }
    private interface Command { void run() throws Exception; default void discard() { } }
    private boolean command(final Command action) {
        if (activeJob != null || !commandPending.compareAndSet(false,true)) return false;
        worker.execute(new Runnable() { @Override public void run() {
            try {
                if (unavailable) return;
                action.run(); if (activeJob == null) schedule(nextDelay());
            } catch (Exception e) {
                try { publish("Local transfer action failed (" + e.getClass().getSimpleName() + "). Uncertain sources retained.",false); }
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
    void disableCleanup() {
        cleanupRevoked.set(true);
        if(!cleanupDisableQueued.compareAndSet(false,true)) return;
        worker.execute(new Runnable() { @Override public void run() {
            try { if(!unavailable) { queue.revokeCleanup(); publish("New local cleanup disabled. Previously committed partial purge may finish; no deleted audio can be restored.",false); schedule(nextDelay()); } }
            catch(RuntimeException e) { fatal(); }
            finally { cleanupDisableQueued.set(false); }
        } });
    }
    void cleanupEnabled(final boolean enabled, final String revision) {
        command(new Command() { @Override public void run() {
            FtpsQueue.Profile p=queue.current();
            if(enabled && (p==null || !p.revision.equals(revision))) { publish("Destination changed; review cleanup consent again.",false); return; }
            queue.revokeCleanup(); queue.cleanupRevision(enabled ? revision : null); cleanupRevoked.set(!enabled);
            publish(enabled ? "Local cleanup enabled for future freshly verified transfers to this exact destination. Remote future loss remains possible."
                : "New local cleanup disabled. Already committed partial purge can finish on resume; uncertain files remain.",false);
        } });
    }
    private static final class CleanupDeferred extends IOException {
        private static final long serialVersionUID=1L;
        CleanupDeferred() { super("Cleanup waits for exclusive finalized-object admission"); }
    }
    private interface LocalAction { void run(DirectorySpool.Maintenance lease) throws IOException; }
    private void localStep(String source,boolean confirm,final LocalAction action) throws IOException {
        SpoolAdmission.Reservation reservation;
        try { reservation=SpoolAdmission.beginMaintenance(); } catch(IOException e) { throw new CleanupDeferred(); }
        DirectorySpool spool=reservation.owner;
        boolean idle=spool==null;
        try {
            if(idle) spool=new DirectorySpool(spoolRoot(),new CachePolicy(RecordingSettings.defaults().cacheBytes,RecordingSettings.FREE_RESERVE),new AndroidDirectoryProtocol(),new DirectorySpool.Faults() { @Override public void before(DirectorySpool.Operation op,Path path) { } },new RetirementStore(context));
            DirectorySpool.Maintenance acquired;
            try { acquired=spool.maintenance(source); } catch(DirectorySpool.MaintenanceBusy e) { throw new CleanupDeferred(); }
            try(DirectorySpool.Maintenance lease=acquired) {
                final long confirmationDeadline=SystemClock.elapsedRealtime()+10000;
                if(confirm) lease.confirm(new VerifiedExport.Cancellation() { @Override public boolean cancelled() { return pauseRequested.get() || cleanupRevoked.get() || SystemClock.elapsedRealtime()>=confirmationDeadline; } });
                action.run(lease);
            }
        } finally {
            try { if(idle && spool!=null) spool.close(); } finally { reservation.close(); }
        }
    }
    /** Bounded empty-marker/history compaction only. Never removes a WAV or its metadata. */
    private void retireHistory() {
        if(unavailable||retirementBlocked||queue.paused()||pauseRequested.get())return;
        SpoolAdmission.Reservation reservation;
        try{reservation=SpoolAdmission.beginMaintenance();}catch(IOException busy){return;}
        DirectorySpool spool=reservation.owner;boolean idle=spool==null;
        try {
            if(idle)spool=new DirectorySpool(spoolRoot(),new CachePolicy(RecordingSettings.defaults().cacheBytes,RecordingSettings.FREE_RESERVE),new AndroidDirectoryProtocol(),new DirectorySpool.Faults(){@Override public void before(DirectorySpool.Operation op,Path path){}},new RetirementStore(context));
            Map<String,FtpsQueue.Item> pending=RetirementStore.pending(queue);
            List<FtpsQueue.Item> work=new ArrayList<FtpsQueue.Item>(pending.values());
            for(FtpsQueue.Item candidate:queue.retirementCandidates())if(!pending.containsKey(candidate.source)&&work.size()<FtpsQueue.MAX_RETIRING){work.add(candidate);pending.put(candidate.source,candidate);}
            int handled=0;
            for(FtpsQueue.Item item:work) {
                if(handled++>=FtpsQueue.MAX_RETIRING||pauseRequested.get())break;
                VerifiedLocalCleanup.Prepared binding=VerifiedLocalCleanup.decode(item.cleanupBinding,new AndroidCleanupIdentity());
                if(!binding.source.equals(item.source))throw new IOException("retirement source binding mismatch");
                long generation=item.retirementGeneration;
                if(generation<0) {
                    // Validate original empty inode before creating durable dedup/floor intent.
                    try(DirectorySpool.Maintenance proof=spool.maintenance(item.source)) {
                        proof.verifyRetired(binding);
                        generation=queue.beginRetirement(item);
                        if(generation>=0)RetirementStore.synchronize(queue,spoolRoot(),new AndroidDirectoryProtocol());
                    }
                    if(generation<0)continue;
                }
                io.github.headmaster218.recorder.core.SegmentIdentity id=io.github.headmaster218.recorder.core.SegmentIdentity.parse(item.source);
                Long floor=queue.floors().get(id.capture);if(floor==null)throw new IOException("retirement floor missing");
                try(DirectorySpool.Maintenance lease=spool.retirement(item.source,floor)) {
                    RetirementStore.synchronize(queue,spoolRoot(),new AndroidDirectoryProtocol());
                    lease.pruneRetired(binding,true);
                    queue.finishRetirement(item.source,generation);
                }
            }
        } catch(DirectorySpool.MaintenanceBusy busy) {
            // A closing capture still owns the lock; the retained rows resume on the next owner.
        } catch(IOException|RuntimeException failed) {
            retirementBlocked=true;retirementNotice="History retirement stopped safely ("+failed.getClass().getSimpleName()+"). Detailed receipts/uncertain markers retained; inspect before retrying.";
        } finally {
            try{if(idle&&spool!=null)spool.close();}catch(IOException|RuntimeException failed){retirementBlocked=true;retirementNotice="Retirement storage close failed; inspection required.";}
            finally{reservation.close();}
        }
    }
    private static boolean cleanupPending(FtpsQueue.Item item) {
        return item.cleanupState.equals("RESTORE_ONLY") || item.cleanupState.equals("INTENT") || item.cleanupState.equals("QUARANTINED") || item.cleanupState.equals("PURGING");
    }
    private void replayCleanup() {
        if(queue.paused() || pauseRequested.get() || ExportCoordinator.cleanupBlocked()) return;
        int handled=0;
        for(final FtpsQueue.Item item:queue.items(false)) {
            if(!cleanupPending(item) || handled++>=8) continue;
            try {
                if(!item.state.equals("VERIFIED_AT_TIME") || item.verifiedAt<=0 || item.resultMetadataHash==null || item.markerHash==null) throw new IOException("Missing durable verification receipt");
                final VerifiedLocalCleanup.Prepared binding=VerifiedLocalCleanup.decode(item.cleanupBinding,new AndroidCleanupIdentity());
                if(!item.source.equals(binding.source)) throw new IOException("Cleanup source mismatch");
                final boolean purge=item.cleanupState.equals("PURGING");
                localStep(item.source,false,new LocalAction() { @Override public void run(DirectorySpool.Maintenance lease) throws IOException {
                    if(pauseRequested.get()) throw new IOException("Cleanup paused");
                    if(purge) lease.purge(binding); else lease.restore(binding);
                } });
                queue.cleanup(item,purge ? "REMOVED" : (!item.cleanupState.equals("RESTORE_ONLY") && !cleanupRevoked.get() && item.revision.equals(queue.cleanupRevision()) ? "DEFERRED" : "RESTORED"),item.cleanupBinding);
            } catch(IOException | RuntimeException e) {
                try { if(!pauseRequested.get() && !(e instanceof CleanupDeferred)) queue.cleanup(item,"BLOCKED",item.cleanupBinding); }
                catch(IOException | RuntimeException failed) { fatal(); return; }
            }
        }
    }
    private void cleanupFresh(final FtpsQueue.Item item, final CommittedSegments.Entry entry, final Job job) {
        if(!item.revision.equals(queue.cleanupRevision()) || pauseRequested.get() || cleanupRevoked.get()) return;
        if(queue.otherNeedsSource(item)) { try { queue.cleanup(item,"WAIT_SOURCE",null); } catch(IOException | RuntimeException e) { fatal(); } return; }
        if(ExportCoordinator.cleanupBlocked()) {
            try { queue.cleanup(item,"DEFERRED",null); } catch(IOException | RuntimeException e) { fatal(); }
            return;
        }
        byte[] encoded=null; String stage="NONE"; final boolean[] purgeEntered={false},quarantineEntered={false};
        try {
            final long freshness=SystemClock.elapsedRealtime()+30000;
            final VerifiedLocalCleanup.Prepared binding=VerifiedLocalCleanup.prepare(catalog,entry,new VerifiedExport.Cancellation() { @Override public boolean cancelled() { return job.cancelled.get() || pauseRequested.get() || SystemClock.elapsedRealtime()>=Math.min(freshness,job.deadline); } },new AndroidCleanupIdentity());
            encoded=binding.encode(); job.check();
            if(cleanupRevoked.get()) throw new IOException("Cleanup revoked");
            queue.cleanup(item,"INTENT",encoded); stage="INTENT";
            localStep(item.source,true,new LocalAction() { @Override public void run(DirectorySpool.Maintenance lease) throws IOException {
                job.check(); if(cleanupRevoked.get() || pauseRequested.get() || SystemClock.elapsedRealtime()>=freshness) throw new IOException("Fresh cleanup consent window expired");
                quarantineEntered[0]=true; lease.quarantine(binding);
            } });
            queue.cleanup(item,"QUARANTINED",encoded); stage="QUARANTINED";
            job.check(); if(cleanupRevoked.get() || pauseRequested.get() || SystemClock.elapsedRealtime()>=freshness || !item.revision.equals(queue.cleanupRevision())) throw new IOException("Cleanup stopped before purge authorization");
            queue.cleanup(item,"PURGING",encoded); stage="PURGING";
            localStep(item.source,false,new LocalAction() { @Override public void run(DirectorySpool.Maintenance lease) throws IOException {
                if(pauseRequested.get() || cleanupRevoked.get()) throw new IOException("Cleanup paused or revoked before purge");
                purgeEntered[0]=true;
                lease.purge(binding);
            } });
            queue.cleanup(item,"REMOVED",encoded);
        } catch(IOException | RuntimeException e) {
            // Durable intent records uncertainty. Pre-purge restart restores; partial purge replay
            // only removes the exact bound remaining objects. Never invent a new proof or attempt.
            // Confirmation/admission failed before any quarantine call: source is still intact.
            // Do not repeatedly download a remote copy for a permanently slow/invalid local source.
            if(stage.equals("INTENT") && !quarantineEntered[0] && !(e instanceof CleanupDeferred)) {
                try { queue.cleanup(item,"BLOCKED",encoded); queue.state(item,"VERIFIED_AT_TIME","Local confirmation failed before quarantine; source retained for inspection",0); }
                catch(IOException | RuntimeException failed) { fatal(); }
            }
            if(stage.equals("PURGING") && !purgeEntered[0]) {
                try { queue.cleanup(item,"QUARANTINED",encoded); } catch(IOException | RuntimeException failed) { fatal(); }
            }
            if(encoded==null || stage.equals("NONE")) {
                try { queue.cleanup(item,e instanceof CleanupDeferred ? "DEFERRED" : "BLOCKED",null); queue.state(item,"VERIFIED_AT_TIME","Local cleanup deferred or could not establish fresh, stable local identity; source retained",0); }
                catch(IOException | RuntimeException failed) { fatal(); }
            }
        }
    }
    void uploadNow() {
        command(new Command() { @Override public void run() {
            pauseRequested.set(false); queue.uploadNow(); catchupDeferred = false; postCatchup(); publish("Queued work requested. Charging, unmetered Wi-Fi, runtime permission and trusted TLS remain mandatory.",false);
        } });
    }
    void pause() {
        pauseRequested.set(true); Job running = activeJob; if (running != null) running.cancel();
        // Cancellation is immediate; the single journal owner commits pause after the owned I/O releases.
        if (!pauseWriteQueued.compareAndSet(false,true)) return;
        worker.execute(new Runnable() { @Override public void run() {
            try {
                if (!unavailable) { queue.pause(true); publish("Queue paused. Any started attempt can only be reconciled by readback; uncertain sources retained.",false); schedule(-1); }
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
                    runOne(job); retireHistory();
                    try{resumeCapacity();}catch(IOException e){automaticBlocked("Capacity discovery bounds or validation failed");}
                }
                if (!unavailable) {
                    if (pauseRequested.get()) queue.pause(true);
                    publish(queue.paused() ? "Queue paused; uncertain sources retained." : snapshot.notice,false);
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
    private boolean recheckDeferred(final Job job) {
        if(ExportCoordinator.cleanupBlocked() || cleanupRevoked.get()) return false;
        for(final FtpsQueue.Item item:queue.items(false)) {
            if(!item.state.equals("VERIFIED_AT_TIME") || !(item.cleanupState.equals("DEFERRED") || item.cleanupState.equals("RECHECKING") || item.cleanupState.equals("WAIT_SOURCE")) || !item.revision.equals(queue.cleanupRevision())) continue;
            if(item.nextAt>System.currentTimeMillis() || queue.otherNeedsSource(item)) continue;
            char[] secret=null;
            try {
                if(item.attempt==null || item.verifiedAt<=0) throw new IOException("Missing prior remote receipt");
                Network network;
                try { network=FtpsNetwork.allowed(context,item.bytes,0,true); }
                catch(IOException | RuntimeException unsafe) { queue.state(item,"VERIFIED_AT_TIME","Cleanup readback waits for transfer safety gates; source retained",System.currentTimeMillis()+300000); return true; }
                FtpsQueue.Profile profile=queue.profile(item.revision);
                try { secret=password(profile); } catch(Exception e) { queue.cleanup(item,"WAIT_CREDENTIALS",null); return true; }
                final CommittedSegments.Entry entry=catalog.load(item.source);
                if(entry.metadata.wavBytes!=item.bytes || !entry.metadata.wavSha256.equals(item.wavHash) || !FtpsQueue.digest(entry.metadata.encode()).equals(item.metadataHash)) throw new IOException("Source changed");
                queue.cleanup(item,"RECHECKING",null); // Read-only exact-attempt reconciliation, never a new upload.
                try(final FtpsNetwork connection=new FtpsNetwork(context,network)) {
                    job.connection=connection;
                    FtpsTransfer.Result result=FtpsTransfer.run(catalog,entry,profile.destination,secret,connection,item.revision,item.attempt,true,new FtpsTransfer.Guard() { @Override public void check() throws IOException { job.check(); connection.check(); } });
                    job.check(); queue.verified(item,result); cleanupFresh(item,entry,job);
                } finally { job.connection=null; }
            } catch(IOException | RuntimeException e) {
                try { queue.cleanup(item,"BLOCKED",null); } catch(IOException | RuntimeException failed) { fatal(); }
            } finally { if(secret!=null) Arrays.fill(secret,'\0'); }
            return true;
        }
        return false;
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
        // A persisted, bounded wake resumes deferred discovery without a fresh history scan.
        catchupDeferred=false; postCatchup();
        replayCleanup(); if (unavailable) return;
        if(recheckDeferred(job)) return;
        List<FtpsQueue.Item> items = queue.items(true); long total = pendingBytes(items), now = System.currentTimeMillis();
        FtpsQueue.Item selected = null;
        for (FtpsQueue.Item item : items) if (item.nextAt <= now && triggered(item,total,now)) { selected = item; break; }
        if (selected == null) return;
        final FtpsQueue.Item item = selected;
        char[] secret = null;
        try {
            final Network selectedNetwork;
            try { selectedNetwork = FtpsNetwork.allowed(context,total,Math.max(0,now - item.queuedAt),item.uploadNow || item.attempt != null); }
            catch (IOException | RuntimeException e) {
                // One durable deferral covers every runnable row, not one 1-second job per queued file.
                queue.deferForSafety(now + 300000);
                publish("Transfer safety gates are not satisfied. Upload now cannot bypass them.",false); return;
            }
            FtpsQueue.Profile profile = queue.profile(item.revision);
            if (profile == null) { queue.state(item,"BLOCKED","Saved destination is unavailable",0); return; }
            try { secret = password(profile); }
            catch (Exception e) {
                queue.state(item,"NEEDS_CREDENTIALS","Password unavailable or secure storage locked. Unlock device and explicitly save credentials for this exact profile again.",0);
                publish("Transfer blocked until credentials are supplied for the exact destination revision.",false); return;
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
                } else queue.state(item,"BLOCKED","Local source identity, metadata or content verification failed. Source mutation is blocked; uncertain sources retained.",0);
                return;
            }
            if (item.attempt != null && item.reconciliations >= FtpsQueue.MAX_RECONCILIATIONS) {
                queue.state(item,"BLOCKED","Readback retry limit reached. Remote attempt may be incomplete. No overwrite, replacement attempt or deletion is permitted in this version.",0); return;
            }
            String attempt = item.attempt == null ? FtpsTransfer.newAttemptName(item.source,item.wavHash,item.revision) : item.attempt;
            queue.begin(item,attempt); // Must commit successfully before creating any connection or issuing DNS.
            publish(item.attempt == null ? "Sending authorized queued WAV + technical metadata to " + profile.label()
                : "Read-only reconciliation of the exact prior remote attempt at " + profile.label(),true);
            try (FtpsNetwork connection = new FtpsNetwork(context,selectedNetwork)) {
                job.connection = connection; job.check();
                final FtpsNetwork guardedNetwork = connection;
                FtpsTransfer.Guard guard = new FtpsTransfer.Guard() { @Override public void check() throws IOException { job.check(); guardedNetwork.check(); } };
                FtpsTransfer.Result result = FtpsTransfer.run(catalog,entry,profile.destination,secret,connection,item.revision,attempt,item.attempt != null,guard);
                job.check(); queue.verified(item,result);
                cleanupFresh(item,entry,job);
                publish("VERIFIED AT TIME: remote readback matched. Review the local cleanup state in the receipt; remote future integrity remains unproven.",false);
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
                publish("Transfer was interrupted or unverified. Uncertain sources retained; any retry is bounded readback-only reconciliation.",false);
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
        for(FtpsQueue.Item item:queue.items(false)) if(cleanupPending(item)
            || (!cleanupRevoked.get() && item.revision.equals(queue.cleanupRevision()) && (item.cleanupState.equals("DEFERRED") || item.cleanupState.equals("RECHECKING") || (item.cleanupState.equals("WAIT_SOURCE") && !queue.otherNeedsSource(item)))))
            soonest=Math.min(soonest,Math.max(ExportCoordinator.cleanupBlocked() ? 300000 : 5000,item.nextAt-now));
        if(queue.automaticRevision()!=null&&queue.automaticCapacityAt()!=0)soonest=Math.min(soonest,Math.max(0,queue.automaticCapacityAt()-now));
        if(!retirementBlocked&&(!queue.retiring().isEmpty()||!queue.retirementCandidates().isEmpty()))soonest=Math.min(soonest,300000);
        // Persist a wake while catch-up remains, covering death between confirmations.
        if (queue.automaticRevision() != null && catchup != null && !catchup.isEmpty())
            soonest = Math.min(soonest,300000);
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
        JobInfo job = FtpsJobSchedule.build(JOB_ID,new ComponentName(context,FtpsJobService.class),delay);
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            Snapshot old = snapshot;
            snapshot = new Snapshot(old.page,old.profile,old.items,old.destinations,
                "Android did not schedule the transfer. Queue is retained; open this screen and request Upload queued now again.",old.busy,old.paused);
            snapshot.automaticRevision = old.automaticRevision; snapshot.automaticNotice = old.automaticNotice; snapshot.cleanupRevision=old.cleanupRevision; snapshot.cleanupLabel=old.cleanupLabel;
        }
    }
}
