package io.github.headmaster218.recorder.android;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.headmaster218.recorder.core.CleanPauseGate;

/** Bounded last-state record, not a complete crash journal. Audio/epoch metadata lives in the spool. */
final class RecorderState {
    static final String PREFS = "recorder-state";
    private static final CleanPauseGate CLEAN_PAUSE = new CleanPauseGate();
    static volatile boolean active;
    static volatile String status = "Idle";
    static volatile String route = "No active input";
    static volatile long writtenFrames;
    private RecorderState() { }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static CleanPauseGate.Resume takeCleanPause() { return CLEAN_PAUSE.consume(); }
    static void starting(Context c, String capture, long epoch) {
        CLEAN_PAUSE.clear();
        active = true; status = "Starting visible microphone service"; route = "Verifying actual input"; writtenFrames = 0;
        prefs(c).edit().putBoolean("active", true).putBoolean("paused",false).putString("capture", capture).putLong("epoch",epoch)
            .putString("result", "Recording was active; if the process stopped, inspect preserved files before restarting.").apply();
    }
    static void finish(Context c, String result, boolean paused, String capture, long nextEpoch) {
        if (paused) CLEAN_PAUSE.cleanPause(capture,nextEpoch); else CLEAN_PAUSE.clear();
        status = bounded(result); active = false;
        prefs(c).edit().putBoolean("active",false).putBoolean("paused",paused).putString("capture",capture)
            .putLong("epoch",nextEpoch).putString("result",status).putString("route",bounded(route)).putLong("frames",writtenFrames).apply();
    }
    static String display(Context c) {
        if (active) return status + "\n" + route + "\nPCM frames written: " + writtenFrames;
        SharedPreferences p = prefs(c);
        if (p.getBoolean("active",false)) return "Previous recording ended without a clean stop. Files are preserved. Start creates a new run; nothing resumes automatically.";
        if (p.getBoolean("paused",false) && !CLEAN_PAUSE.hasCleanPause())
            return "A previous capture was paused, but this process has no live resume token. Start begins a fresh capture; existing files remain preserved.";
        return p.getString("result","Idle. No recording starts until you tap Start.") + "\n" + p.getString("route", "");
    }
    static String bounded(String s) { return s == null ? "Unknown interruption" : s.substring(0, Math.min(s.length(), 1200)); }
}
