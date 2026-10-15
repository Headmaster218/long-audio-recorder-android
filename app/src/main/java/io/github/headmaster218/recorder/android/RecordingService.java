package io.github.headmaster218.recorder.android;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.UUID;
import io.github.headmaster218.recorder.core.CleanPauseGate;
import io.github.headmaster218.recorder.core.CaptureEvidence;
import io.github.headmaster218.recorder.core.InterruptionRecovery;
import io.github.headmaster218.recorder.core.PcmReadAccounting;
import io.github.headmaster218.recorder.core.RecoveryGate;
import io.github.headmaster218.recorder.core.RecoveryJournal;

/** Explicit visible starts only. No boot receiver, sticky restart, background permission or upload work. */
public final class RecordingService extends Service {
    static final String START = "recorder.START", PAUSE = "recorder.PAUSE", STOP = "recorder.STOP";
    private static final String CHANNEL = "visible-recording";
    private static final int NOTIFICATION = 10, RESULT_NOTIFICATION = 11;
    private final Handler main = new Handler(Looper.getMainLooper());
    private CaptureEngine engine;
    private boolean foreground, destroyed;
    private NotificationManager notifications;
    @Override public void onCreate() {
        super.onCreate(); notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL,"Visible microphone recording",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Recording notification controls and interruption notices"); notifications.createNotificationChannel(channel);
    }
    static boolean notificationsVisible(Context c) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager n = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = n.getNotificationChannel(CHANNEL);
        return n.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }
    static boolean permissionsReady(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && notificationsVisible(c);
    }
    // Main-looper confined. Retained across service recreation until physical/storage/evidence gates clear.
    // Process death relies on Android resource cleanup; this is not a cross-process lease or restart token.
    private static Session heldSession;
    private static final class Session {
        final String id = UUID.randomUUID().toString(), captureId;
        final RecordingSettings settings;
        final InterruptionRecovery policy = new InterruptionRecovery(8,1000,30000);
        InterruptionRecovery.Lease lease;
        CaptureEngine engine;
        CaptureEvidence.Result lastEvidence, pendingOutcome;
        CaptureEvidence.FirstPcm first;
        long nextEpoch;
        int ordinal;
        boolean resumed, writing, blocked, journalFailed, userPaused, historicalUncertainty, cleanPauseEligible;
        String status = "Starting visible microphone service";
        Runnable timer;
        Session(RecordingSettings settings,String captureId,long epoch,boolean resumed) {
            this.settings = settings; this.captureId = captureId; nextEpoch = epoch; this.resumed = resumed;
        }
    }
    private Session session;
    private boolean authority() { return !destroyed && foreground && permissionsReady(this); }
    private static long now() { return android.os.SystemClock.elapsedRealtime(); }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (START.equals(action)) startRequested(intent);
        else if (STOP.equals(action) || PAUSE.equals(action)) {
            Session s = session;
            if (s != null && heldSession == s) {
                s.userPaused = PAUSE.equals(action);
                if (s.userPaused) s.policy.pause(now()); else s.policy.stop(now());
                cancelTimer(s);
                if (s.engine != null) s.engine.requestStop(s.userPaused ? CaptureEngine.Stop.PAUSE : CaptureEngine.Stop.STOP);
                s.status = "Stopping microphone and preserving queued PCM"; show(s);
                pump(s);
            } else {
                if (heldSession != null) RecorderState.status = "Previous session has unresolved ownership, storage or gap evidence; no new recorder started.";
                else if (STOP.equals(action)) RecorderState.finish(this,"Stopped. Local recordings are preserved.",false,"",0);
                stopSelf();
            }
        } else if (session == null) stopSelf();
        return START_NOT_STICKY;
    }
    private void startRequested(Intent intent) {
        if (heldSession != null) {
            RecorderState.status = "A recording session is active or awaiting safe cleanup; Start did not create another recorder.";
            if (session == null) stopSelf();
            return;
        }
        String capture = UUID.randomUUID().toString(); long epoch = 0; boolean resume = false;
        try {
            RecordingSettings settings = RecordingSettings.from(intent);
            if (!permissionsReady(this)) throw new SecurityException("Microphone permission and visible notifications are required");
            CleanPauseGate.Resume cleanPause = RecorderState.takeCleanPause();
            if (cleanPause != null) {
                capture = cleanPause.captureId; epoch = cleanPause.nextEpoch; resume = true;
                if (epoch < 0 || epoch == Long.MAX_VALUE) throw new IllegalStateException("Epoch exhausted; tap Stop, then Start a new capture");
            }
            startForeground(NOTIFICATION,notification("Starting microphone; local recording only",true),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            foreground = true; notifications.cancel(RESULT_NOTIFICATION);
            Session s = new Session(settings,capture,epoch,resume);
            s.lease = s.policy.explicitStart(intent.getBooleanExtra("recovery_opt_in",false),authority(),now());
            if (s.lease == null) throw new IllegalStateException("Session start was not admitted");
            session = s; heldSession = s;
            RecorderState.starting(this,capture,epoch);
            launch(s,s.lease);
        } catch (RuntimeException e) {
            if (session != null) { session.blocked = true; session.policy.stop(now()); cancelTimer(session);
                if (session.engine != null) session.engine.requestStop(CaptureEngine.Stop.INTERRUPTED);
                session.status = "Start failed; session ownership needs inspection: " + e.getClass().getSimpleName(); show(session); pump(session); return; }
            RecorderState.finish(this,"Recording did not start: " + e.getClass().getSimpleName() + ": " + e.getMessage(),false,capture,epoch);
            if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
            stopSelf();
        }
    }
    private void launch(final Session s,final InterruptionRecovery.Lease lease) {
        if (session != s || heldSession != s || s.engine != null || s.blocked || s.policy.snapshot().lease != lease || !authority()) {
            s.policy.authorityLost(now()); s.policy.released(lease); pump(s); return;
        }
        final CaptureEngine created = new CaptureEngine(getApplicationContext(),s.settings,s.captureId,s.nextEpoch,s.resumed,new CaptureEngine.Completion() {
            @Override public void finished(CaptureEngine ignored,String result,boolean paused,long nextEpoch) {
                // This adapter always receives the typed overload; the legacy callback grants no ownership evidence.
                throw new IllegalStateException("Typed completion evidence required");
            }
            @Override public void firstPcm(final CaptureEngine engine,final CaptureEvidence.FirstPcm first) {
                main.post(new Runnable() { @Override public void run() {
                    if (session != s || heldSession != s || s.engine != engine || s.lease != lease || destroyed
                        || !engine.captureId.equals(first.captureId) || !engine.runId.equals(first.runId)) return;
                    if (!authority()) { loseAuthority(s); return; }
                    if (s.policy.captureConfirmed(lease,now())) { s.first = first; s.status = "Recording visibly; recovery boundaries remain uncertain"; show(s); pump(s); }
                } });
            }
            @Override public void finished(final CaptureEngine engine,final CaptureEvidence.Result evidence,final String result,final boolean paused,final long nextEpoch) {
                main.post(new Runnable() { @Override public void run() { completed(s,lease,engine,evidence,result,paused,nextEpoch); } });
            }
        });
        s.engine = created; engine = created;
        try { new Thread(created,"recorder-storage").start(); }
        catch (RuntimeException failure) {
            // Fresh thread did not start: no engine run/producer/AudioRecord was invoked.
            CaptureEvidence evidence = new CaptureEvidence(created.captureId,created.runId);
            evidence.failed(CaptureEvidence.Failure.INITIALIZATION);
            completed(s,lease,created,evidence.finish(new PcmReadAccounting().snapshot(),true),"Storage worker could not start",false,s.nextEpoch);
        }
    }
    private void completed(Session s,InterruptionRecovery.Lease lease,CaptureEngine completed,CaptureEvidence.Result result,String text,boolean paused,long nextEpoch) {
        if (heldSession != s || s.engine != completed || s.lease != lease
            || !completed.captureId.equals(result.captureId) || !completed.runId.equals(result.runId)) return;
        s.cleanPauseEligible = RecoveryGate.cleanPauseEligible(result,paused);
        s.lastEvidence = result; s.pendingOutcome = result; s.first = null; s.nextEpoch = nextEpoch;
        s.status = result.pcm.warning() + text;
        InterruptionRecovery.Snapshot state = s.policy.snapshot();
        if (state.state == InterruptionRecovery.State.STARTING || state.state == InterruptionRecovery.State.RUNNING) {
            InterruptionRecovery.Reason reason = RecoveryGate.retryReason(result);
            s.policy.interrupted(lease,reason == null ? InterruptionRecovery.Reason.UNKNOWN_FAILURE : reason,now());
        }
        if (RecoveryGate.ownershipReleased(result)) { s.policy.released(lease); s.engine = null; engine = null; }
        else { s.blocked = true; s.status = "Physical microphone release is unconfirmed; no replacement recorder allowed. " + s.status;
            if (result.producerTerminated) { s.engine = null; engine = null; } // Keep the process admission barrier, not a dead worker reference.
        }
        if (!RecoveryGate.storageClosed(result)) { s.blocked = true; s.status = "Storage closure is unconfirmed; files retained and restart blocked. " + s.status; }
        if (s.blocked || nextEpoch == Long.MAX_VALUE) { s.policy.stop(now());
            if (nextEpoch == Long.MAX_VALUE) { s.userPaused = false; s.status = "Capture epoch exhausted; explicit Start must create a new capture. " + s.status; }
        }
        if (destroyed || !authority()) s.policy.authorityLost(now());
        s.userPaused = s.userPaused && paused;
        show(s); pump(s);
    }
    private void cancelTimer(Session s) { if (s.timer != null) { main.removeCallbacks(s.timer); s.timer = null; } }
    private void loseAuthority(Session s) {
        s.policy.authorityLost(now()); cancelTimer(s);
        if (s.engine != null) s.engine.requestStop(CaptureEngine.Stop.INTERRUPTED);
        s.status = "Recovery stopped: foreground microphone authority or notification permission is unavailable.";
        show(s); pump(s);
    }
    /** One bounded journal operation outstanding; admission waits for its exact completion. */
    private void pump(final Session s) {
        if (heldSession != s || s.writing) return;
        if (s.journalFailed) { terminal(s); return; }
        final CaptureEvidence.Result outcome = s.pendingOutcome;
        final InterruptionRecovery.GapEvent event = outcome == null ? s.policy.peekGap() : null;
        if (outcome != null || event != null) {
            final String record = RecoveryJournal.encode(s.id,event,outcome == null ? s.lastEvidence : outcome,s.nextEpoch,s.first);
            final int ordinal = s.ordinal; s.writing = true;
            final RecoveryJournal journal = new RecoveryJournal(getFilesDir().toPath().resolve("recovery-gaps"),new AndroidDirectoryProtocol(),
                new RecoveryJournal.Faults() { @Override public void before(RecoveryJournal.Step step) { } });
            try { new Thread(new Runnable() { @Override public void run() {
                String error = null; boolean historical = false;
                try { historical = journal.persist(s.id,ordinal,record); } catch (Exception failure) { error = failure.getClass().getSimpleName() + ": " + failure.getMessage(); }
                final String failure = error; final boolean historicalWarning = historical;
                main.post(new Runnable() { @Override public void run() {
                    if (heldSession != s) return;
                    s.writing = false;
                    if (failure != null) { journalFailed(s,failure); return; }
                    s.historicalUncertainty |= historicalWarning;
                    s.ordinal++;
                    if (outcome != null && s.pendingOutcome == outcome) s.pendingOutcome = null;
                    if (event != null && !s.policy.acknowledgeGap(event)) { journalFailed(s,"Gap acknowledgement identity mismatch"); return; }
                    pump(s);
                } });
            } },"recorder-gap-journal").start(); }
            catch (RuntimeException failure) { s.writing = false; journalFailed(s,"Journal worker could not start"); }
            return;
        }
        if (s.engine != null) return;
        InterruptionRecovery.Snapshot state = s.policy.snapshot();
        if (!s.blocked && state.state == InterruptionRecovery.State.BACKOFF && state.retry != null) {
            if (!authority()) { loseAuthority(s); return; }
            if (s.timer != null) return;
            final InterruptionRecovery.Retry retry = state.retry;
            s.status = "Microphone interrupted. Waiting for bounded retry; prior PCM may have an uncertain gap. Stop/Pause cancels."; show(s);
            s.timer = new Runnable() { @Override public void run() {
                s.timer = null;
                if (session != s || heldSession != s || s.blocked || s.writing || s.pendingOutcome != null || s.policy.peekGap() != null) return;
                InterruptionRecovery.Lease next = s.policy.takeRetry(retry,authority(),now());
                if (next == null) { pump(s); return; }
                s.lease = next; s.resumed = true; launch(s,next);
            } };
            main.postDelayed(s.timer,Math.max(0,retry.dueMillis-now())); return;
        }
        terminal(s);
    }
    private void journalFailed(Session s,String error) {
        s.journalFailed = true; s.blocked = true; s.policy.stop(now()); cancelTimer(s);
        s.status = "Gap evidence could not be durably saved; recovery stopped and existing files retained. " + RecorderState.bounded(error);
        if (s.engine != null) s.engine.requestStop(CaptureEngine.Stop.INTERRUPTED);
        show(s); terminal(s);
    }
    private void show(Session s) {
        RecorderState.status = RecorderState.bounded((s.historicalUncertainty ? "Historical partial/unknown gap records remain; past gap coverage is incomplete. " : "") + s.status);
        if (!destroyed && foreground && notificationsVisible(this)) notifications.notify(NOTIFICATION,notification(RecorderState.status,true));
    }
    private void terminal(Session s) {
        if (s.engine != null || s.writing) return;
        cancelTimer(s);
        if (!s.blocked && !s.journalFailed && s.pendingOutcome == null && s.policy.peekGap() == null) {
            heldSession = null; session = null;
            RecorderState.finish(this,(s.historicalUncertainty ? "Historical partial/unknown gap records remain; past gap coverage is incomplete. " : "") + s.status,s.userPaused && s.cleanPauseEligible,s.captureId,s.nextEpoch);
        } else {
            RecorderState.active = true; // Admission blocked, not a claim that the microphone is recording.
            RecorderState.status = RecorderState.bounded((s.historicalUncertainty ? "Historical partial/unknown gap records remain; past gap coverage is incomplete. " : "") + s.status);
        }
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
        if (!destroyed && notificationsVisible(this)) notifications.notify(RESULT_NOTIFICATION,notification(s.status,false));
        stopSelf();
    }
    private Notification notification(String message,boolean ongoing) {
        PendingIntent open = PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(ongoing ? "Microphone recording service" : "Recorder status").setContentText(message)
            .setStyle(new Notification.BigTextStyle().bigText(message)).setContentIntent(open).setOngoing(ongoing)
            .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_SERVICE);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (ongoing) {
            PendingIntent pause = PendingIntent.getService(this,2,new Intent(this,RecordingService.class).setAction(PAUSE),PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop = PendingIntent.getService(this,3,new Intent(this,RecordingService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null,"Pause",pause).build());
            b.addAction(new Notification.Action.Builder(null,"Stop",stop).build());
        } else b.setAutoCancel(true);
        return b.build();
    }
    @Override public void onDestroy() { destroyed = true; if (session != null && heldSession == session) { cancelTimer(session); session.policy.authorityLost(now()); if (session.engine != null) session.engine.requestStop(CaptureEngine.Stop.INTERRUPTED); pump(session); } super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
