package android.media; public class AudioDeviceInfo {
 public static final int TYPE_BUILTIN_MIC=1,TYPE_WIRED_HEADSET=2,TYPE_USB_DEVICE=3,TYPE_USB_HEADSET=4;
 public boolean isSource(){return true;}public int getType(){return TYPE_BUILTIN_MIC;}public int getId(){return 1;}public CharSequence getProductName(){return "host microphone";}}
