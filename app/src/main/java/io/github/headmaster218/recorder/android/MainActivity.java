package io.github.headmaster218.recorder.android;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText rate, channels, seconds, cache;
    private TextView status;
    private Button start, pause, stop;
    private boolean resumed, pendingPermissionStart;
    private RecordingSettings pending;
    private String localError;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            status.setText(localError != null && !RecorderState.active ? localError : RecorderState.display(MainActivity.this));
            boolean running = RecorderState.active;
            start.setEnabled(!running); pause.setEnabled(running);
            rate.setEnabled(!running); channels.setEnabled(!running); seconds.setEnabled(!running); cache.setEnabled(!running);
            main.postDelayed(this,1000);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this); LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL); int pad = (int) (20 * getResources().getDisplayMetrics().density); column.setPadding(pad,pad,pad,pad);
        scroll.addView(column); setContentView(scroll);
        text(column,"Recorder · development build",24);
        text(column,"Visible phone microphone recording. PCM16 WAV, local storage only. No uploads or automatic deletion. Screen-off and long-run behavior are unverified.",16);
        rate = number(column,"Sample rate (Hz)","16000",101);
        channels = number(column,"Channels: 1 mono or 2 stereo","1",102);
        seconds = number(column,"Segment duration (seconds)","300",103);
        cache = number(column,"Spool maximum (MiB); 32 MiB free-space reserve","512",104);
        text(column,"Requested input: built-in phone microphone. Hardware format may differ from the requested client PCM; both are reported. Unsupported client settings stop visibly.",15);
        start = button(column,"Start / resume",new View.OnClickListener() { @Override public void onClick(View v) { requestStart(); } });
        pause = button(column,"Pause",new View.OnClickListener() { @Override public void onClick(View v) { command(RecordingService.PAUSE); } });
        stop = button(column,"Stop",new View.OnClickListener() { @Override public void onClick(View v) { command(RecordingService.STOP); } });
        status = text(column,"Idle",16);
        text(column,"Pause ends this AudioRecord run. Resume is a new run and uncertain epoch in the same capture. Stop ends the capture. App recreation never restarts recording. Files remain app-private until a reviewed export/transfer feature exists.",14);
    }
    private TextView text(LinearLayout parent,String value,int size) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setPadding(0,10,0,10); parent.addView(t); return t; }
    private EditText number(LinearLayout p,String label,String value,int id) {
        text(p,label,15); EditText e = new EditText(this); e.setId(id); e.setSingleLine(true); e.setInputType(InputType.TYPE_CLASS_NUMBER); e.setText(value); p.addView(e); return e;
    }
    private Button button(LinearLayout p,String title,View.OnClickListener click) { Button b = new Button(this); b.setText(title); b.setOnClickListener(click); p.addView(b); return b; }
    private void requestStart() {
        if (!resumed || RecorderState.active) return;
        localError = null;
        try { pending = new RecordingSettings(Integer.parseInt(rate.getText().toString()),Integer.parseInt(channels.getText().toString()),Integer.parseInt(seconds.getText().toString()),Long.parseLong(cache.getText().toString())); }
        catch (IllegalArgumentException e) { message(e.getMessage()); return; }
        ArrayList<String> permissions = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) { pendingPermissionStart = true; requestPermissions(permissions.toArray(new String[0]),1); return; }
        startVisible();
    }
    private void startVisible() {
        pendingPermissionStart = false;
        if (!resumed || pending == null) { message("Tap Start again while this screen is visible."); return; }
        if (!RecordingService.permissionsReady(this)) { message("Microphone and visible notifications are required. Enable them in Android settings, then tap Start."); return; }
        Intent i = new Intent(this,RecordingService.class).setAction(RecordingService.START); pending.put(i);
        try { startForegroundService(i); }
        catch (RuntimeException e) { message("Android rejected the foreground start: " + e.getClass().getSimpleName()); }
    }
    private void command(String action) {
        try { startService(new Intent(this,RecordingService.class).setAction(action)); }
        catch (RuntimeException e) { message("Could not send control: " + e.getClass().getSimpleName()); }
    }
    private void message(String message) { localError = message; Toast.makeText(this,message,Toast.LENGTH_LONG).show(); status.setText(message); }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if (request == 1 && pendingPermissionStart) {
            if (RecordingService.permissionsReady(this) && resumed) startVisible();
            else { pendingPermissionStart = false; message("Recording has not started. Grant permissions and tap Start while the app is visible."); }
        }
    }
    @Override protected void onResume() { super.onResume(); resumed = true; main.post(refresh); }
    @Override protected void onPause() { resumed = false; main.removeCallbacks(refresh); super.onPause(); }
}
