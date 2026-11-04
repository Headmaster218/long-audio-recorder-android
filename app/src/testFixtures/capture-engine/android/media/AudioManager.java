package android.media; public class AudioManager { public static final int GET_DEVICES_INPUTS=1;
 public AudioDeviceInfo[] getDevices(int n){return new AudioDeviceInfo[]{new AudioDeviceInfo()};}
 public static class AudioRecordingCallback {public void onRecordingConfigChanged(java.util.List<AudioRecordingConfiguration> c){}}}
