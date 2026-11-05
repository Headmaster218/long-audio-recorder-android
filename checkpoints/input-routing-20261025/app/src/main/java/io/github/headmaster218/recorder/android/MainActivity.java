package io.github.headmaster218.recorder.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import io.github.headmaster218.recorder.core.OfflineDayPlan;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Map;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText rate, channels, seconds, cache;
    private TextView status, capacity;
    private ExportControls exports;
    private FtpsControls ftps;
    private InputControls inputs;
    private Button start, pause, stop, planDay;
    private final io.github.headmaster218.recorder.core.ReadOnlyPlanGate planGate=new io.github.headmaster218.recorder.core.ReadOnlyPlanGate();
    private Button recoverSealed,cancelRecovery;
    private TextView recoveryStatus;
    private SealedRecoveryCoordinator sealedRecovery;
    private boolean resumed, pendingRecoveryOptIn;
    private CheckBox recovery;
    private final RecordingSettings.PendingStart pending = new RecordingSettings.PendingStart();
    private RecordingSettingsStore settingsStore;
    private String localError;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            status.setText(localError != null && !RecorderState.active ? localError : RecorderState.display(MainActivity.this));
            boolean running = RecorderState.active;
            capacity.setText(RecordingCapacityDisplay.display(running));
            start.setEnabled(!running && !pending.waiting() && !sealedRecovery.busy() && !planGate.busy());
            planDay.setEnabled(!running && !pending.waiting() && !sealedRecovery.busy() && !planGate.busy()); pause.setEnabled(running || pending.waiting());
            recoverSealed.setEnabled(!running && !pending.waiting() && !sealedRecovery.busy());
            cancelRecovery.setEnabled(sealedRecovery.busy());
            recoveryStatus.setText(sealedRecovery.status());
            rate.setEnabled(!running && !pending.waiting()); channels.setEnabled(!running && !pending.waiting()); seconds.setEnabled(!running && !pending.waiting()); cache.setEnabled(!running && !pending.waiting());
            inputs.enabled(!running && !pending.waiting());
            exports.render();
            ftps.render();
            main.postDelayed(this,1000);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        final SharedPreferences preferences = getSharedPreferences(RecordingSettingsStore.PREFS,MODE_PRIVATE);
        settingsStore = new RecordingSettingsStore(new RecordingSettingsStore.Storage() {
            @Override public Map<String, ?> read() { return preferences.getAll(); }
            @Override public boolean write(Map<String, Object> values) {
                SharedPreferences.Editor editor = preferences.edit();
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    if (entry.getValue() instanceof Integer) editor.putInt(entry.getKey(),(Integer) entry.getValue());
                    else editor.putLong(entry.getKey(),(Long) entry.getValue());
                }
                return editor.commit();
            }
        });
        RecordingSettingsStore.Loaded loaded = settingsStore.load();
        RecordingSettings defaults = loaded.settings;
        localError = loaded.warning;
        ScrollView scroll = new ScrollView(this); LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL); int pad = (int) (20 * getResources().getDisplayMetrics().density); column.setPadding(pad,pad,pad,pad);
        scroll.addView(column); setContentView(scroll);
        text(column,"Recorder · development build",24);
        text(column,"Visible microphone recording. PCM16 WAV in private local storage. Automatic FTPS enrollment and local cleanup default OFF; review their current settings below. Manual SAF export and FTPS controls are available below. Screen-off and long-run behavior are unverified.",16);
        rate = number(column,"Sample rate (Hz)",String.valueOf(defaults.rate),101);
        channels = number(column,"Channels: 1 mono or 2 stereo",String.valueOf(defaults.channels),102);
        seconds = number(column,"Segment duration (seconds)",String.valueOf(defaults.seconds),103);
        cache = number(column,"Spool maximum (MiB); 32 MiB free-space reserve",String.valueOf(defaults.cacheBytes / RecordingSettings.MIB),104);
        planDay=button(column,"Plan 24-hour offline cache…",new View.OnClickListener() { @Override public void onClick(View v) { planOfflineDay(); } });
        text(column,"The default 512 MiB cache is not enough for a day at 16 kHz mono. Plan a larger cache using current format and existing files. Planning never saves, records, uploads or deletes anything.",14);
        button(column,"Save settings",new View.OnClickListener() { @Override public void onClick(View v) { saveSettings(); } });
        text(column,"Save stores only these four defaults. Start uses the displayed values without saving. Neither opening this screen nor saving starts recording. Saving never changes an active run.",14);
        inputs = new InputControls(this,column);
        recovery = new CheckBox(this); recovery.setText("Experimental interruption recovery for the next session (off by default; up to 8 retries, 30 s maximum backoff)");
        recovery.setChecked(false); recovery.setSaveEnabled(false); column.addView(recovery);
        start = button(column,"Start / resume",new View.OnClickListener() { @Override public void onClick(View v) { requestStart(); } });
        pause = button(column,"Pause",new View.OnClickListener() { @Override public void onClick(View v) { command(RecordingService.PAUSE); } });
        stop = button(column,"Stop",new View.OnClickListener() { @Override public void onClick(View v) { command(RecordingService.STOP); } });
        status = text(column,"Idle",16);
        capacity = text(column,RecordingCapacityDisplay.display(false),14);
        text(column,"Pause ends this AudioRecord run. Resume within the same process uses a new run and uncertain epoch. After process restart, Start creates a fresh capture. Stop ends the capture. App recreation never restarts recording. Completed files can be copied with the manual export controls below; originals remain app-private unless separate verified-transfer cleanup is enabled.",14);
        text(column,"Android may let you dismiss the ongoing notification. A swipe alone does not stop recording or trigger forced reposts. Reopen this app for Stop/Pause; Android microphone/active-app controls remain available where supported.",14);
        sealedRecovery=SealedRecoveryCoordinator.get(this);
        text(column,"Recover sealed recordings after an interruption",20);
        text(column,"Only recordings with an intact final manifest, header and matching hashes can become completed files. Unfinished or damaged audio stays untouched. This does not resume the microphone or invent Stop. Stop recording and finish exports first. Each tap checks up to 32 staged objects, skips completed audio and saves sweep progress for the next tap. Large segments can take time. Cancel stops pre-publication checks; a publication already underway must finish safely before Start/export unlock. Work continues through screen recreation. Existing opted-in upload and cleanup policies may later apply to recovered completed files.",14);
        recoverSealed=button(column,"Check / recover sealed recordings",new View.OnClickListener(){@Override public void onClick(View v){
            if(!resumed || RecorderState.active || pending.waiting() || sealedRecovery.busy())return;
            try{sealedRecovery.start(displayedSettings().cacheBytes);recoverSealed.setEnabled(false);start.setEnabled(false);recoveryStatus.setText(sealedRecovery.status());}
            catch(IllegalArgumentException failed){message(failed.getMessage());}
        }});
        cancelRecovery=button(column,"Cancel recovery checks",new View.OnClickListener(){@Override public void onClick(View v){sealedRecovery.cancel();recoveryStatus.setText(sealedRecovery.status());}});
        cancelRecovery.setEnabled(sealedRecovery.busy());
        recoveryStatus=text(column,sealedRecovery.status(),14);
        exports = new ExportControls(this,column,saved);
        ftps = new FtpsControls(this,column);
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); exports.saveState(out); }
    @Override protected void onActivityResult(int request,int result,Intent data) { super.onActivityResult(request,result,data); exports.result(request,result,data); }
    private TextView text(LinearLayout parent,String value,int size) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setPadding(0,10,0,10); parent.addView(t); return t; }
    private EditText number(LinearLayout p,String label,String value,int id) {
        text(p,label,15); EditText e = new EditText(this); e.setId(id); e.setSingleLine(true); e.setInputType(InputType.TYPE_CLASS_NUMBER); e.setText(value); p.addView(e); return e;
    }
    private Button button(LinearLayout p,String title,View.OnClickListener click) { Button b = new Button(this); b.setText(title); b.setOnClickListener(click); p.addView(b); return b; }
    private RecordingSettings displayedSettings() {
        return new RecordingSettings(Integer.parseInt(rate.getText().toString()),Integer.parseInt(channels.getText().toString()),Integer.parseInt(seconds.getText().toString()),Long.parseLong(cache.getText().toString()),inputs.snapshot());
    }
    private void planOfflineDay() {
        if(!resumed || RecorderState.active || pending.waiting() || sealedRecovery.busy() || planGate.busy())return;
        final RecordingSettings proposed;
        try {
            // Validate format independently of a too-small existing cache field.
            proposed=new RecordingSettings(Integer.parseInt(rate.getText().toString()),Integer.parseInt(channels.getText().toString()),Integer.parseInt(seconds.getText().toString()),262144,inputs.snapshot());
        } catch(IllegalArgumentException invalid){message(invalid.getMessage());return;}
        final java.nio.file.Path files;
        try {files=getFilesDir().toPath();}catch(RuntimeException unavailable){message("Private storage is unavailable. Try again.");return;}
        final Object planTicket=planGate.begin();planDay.setEnabled(false);start.setEnabled(false);
        try {Thread planner=new Thread(new Runnable(){@Override public void run(){
            OfflineDayPlan result=null;String error=null;
            try {result=OfflineDayPlan.inspect(files,proposed.rate,proposed.channels,proposed.seconds);}
            catch(java.io.IOException | RuntimeException failed){error="24-hour plan unavailable: "+failed.getMessage();}
            final OfflineDayPlan plan=result;final String failure=error;
            main.post(new Runnable(){@Override public void run(){
                if(!planGate.finish(planTicket))return;
                if(!resumed || isFinishing() || isDestroyed() || RecorderState.active)return;
                if(failure!=null){message(failure);return;}
                new AlertDialog.Builder(MainActivity.this).setTitle("24-hour offline cache plan")
                    .setMessage("Format: "+proposed.rate+" Hz, "+proposed.channels+" channel(s), "+proposed.seconds+" s segments.\nProposed cache maximum: "+plan.cacheMiB+" MiB. Existing spool: "+plan.existingBytes+" bytes / "+plan.existingObjects+" objects.\nRequired currently free storage: "+plan.requiredFreeBytes+" bytes, including 32 MiB reserve. Observed free (snapshot, not reserved): "+plan.observedFreeBytes+" bytes.\n\nSized for at least 24 hours of PCM plus bounded metadata and a 5% planning margin at steady input, without network or future cleanup. This is a storage budget, not proof existing files are healthy or recording will start. It does not reserve disk space or guarantee Android stays recording. Route changes, other disk use, battery or errors can stop earlier. If storage fills, recording stops and files are preserved, never overwritten.\n\nThis may retain several GiB of private audio. Transfers and cleanup remain separately controlled; uploads require their existing conditions. Apply changes only the cache field. Save is separate; Start remains explicit.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Apply "+plan.cacheMiB+" MiB",new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface dialog,int which){
                        if(!resumed || RecorderState.active || pending.waiting() || sealedRecovery.busy())return;
                        try {
                            if(Integer.parseInt(rate.getText().toString())!=proposed.rate || Integer.parseInt(channels.getText().toString())!=proposed.channels || Integer.parseInt(seconds.getText().toString())!=proposed.seconds){message("Format changed. Run the 24-hour plan again.");return;}
                            cache.setText(Long.toString(plan.cacheMiB));localError=null;
                            Toast.makeText(MainActivity.this,"Cache field updated only. Save or Start remains your choice; available space may change.",Toast.LENGTH_LONG).show();
                        } catch(IllegalArgumentException changed){message("Format changed. Run the 24-hour plan again.");}
                    }}).show();
            }});
        }},"recorder-capacity-plan");
        planner.start();}catch(RuntimeException failed){planGate.finish(planTicket);message("Capacity check did not start. Try again.");}
    }
    private void saveSettings() {
        try {
            String failure = settingsStore.save(displayedSettings());
            if (failure != null) message(failure);
            else { localError = null; Toast.makeText(this,"Settings saved. Recording state is unchanged.",Toast.LENGTH_LONG).show(); }
        } catch (IllegalArgumentException failure) { message(failure.getMessage()); }
    }
    private void requestStart() {
        if (!resumed || RecorderState.active || pending.waiting() || sealedRecovery.busy() || planGate.busy()) return;
        localError = null;
        pendingRecoveryOptIn = recovery.isChecked();
        final RecordingSettings snapshot;
        try { snapshot = displayedSettings(); }
        catch (IllegalArgumentException e) { message(e.getMessage()); return; }
        ArrayList<String> permissions = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) {
            try {
                int request = pending.begin(snapshot);
                start.setEnabled(false); pause.setEnabled(true);
                requestPermissions(permissions.toArray(new String[0]),request);
            } catch (RuntimeException failure) { pending.cancel(); message("Permission request did not start: " + failure.getMessage()); }
            return;
        }
        startVisible(snapshot);
    }
    private void startVisible(RecordingSettings snapshot) {
        if (!resumed || snapshot == null || RecorderState.active) { message("Tap Start again while this screen is visible."); return; }
        if (!RecordingService.permissionsReady(this)) { message("Microphone and visible notifications are required. Enable them in Android settings, then tap Start."); return; }
        Intent i = new Intent(this,RecordingService.class).setAction(RecordingService.START); snapshot.put(i);
        i.putExtra("recovery_opt_in",pendingRecoveryOptIn); pendingRecoveryOptIn = false; recovery.setChecked(false); inputs.consumed();
        try { startForegroundService(i); }
        catch (RuntimeException e) { message("Android rejected the foreground start: " + e.getClass().getSimpleName()); }
    }
    private void command(String action) {
        pending.cancel(); pendingRecoveryOptIn = false;
        try { startService(new Intent(this,RecordingService.class).setAction(action)); }
        catch (RuntimeException e) { message("Could not send control: " + e.getClass().getSimpleName()); }
    }
    private void message(String message) { localError = message; Toast.makeText(this,message,Toast.LENGTH_LONG).show(); status.setText(message); }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if (ftps != null) ftps.permissionResult(request);
        if (pending.matches(request)) {
            RecordingSettings snapshot = pending.consume(request);
            if (RecordingService.permissionsReady(this) && resumed) startVisible(snapshot);
            else message("Recording has not started. Grant permissions and tap Start while the app is visible.");
        }
    }
    @Override protected void onDestroy() { pending.cancel(); pendingRecoveryOptIn = false; super.onDestroy(); }
    @Override protected void onResume() { super.onResume(); resumed = true; main.post(refresh); }
    @Override protected void onPause() { resumed = false; planGate.cancel(); main.removeCallbacks(refresh); super.onPause(); }
}
