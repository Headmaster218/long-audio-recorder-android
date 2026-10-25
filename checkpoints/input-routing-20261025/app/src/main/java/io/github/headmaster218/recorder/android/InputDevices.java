package io.github.headmaster218.recorder.android;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import java.util.ArrayList;
import java.util.List;
import io.github.headmaster218.recorder.core.InputRoutePolicy;

/** MIC source only; never changes communication mode, SCO, audio focus or call routing. */
final class InputDevices implements InputRoutePolicy.Port {
    private final AudioManager manager;
    private final AudioRecord recorder;
    InputDevices(Context context,AudioRecord recorder) {
        manager=(AudioManager) context.getSystemService(Context.AUDIO_SERVICE); this.recorder=recorder;
    }
    static int kind(AudioDeviceInfo device) {
        if (device == null || !device.isSource()) return InputRoutePolicy.UNSUPPORTED;
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return InputRoutePolicy.PHONE;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return InputRoutePolicy.WIRED;
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET: return InputRoutePolicy.USB;
            default: return InputRoutePolicy.UNSUPPORTED;
        }
    }
    static InputRoutePolicy.Device descriptor(AudioDeviceInfo device) {
        return device == null ? null : new InputRoutePolicy.Device(device.getId(),kind(device));
    }
    AudioDeviceInfo[] available() {
        if (manager == null) return new AudioDeviceInfo[0];
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
    }
    @Override public List<InputRoutePolicy.Device> inputs() {
        List<InputRoutePolicy.Device> result=new ArrayList<InputRoutePolicy.Device>();
        for (AudioDeviceInfo device : available()) result.add(descriptor(device));
        return result;
    }
    @Override public boolean prefer(InputRoutePolicy.Device target) {
        for (AudioDeviceInfo device : available()) if (InputRoutePolicy.same(target,descriptor(device)))
            return recorder.setPreferredDevice(device);
        return false;
    }
    @Override public InputRoutePolicy.Device actual() { return descriptor(recorder.getRoutedDevice()); }
}
