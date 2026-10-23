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
import io.github.headmaster218.recorder.core.RecoveryJournal;
import io.github.headmaster218.recorder.core.RecoverySession;
import io.github.headmaster218.recorder.core.NotificationBoundary;

/** Explicit visible starts only. No boot receiver, sticky restart, background permission or upload work. */
public final class RecordingService extends Service {
    static final String START = "recorder.START", PAUSE = "recorder.PAUSE", STOP = "recorder.STOP";
    private static final String CHANNEL = "visible-recording";
    private static final int NOTIFICATION = 10, RESULT_NOTIFICATION = 11;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean foreground, destroyed, notificationFailed;
    private NotificationManager notifications;
    @Override public void onCreate() {
        super.onCreate();
        try {
            notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL,"Visible microphone recording",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Recording notification controls and interruption notices"); notifications.createNotificationChannel(channel);
        } catch (RuntimeException notificationFailure) {
            notificationFailed = true;
            RecorderState.status = "Notification setup is unavailable; microphone recording is not authorized.";
        }
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
    // Main-looper confined. The process admission slot outlives a destroyed service object.
    // It is not persisted and does not grant authority after process death or force-stop.
    private static RecoverySession heldSession;
    private RecoverySession session;
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (START.equals(action)) startRequested(intent);
        else if (STOP.equals(action) || PAUSE.equals(action)) {
            if (session != null && heldSession == session) {
                if (PAUSE.equals(action)) session.pause(); else session.stop();
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
            final RecordingSettings settings = RecordingSettings.from(intent);
            if (notificationFailed || !permissionsReady(this)) throw new SecurityException("Microphone permission and visible notifications are required");
            CleanPauseGate.Resume cleanPause = RecorderState.takeCleanPause();
            if (cleanPause != null) {
                capture = cleanPause.captureId; epoch = cleanPause.nextEpoch; resume = true;
                if (epoch < 0 || epoch == Long.MAX_VALUE) throw new IllegalStateException("Epoch exhausted; tap Stop, then Start a new capture");
            }
            startForeground(NOTIFICATION,notification("Starting microphone; local recording only",true),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            foreground = true; notifications.cancel(RESULT_NOTIFICATION);
            final String captureId = capture;
            final RecoverySession[] binding = new RecoverySession[1];
            RecoverySession created = new RecoverySession(capture,epoch,resume,new RecoverySession.Host() {
                @Override public long nowMillis() { return android.os.SystemClock.elapsedRealtime(); }
                @Override public boolean authorized() {
                    return session == binding[0] && heldSession == binding[0] && !destroyed && foreground && !notificationFailed && permissionsReady(RecordingService.this);
                }
                @Override public void post(Runnable work) { main.post(work); }
                @Override public void postDelayed(Runnable work,long delay) { main.postDelayed(work,delay); }
                @Override public void remove(Runnable work) { main.removeCallbacks(work); }
                @Override public void status(String text) {
                    if (heldSession != binding[0]) return;
                    RecorderState.status = notificationFailed ? "Notification delivery is unavailable. " + text : text;
                    if (!destroyed && foreground && !notificationFailed && !NotificationBoundary.publish(publication(text,true))) {
                        // Latch before posting: later successful queries cannot revive this session.
                        notificationFailed = true;
                        RecorderState.status = "Notification delivery is unavailable; stopping microphone safely. " + text;
                        main.post(new Runnable() { @Override public void run() {
                            if (session == binding[0] && heldSession == binding[0]) binding[0].authorityUnavailable();
                        } });
                    }
                }
                @Override public void finished(String text,boolean paused,long nextEpoch,boolean admissionReleased) {
                    if (heldSession != binding[0]) return;
                    try {
                        if (admissionReleased) {
                            heldSession = null;
                            if (session == binding[0]) session = null;
                            RecorderState.finish(RecordingService.this,text,paused && !notificationFailed,captureId,nextEpoch);
                        } else {
                            RecorderState.active = true; // Admission blocked, not a claim that microphone capture is active.
                            RecorderState.status = text;
                        }
                    } finally {
                        finishNotifications(text);
                    }
                }
            },new RecoverySession.Engines() {
                @Override public RecoverySession.Attempt create(String captureId,long nextEpoch,boolean resumed,RecoverySession.Events events) {
                    return new AndroidAttempt(settings,captureId,nextEpoch,resumed,events);
                }
            },new RecoverySession.Journal() {
                @Override public void persist(final String sessionId,final int ordinal,final String record,final RecoverySession.JournalCompletion completion) {
                    final RecoveryJournal journal = new RecoveryJournal(getFilesDir().toPath().resolve("recovery-gaps"),new AndroidDirectoryProtocol(),
                        new RecoveryJournal.Faults() { @Override public void before(RecoveryJournal.Step step) { } });
                    new Thread(new Runnable() { @Override public void run() {
                        String error = null; boolean historical = false;
                        try { historical = journal.persist(sessionId,ordinal,record); }
                        catch (Exception failure) { error = failure.getClass().getSimpleName() + ": " + failure.getMessage(); }
                        completion.complete(historical,error);
                    } },"recorder-gap-journal").start();
                }
            });
            binding[0] = created; session = created; heldSession = created;
            RecorderState.starting(this,capture,epoch);
            if (!created.start(intent.getBooleanExtra("recovery_opt_in",false))) {
                // No engine was admitted by the new session.
                heldSession = null; session = null;
                throw new IllegalStateException("Session start was not admitted");
            }
        } catch (RuntimeException e) {
            if (session != null && heldSession == session) {
                if (!session.abortBeforeStart()) {
                    session.stop(); RecorderState.status = "Start failed; session cleanup needs inspection: " + e.getClass().getSimpleName(); return;
                }
                // Proven pre-admission failure, including state persistence or authority lookup failure.
                heldSession = null; session = null;
            }
            try {
                RecorderState.finish(this,"Recording did not start: " + e.getClass().getSimpleName() + ": " + e.getMessage(),false,capture,epoch);
            } finally {
                finishNotifications(null);
            }
        }
    }
    private NotificationBoundary.Publication publication(final String text,final boolean ongoing) {
        return new NotificationBoundary.Publication() {
            @Override public boolean visible() { return !destroyed && notificationsVisible(RecordingService.this); }
            @Override public void publish() { notifications.notify(ongoing ? NOTIFICATION : RESULT_NOTIFICATION,notification(text,ongoing)); }
        };
    }
    private void finishNotifications(String text) {
        NotificationBoundary.finish(new Runnable() { @Override public void run() {
            // A thrown stopForeground is not proof of notification removal. Still attempt stopSelf.
            if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
        } },text == null ? null : publication(text,false),new Runnable() { @Override public void run() { stopSelf(); } });
    }
    /** Small platform attempt adapter; native capture ownership remains inside CaptureEngine. */
    private final class AndroidAttempt implements RecoverySession.Attempt {
        private final CaptureEngine engine;
        AndroidAttempt(RecordingSettings settings,String captureId,long epoch,boolean resumed,final RecoverySession.Events events) {
            engine = new CaptureEngine(getApplicationContext(),settings,captureId,epoch,resumed,new CaptureEngine.Completion() {
                @Override public void finished(CaptureEngine ignored,String text,boolean paused,long nextEpoch) {
                    throw new IllegalStateException("Typed completion evidence required");
                }
                @Override public void firstPcm(CaptureEngine completed,CaptureEvidence.FirstPcm first) {
                    if (completed == engine) events.firstPcm(AndroidAttempt.this,first);
                }
                @Override public void finished(CaptureEngine completed,CaptureEvidence.Result result,String text,boolean paused,long nextEpoch) {
                    if (completed == engine) events.finished(AndroidAttempt.this,result,text,paused,nextEpoch);
                }
            });
        }
        @Override public String captureId() { return engine.captureId; }
        @Override public String runId() { return engine.runId; }
        @Override public void start() { new Thread(engine,"recorder-storage").start(); }
        @Override public void stop(RecoverySession.Stop reason) {
            engine.requestStop(reason == RecoverySession.Stop.STOP ? CaptureEngine.Stop.STOP
                : reason == RecoverySession.Stop.PAUSE ? CaptureEngine.Stop.PAUSE : CaptureEngine.Stop.INTERRUPTED);
        }
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
    @Override public void onDestroy() { destroyed = true; if (session != null && heldSession == session) session.destroy(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
