package io.github.headmaster218.recorder.android;

import android.content.Intent;
import io.github.headmaster218.recorder.core.InputRoutePolicy;

/** Immutable input authority across permission callbacks; no Android device proof. */
public final class InputSettingsTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        RecordingSettings chosen=new RecordingSettings(16000,1,300,512,
            new InputRoutePolicy.Request(InputRoutePolicy.USB,7,true));
        Intent intent=new Intent(); chosen.put(intent);
        RecordingSettings restored=RecordingSettings.from(intent);
        check(restored.input.kind == InputRoutePolicy.USB && restored.input.id == 7 && restored.input.phoneFallback);
        check(restored.requestedInput().contains("explicit-phone-fallback"));
        check(RecordingSettings.from(new Intent()).input.kind == InputRoutePolicy.PHONE);
        check(!RecordingSettings.from(new Intent()).input.phoneFallback);
        RecordingSettings.PendingStart pending=new RecordingSettings.PendingStart();
        int canceled=pending.begin(chosen); pending.cancel(); // Pause, Stop or destroyed Activity.
        check(pending.consume(canceled) == null);
        int current=pending.begin(RecordingSettings.defaults());
        check(pending.consume(canceled) == null && pending.waiting());
        check(!pending.consume(current).input.phoneFallback);
        check(pending.consume(current) == null);
        int exact=pending.begin(chosen); check(pending.consume(exact) == chosen);
        try { RecordingSettings.from(new Intent().putExtra("input_kind",99)); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        System.out.println("InputSettingsTest passed (host Intent/permission authority only)");
    }
}
