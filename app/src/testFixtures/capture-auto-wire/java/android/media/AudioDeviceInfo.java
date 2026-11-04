package android.media; public class AudioDeviceInfo {
 public static final int TYPE_BUILTIN_MIC=15,TYPE_WIRED_HEADSET=3,TYPE_USB_DEVICE=11,TYPE_USB_HEADSET=22;
 public boolean isSource(){return true;}public int getType(){return TYPE_BUILTIN_MIC;}public int getId(){return 1;}public CharSequence getProductName(){return "host microphone";}}
