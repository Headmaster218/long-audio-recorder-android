package io.github.headmaster218.recorder.android;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.UUID;

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
        channel.setDescription("Persistent recording controls and interruption notices"); notifications.createNotificationChannel(channel);
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
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (START.equals(action)) startRequested(intent);
        else if (STOP.equals(action) || PAUSE.equals(action)) {
            if (engine != null) {
                engine.requestStop(STOP.equals(action) ? CaptureEngine.Stop.STOP : CaptureEngine.Stop.PAUSE);
                RecorderState.status = "Stopping microphone and preserving queued PCM";
                if (notificationsVisible(this)) notifications.notify(NOTIFICATION,notification(RecorderState.status,true));
            } else {
                if (RecorderState.active) {
                    RecorderState.status = "Previous microphone run is still stopping; waiting for release";
                    stopSelf(); return START_NOT_STICKY;
                }
                if (STOP.equals(action)) RecorderState.finish(this,"Stopped. Local recordings are preserved.",false,"",0);
                stopSelf();
            }
        } else if (engine == null) stopSelf();
        return START_NOT_STICKY;
    }
    private void startRequested(Intent intent) {
        if (engine != null) return;
        if (RecorderState.active) { stopSelf(); return; }
        String capture = UUID.randomUUID().toString(); long epoch = 0; boolean resume = false;
        try {
            RecordingSettings settings = RecordingSettings.from(intent);
            if (!permissionsReady(this)) throw new SecurityException("Microphone permission and visible notifications are required");
            SharedPreferences p = RecorderState.prefs(this);
            if (p.getBoolean("paused",false) && !p.getString("capture","").isEmpty()) {
                capture = p.getString("capture",""); epoch = p.getLong("epoch",0); resume = true;
                if (epoch < 0 || epoch == Long.MAX_VALUE) throw new IllegalStateException("Resume epoch exhausted; tap Stop, then Start a new capture");
            }
            startForeground(NOTIFICATION,notification("Starting microphone; local recording only",true),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            foreground = true; notifications.cancel(RESULT_NOTIFICATION);
            RecorderState.starting(this,capture,epoch);
            engine = new CaptureEngine(getApplicationContext(),settings,capture,epoch,resume,new CaptureEngine.Completion() {
                @Override public void finished(final CaptureEngine completed,final String result,final boolean paused,final long nextEpoch) {
                    main.post(new Runnable() { @Override public void run() { RecordingService.this.finished(completed,result,paused,nextEpoch); } });
                }
            });
            new Thread(engine,"recorder-storage").start();
        } catch (RuntimeException e) {
            RecorderState.finish(this,"Recording did not start: " + e.getClass().getSimpleName() + ": " + e.getMessage(),false,capture,epoch);
            if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
            if (notificationsVisible(this)) notifications.notify(RESULT_NOTIFICATION,notification(RecorderState.status,false));
            stopSelf();
        }
    }
    private void finished(CaptureEngine completed,String result,boolean paused,long nextEpoch) {
        if (engine != completed) return;
        engine = null; RecorderState.finish(this,result,paused,completed.captureId,nextEpoch);
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
        if (!destroyed && notificationsVisible(this)) notifications.notify(RESULT_NOTIFICATION,notification(result,false));
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
    @Override public void onDestroy() { destroyed = true; if (engine != null) engine.requestStop(CaptureEngine.Stop.INTERRUPTED); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
